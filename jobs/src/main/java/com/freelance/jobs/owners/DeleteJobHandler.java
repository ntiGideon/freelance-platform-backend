package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

/**
 * Handler for DELETE /job/owner/delete/{jobId}
 * Allows job owners to delete their own jobs that are still in "open" status
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
            String ownerId = RequestMapper.extractOwnerIdFromContext(input);
            String jobId = RequestMapper.extractJobIdFromPath(input.getPath());

            if (ownerId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }

            context.getLogger().log("Owner " + ownerId + " attempting to delete job: " + jobId);

            // Get the job to validate ownership and status
            JobEntity job = getJobById(jobId);
            if (job == null) {
                return ResponseUtil.createErrorResponse(404, "Job not found");
            }

            // Validate ownership
            if (!job.ownerId().equals(ownerId)) {
                return ResponseUtil.createErrorResponse(403, "Forbidden: You can only delete your own jobs");
            }

            // Only allow deletion of jobs in "open" status
            if (!"open".equals(job.status())) {
                return ResponseUtil.createErrorResponse(400, 
                    "Job can only be deleted while in 'open' status. Current status: " + job.status());
            }

            // Delete the job
            deleteJob(jobId, ownerId);

            context.getLogger().log("Successfully deleted job " + jobId + " by owner " + ownerId);

            return ResponseUtil.createSuccessResponse(200, Map.of(
                "message", "Job deleted successfully",
                "jobId", jobId,
                "deletedAt", java.time.Instant.now().toString()
            ));

        } catch (ConditionalCheckFailedException e) {
            context.getLogger().log("Conditional check failed - job may have been modified: " + e.getMessage());
            return ResponseUtil.createErrorResponse(409, "Job status changed or job doesn't belong to you");
        } catch (Exception e) {
            context.getLogger().log("Error deleting job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity getJobById(String jobId) {
        try {
            GetItemRequest getRequest = GetItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(getRequest);
            if (!response.hasItem()) {
                return null;
            }

            return JobEntityMapper.mapToJobEntity(response.item());
        } catch (Exception e) {
            throw new RuntimeException("Error fetching job: " + jobId, e);
        }
    }

    private void deleteJob(String jobId, String ownerId) {
        try {
            // Use conditional delete to ensure job still belongs to owner and is in "open" status
            DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .conditionExpression("ownerId = :ownerId AND #status = :openStatus")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":ownerId", AttributeValue.builder().s(ownerId).build(),
                            ":openStatus", AttributeValue.builder().s("open").build()
                    ))
                    .build();

            dynamoDbClient.deleteItem(deleteRequest);
        } catch (ConditionalCheckFailedException e) {
            throw e; // Re-throw to be handled by the main handler
        } catch (Exception e) {
            throw new RuntimeException("Error deleting job: " + jobId, e);
        }
    }
}