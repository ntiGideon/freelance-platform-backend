package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;

/**
 * Handler for DELETE /admin/jobs/{jobId}
 * Deletes a job from the system (Admin only)
 */
public class DeleteJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;

    public DeleteJobHandler() {
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

            // Extract jobId from path parameters
            String jobId = RequestMapper.extractJobIdFromPath(input.getPath());
            if (jobId == null || jobId.isEmpty()) {
                return ResponseUtil.createErrorResponse(400, "Job ID is required");
            }

            context.getLogger().log("Admin attempting to delete job: " + jobId);

            // First, check if the job exists
            if (!jobExists(jobId)) {
                return ResponseUtil.createErrorResponse(404, "Job not found: " + jobId);
            }

            // Delete the job
            boolean deleted = deleteJob(jobId);

            if (deleted) {
                context.getLogger().log("Successfully deleted job: " + jobId);
                return ResponseUtil.createSuccessResponse(200, Map.of(
                        "message", "Job deleted successfully",
                        "jobId", jobId
                ));
            } else {
                return ResponseUtil.createErrorResponse(500, "Failed to delete job: " + jobId);
            }

        } catch (ConditionalCheckFailedException e) {
            context.getLogger().log("Job does not exist or condition check failed: " + e.getMessage());
            return ResponseUtil.createErrorResponse(404, "Job not found");
        } catch (Exception e) {
            context.getLogger().log("Error deleting job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Error deleting job: " + e.getMessage());
        }
    }

    /**
     * Check if a job exists before attempting to delete
     */
    private boolean jobExists(String jobId) {
        try {
            GetItemRequest getRequest = GetItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(getRequest);
            return response.hasItem() && response.item() != null && !response.item().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete the job from DynamoDB
     */
    private boolean deleteJob(String jobId) {
        try {
            DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .conditionExpression("attribute_exists(jobId)")
                    .build();

            DeleteItemResponse response = dynamoDbClient.deleteItem(deleteRequest);
            return response.sdkHttpResponse().isSuccessful();
        } catch (ConditionalCheckFailedException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete job: " + e.getMessage(), e);
        }
    }
}