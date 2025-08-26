package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.auth.AdminAuthUtils;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.mappers.JobEntityMapper;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Handler for GET /admin/jobs/statistics
 * Returns job statistics (Admin only)
 */
public class JobStatisticsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;

    public JobStatisticsHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String userId = RequestMapper.extractOwnerIdFromContext(input);

            if (userId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            if (!AdminAuthUtils.isAdminUser(userId)) {
                return ResponseUtil.createErrorResponse(403, "Forbidden: Only admins can view statistics");
            }

            context.getLogger().log("Generating job statistics for admin");

            Map<String, Integer> statusCounts = new HashMap<>();
            int totalJobs = 0;
            int jobsLast7Days = 0;
            int jobsLast30Days = 0;
            double totalPayAmount = 0;
            String lastEvaluatedKey = null;

            do {
                ScanRequest scanRequest = ScanRequest.builder()
                        .tableName(jobsTableName)
                        .exclusiveStartKey(lastEvaluatedKey != null ?
                                Map.of("jobId", AttributeValue.builder().s(lastEvaluatedKey).build()) : null)
                        .build();

                ScanResponse response = dynamoDbClient.scan(scanRequest);

                for (Map<String, AttributeValue> item : response.items()) {
                    JobEntity job = JobEntityMapper.mapToJobEntity(item);
                    totalJobs++;

                    statusCounts.merge(job.status(), 1, Integer::sum);

                    Instant createdAt = Instant.parse(job.createdAt());
                    long daysOld = ChronoUnit.DAYS.between(createdAt, Instant.now());

                    if (daysOld <= 7) jobsLast7Days++;
                    if (daysOld <= 30) jobsLast30Days++;

                    if (job.payAmount() != null) {
                        totalPayAmount += job.payAmount().doubleValue();
                    }
                }

                lastEvaluatedKey = response.lastEvaluatedKey() != null ?
                        response.lastEvaluatedKey().get("jobId").s() : null;

            } while (lastEvaluatedKey != null);

            Map<String, Object> statistics = Map.of(
                    "totalJobs", totalJobs,
                    "statusCounts", statusCounts,
                    "recentJobs", Map.of(
                            "last7Days", jobsLast7Days,
                            "last30Days", jobsLast30Days
                    ),
                    "financials", Map.of(
                            "totalPayAmount", totalPayAmount,
                            "averagePayAmount", totalJobs > 0 ? totalPayAmount / totalJobs : 0
                    ),
                    "timestamps", Map.of(
                            "generatedAt", Instant.now().toString()
                    )
            );

            return ResponseUtil.createSuccessResponse(200, statistics);

        } catch (Exception e) {
            context.getLogger().log("Error generating statistics: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, e.getMessage());
        }
    }
}