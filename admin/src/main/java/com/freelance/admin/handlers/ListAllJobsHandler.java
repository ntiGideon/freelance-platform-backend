package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.mappers.JobEntityMapper;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.HashMap;
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
            String userId = RequestMapper.extractUserIdFromRequestContext(input, "admin");
            if (userId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            // Get query parameters
            String statusFilter = RequestMapper.getQueryParameter(input, "status");
            int limit = parseInt(RequestMapper.getQueryParameter(input, "limit", "100"));
            String nextToken = RequestMapper.getQueryParameter(input, "nextToken");

            context.getLogger().log("Admin listing all jobs with status: " + statusFilter);

            // Build scan request
            ScanRequest.Builder scanRequestBuilder = ScanRequest.builder()
                    .tableName(jobsTableName)
                    .limit(limit);

            // Add status filter if provided
            if (statusFilter != null && !statusFilter.isEmpty()) {
                scanRequestBuilder.filterExpression("#status = :status")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":status", AttributeValue.builder().s(statusFilter).build()));
            }

            // Add pagination token if provided
            if (nextToken != null && !nextToken.isEmpty()) {
                scanRequestBuilder.exclusiveStartKey(parsePaginationToken(nextToken));
            }

            ScanResponse response = dynamoDbClient.scan(scanRequestBuilder.build());
            List<JobEntity> jobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                jobs.add(JobEntityMapper.mapToJobEntity(item));
            }

            // Prepare response with pagination token
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("jobs", jobs);
            responseBody.put("count", jobs.size());
            responseBody.put("nextToken", serializePaginationToken(response.lastEvaluatedKey()));

            return ResponseUtil.createSuccessResponse(200, responseBody);

        } catch (Exception e) {
            context.getLogger().log("Error listing all jobs: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Error listing jobs: " + e.getMessage());
        }
    }

    private int parseInt(String value, String defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            try {
                return Integer.parseInt(defaultValue);
            } catch (NumberFormatException ex) {
                return 100; // fallback default
            }
        }
    }

    private int parseInt(String value) {
        return parseInt(value, "100");
    }

    /**
     * Parse pagination token from string to DynamoDB key format
     */
    private Map<String, AttributeValue> parsePaginationToken(String token) {
        // Simple implementation - assuming token is just the jobId
        // For more complex tokens, you might need to encode/decode JSON
        return Map.of("jobId", AttributeValue.builder().s(token).build());
    }

    /**
     * Serialize pagination token from DynamoDB key format to string
     */
    private String serializePaginationToken(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }

        // Try to extract jobId from the last evaluated key
        AttributeValue jobIdValue = lastEvaluatedKey.get("jobId");
        if (jobIdValue != null && jobIdValue.s() != null) {
            return jobIdValue.s();
        }

        // If jobId is not available, try to serialize the entire key as JSON
        // For simplicity, we'll just return the first value we find
        for (Map.Entry<String, AttributeValue> entry : lastEvaluatedKey.entrySet()) {
            if (entry.getValue().s() != null) {
                return entry.getValue().s();
            } else if (entry.getValue().n() != null) {
                return entry.getValue().n();
            }
        }

        return null;
    }
}