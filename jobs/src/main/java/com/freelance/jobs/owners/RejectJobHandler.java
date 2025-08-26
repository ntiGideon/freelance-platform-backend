package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.events.JobRejectedEvent;
import com.freelance.jobs.exceptions.JobNotOwnedException;
import com.freelance.jobs.exceptions.JobNotSubmittedException;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.model.RejectJobRequest;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * Handler for POST /job/owner/reject/{jobId}
 * Rejects a submitted job and resets it to open status
 */
public class RejectJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;
    private final String eventBusName;

    public RejectJobHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.eventBridgeClient = EventBridgeClient.create();
        this.objectMapper = new ObjectMapper();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
        this.eventBusName = System.getenv("EVENT_BUS_NAME");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String jobId = RequestMapper.extractJobIdFromPath(input.getPath());
            String ownerId = RequestMapper.extractOwnerIdFromContext(input);

            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }
            
            if (ownerId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            // Parse request body for optional message
            String rejectionMessage = null;
            if (input.getBody() != null && !input.getBody().trim().isEmpty()) {
                try {
                    String requestBody = input.getBody();
                    if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded()) {
                        requestBody = new String(Base64.getDecoder().decode(requestBody), StandardCharsets.UTF_8);
                    }
                    
                    RejectJobRequest rejectRequest = objectMapper.readValue(requestBody, RejectJobRequest.class);
                    if (!rejectRequest.isValid()) {
                        return ResponseUtil.createErrorResponse(400, "Invalid request: message too long (max 1000 characters)");
                    }
                    rejectionMessage = rejectRequest.trimmedMessage();
                } catch (Exception e) {
                    return ResponseUtil.createErrorResponse(400, "Invalid JSON in request body");
                }
            }

            context.getLogger().log("Rejecting job " + jobId + " by owner " + ownerId + 
                (rejectionMessage != null ? " with message" : " without message"));

            // Reject the job and reset to open
            JobEntity rejectedJob = rejectJob(jobId, ownerId, rejectionMessage, context);
            
            // Publish job.rejected event
            publishJobRejectedEvent(rejectedJob, rejectionMessage, context);

            return ResponseUtil.createSuccessResponse(200, Map.of(
                    "message", "Job rejected and reset to open status",
                    "jobId", jobId,
                    "status", "open",
                    "rejectedAt", rejectedJob.updatedAt(),
                    "rejectionMessage", rejectionMessage != null ? rejectionMessage : ""
            ));

        } catch (JobNotSubmittedException e) {
            return ResponseUtil.createErrorResponse(400, e.getMessage());
        } catch (JobNotOwnedException e) {
            return ResponseUtil.createErrorResponse(403, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error rejecting job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity rejectJob(String jobId, String ownerId, String rejectionMessage, Context context) 
            throws JobNotSubmittedException, JobNotOwnedException {
        try {
            Instant now = Instant.now();
            
            // Get current job to validate
            JobEntity currentJob = getJobById(jobId);
            if (currentJob == null) {
                throw new JobNotSubmittedException("Job not found");
            }

            // Validate job can be rejected by this owner
            validateJobForRejection(currentJob, ownerId);

            // Store the claimer info before resetting
            String originalClaimerId = currentJob.claimerId();

            // Atomic update to reset job to open status and clear claim fields, with optional rejection message
            String updateExpression = rejectionMessage != null ?
                "SET #status = :openStatus, #updatedAt = :updatedAt, #rejectionMessage = :rejectionMessage REMOVE #claimerId, #claimedAt, #submissionDeadline, #submittedAt, #submissionMessage" :
                "SET #status = :openStatus, #updatedAt = :updatedAt REMOVE #claimerId, #claimedAt, #submissionDeadline, #submittedAt, #submissionMessage";
            
            Map<String, String> expressionAttributeNames = Map.of(
                "#status", "status",
                "#ownerId", "ownerId", 
                "#updatedAt", "updatedAt",
                "#claimerId", "claimerId",
                "#claimedAt", "claimedAt",
                "#submissionDeadline", "submissionDeadline", 
                "#submittedAt", "submittedAt",
                "#submissionMessage", "submissionMessage",
                "#rejectionMessage", "rejectionMessage"
            );
            
            Map<String, AttributeValue> expressionAttributeValues = Map.of(
                ":openStatus", AttributeValue.builder().s("open").build(),
                ":submittedStatus", AttributeValue.builder().s("submitted").build(),
                ":ownerId", AttributeValue.builder().s(ownerId).build(),
                ":updatedAt", AttributeValue.builder().s(now.toString()).build(),
                ":rejectionMessage", AttributeValue.builder().s(rejectionMessage != null ? rejectionMessage : "").build()
            );
            
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .updateExpression(updateExpression)
                    .conditionExpression("#status = :submittedStatus AND #ownerId = :ownerId")
                    .expressionAttributeNames(expressionAttributeNames)
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();

            dynamoDbClient.updateItem(updateRequest);

            context.getLogger().log("Successfully rejected job " + jobId + " by owner " + ownerId);

            // Return job entity with reset fields but preserve original claimer for event
            return JobEntity.builder()
                    .jobId(currentJob.jobId())
                    .ownerId(currentJob.ownerId())
                    .categoryId(currentJob.categoryId())
                    .name(currentJob.name())
                    .description(currentJob.description())
                    .payAmount(currentJob.payAmount())
                    .timeToCompleteSeconds(currentJob.timeToCompleteSeconds())
                    .expiryDate(currentJob.expiryDate())
                    .status("open")
                    .createdAt(currentJob.createdAt())
                    .updatedAt(now.toString())
                    .claimerId(originalClaimerId) // Keep for event notification
                    .claimedAt(null)
                    .submissionDeadline(null)
                    .submittedAt(null)
                    .submissionMessage(null) // Cleared on rejection
                    .approvalMessage(null) // Cleared on rejection
                    .rejectionMessage(rejectionMessage)
                    .build();

        } catch (ConditionalCheckFailedException e) {
            throw new JobNotSubmittedException("Job is not in submitted status or does not belong to you");
        } catch (Exception e) {
            context.getLogger().log("Error in reject operation: " + e.getMessage());
            throw new RuntimeException("Error rejecting job", e);
        }
    }

    private void validateJobForRejection(JobEntity job, String ownerId) 
            throws JobNotSubmittedException, JobNotOwnedException {
        
        if (!ownerId.equals(job.ownerId())) {
            throw new JobNotOwnedException("Job does not belong to you");
        }

        if (!"submitted".equals(job.status())) {
            throw new JobNotSubmittedException("Job is not in submitted status (current: " + job.status() + ")");
        }

        if (job.claimerId() == null || job.claimerId().isEmpty()) {
            throw new JobNotSubmittedException("Job has no claimer");
        }
    }


    private void publishJobRejectedEvent(JobEntity job, String rejectionReason, Context context) {
        try {
            JobRejectedEvent event = new JobRejectedEvent(
                    job.jobId(),
                    job.ownerId(),
                    job.claimerId(), // Use original claimer ID
                    null, // claimerEmail not available when owner rejects, will fallback to Cognito lookup
                    job.categoryId(),
                    job.name(),
                    job.payAmount(),
                    job.updatedAt(),
                    rejectionReason
            );

            String eventJson = objectMapper.writeValueAsString(event);

            PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
                    .source("jobs-service")
                    .detailType("job.rejected")
                    .detail(eventJson)
                    .eventBusName(eventBusName)
                    .build();

            PutEventsRequest putEventsRequest = PutEventsRequest.builder()
                    .entries(eventEntry)
                    .build();

            var response = eventBridgeClient.putEvents(putEventsRequest);

            if (response.failedEntryCount() > 0) {
                context.getLogger().log("Failed to publish job.rejected event: " + response.entries());
            } else {
                context.getLogger().log("Successfully published job.rejected event for job: " + job.jobId());
            }

        } catch (Exception e) {
            context.getLogger().log("Error publishing job.rejected event: " + e.getMessage());
        }
    }

    private JobEntity getJobById(String jobId) {
        GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(jobsTableName)
                .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                .build();

        GetItemResponse response = dynamoDbClient.getItem(getItemRequest);
        
        if (!response.hasItem()) {
            return null;
        }

        return JobEntityMapper.mapToJobEntity(response.item());
    }


}