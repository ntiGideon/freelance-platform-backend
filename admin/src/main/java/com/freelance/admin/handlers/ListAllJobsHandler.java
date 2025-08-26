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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handler for GET /admin/jobs
 * Returns all jobs in the system (Admin only)
 */
public class ListAllJobsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;

    public ListAllJobsHandler() {
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
                return ResponseUtil.createErrorResponse(403, "Forbidden: Only admins can list all jobs");
            }

            // Get query parameters
            String statusFilter = RequestMapper.getQueryParameter(input, "status");
            int limit = parseInt(RequestMapper.getQueryParameter(input, "limit", "100"));
            String nextToken = RequestMapper.getQueryParameter(input, "nextToken");

            context.getLogger().log("Admin listing all jobs with status: " + statusFilter);

            // Scan the jobs table with optional filtering
            ScanRequest.Builder scanRequest = ScanRequest.builder()
                    .tableName(jobsTableName)
                    .limit(limit);

            if (statusFilter != null && !statusFilter.isEmpty()) {
                scanRequest.filterExpression("#status = :status")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":status", AttributeValue.builder().s(statusFilter).build()));
            }

            if (nextToken != null && !nextToken.isEmpty()) {
                scanRequest.exclusiveStartKey(Map.of("jobId", AttributeValue.builder().s(nextToken).build()));
            }

            ScanResponse response = dynamoDbClient.scan(scanRequest.build());
            List<JobEntity> jobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                jobs.add(JobEntityMapper.mapToJobEntity(item));
            }

            // Prepare response with pagination token
            Map<String, Object> responseBody = Map.of(
                    "jobs", jobs,
                    "count", jobs.size(),
                    "nextToken", response.lastEvaluatedKey() != null ?
                            response.lastEvaluatedKey().get("jobId").s() : null
            );

            return ResponseUtil.createSuccessResponse(200, responseBody);

        } catch (Exception e) {
            context.getLogger().log("Error listing all jobs: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}