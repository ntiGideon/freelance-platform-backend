package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.admin.auth.AdminAuthUtils;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.events.JobRejectedEvent;
import com.freelance.admin.exceptions.JobNotSubmittedException;
import com.freelance.admin.mappers.JobEntityMapper;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Handler for POST /admin/jobs/{jobId}/reject
 * Allows admins to reject submitted jobs and reset them to open status
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
            String userId = RequestMapper.extractUserIdFromRequestContext(input, "admin");

            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }

            // Handle base64 encoded request body and parse rejection reason
            String requestBody = input.getBody();
            if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded()) {
                requestBody = new String(Base64.getDecoder().decode(requestBody), StandardCharsets.UTF_8);
            }
            String rejectionReason = parseRejectionReason(requestBody);

            context.getLogger().log("Admin " + userId + " rejecting job " + jobId);

            // Reject the job and reset to open
            JobEntity rejectedJob = rejectJob(jobId, userId, rejectionReason, context);

            // Publish job.rejected event
            publishJobRejectedEvent(rejectedJob, rejectionReason, context);

            return ResponseUtil.createSuccessResponse(200, Map.of(
                    "message", "Job rejected by admin and reset to open status",
                    "jobId", jobId,
                    "status", "open",
                    "rejectedAt", rejectedJob.updatedAt(),
                    "rejectedBy", userId,
                    "rejectionReason", rejectionReason != null ? rejectionReason : ""
            ));

        } catch (JobNotSubmittedException e) {
            return ResponseUtil.createErrorResponse(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error rejecting job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity rejectJob(String jobId, String adminId, String rejectionReason, Context context)
            throws JobNotSubmittedException {
        try {
            Instant now = Instant.now();

            // Get current job to validate
            JobEntity currentJob = getJobById(jobId);
            if (currentJob == null) {
                throw new JobNotSubmittedException("Job not found");
            }

            // Validate job can be rejected (admin doesn't need to be owner)
            validateJobForAdminRejection(currentJob);

            // Store the claimer info before resetting
            String originalClaimerId = currentJob.claimerId();

            // Atomic update to reset job to open status and clear claim fields
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .updateExpression("SET #status = :openStatus, #updatedAt = :updatedAt, #rejectedBy = :adminId REMOVE #claimerId, #claimedAt, #submissionDeadline, #submittedAt")
                    .conditionExpression("#status = :submittedStatus")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#updatedAt", "updatedAt",
                            "#rejectedBy", "rejectedBy",
                            "#claimerId", "claimerId",
                            "#claimedAt", "claimedAt",
                            "#submissionDeadline", "submissionDeadline",
                            "#submittedAt", "submittedAt"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":openStatus", AttributeValue.builder().s("open").build(),
                            ":submittedStatus", AttributeValue.builder().s("submitted").build(),
                            ":updatedAt", AttributeValue.builder().s(now.toString()).build(),
                            ":adminId", AttributeValue.builder().s(adminId).build()
                    ))
                    .build();

            dynamoDbClient.updateItem(updateRequest);

            context.getLogger().log("Successfully rejected job " + jobId + " by admin " + adminId);

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
                    .claimerId(originalClaimerId)
                    .claimedAt(null)
                    .submissionDeadline(null)
                    .submittedAt(null)
                    .build();

        } catch (ConditionalCheckFailedException e) {
            throw new JobNotSubmittedException("Job is not in submitted status");
        } catch (Exception e) {
            context.getLogger().log("Error in reject operation: " + e.getMessage());
            throw new RuntimeException("Error rejecting job", e);
        }
    }

    private void validateJobForAdminRejection(JobEntity job) throws JobNotSubmittedException {
        if (!"submitted".equals(job.status())) {
            throw new JobNotSubmittedException("Job is not in submitted status (current: " + job.status() + ")");
        }

        if (job.claimerId() == null || job.claimerId().isEmpty()) {
            throw new JobNotSubmittedException("Job has no claimer");
        }
    }

    private String parseRejectionReason(String requestBody) {
        try {
            if (requestBody == null || requestBody.trim().isEmpty()) {
                return "No reason provided";
            }

            Map<String, Object> body = objectMapper.readValue(requestBody, Map.class);
            Object reason = body.get("reason");

            if (reason != null && !reason.toString().trim().isEmpty()) {
                return reason.toString().trim();
            }

            return "No reason provided";
        } catch (Exception e) {
            return "No reason provided";
        }
    }

    private void publishJobRejectedEvent(JobEntity job, String rejectionReason, Context context) {
        try {
            JobRejectedEvent event = new JobRejectedEvent(
                    job.jobId(),
                    job.ownerId(),
                    job.claimerId(), // Use original claimer ID
                    job.categoryId(),
                    job.name(),
                    job.payAmount(),
                    job.updatedAt(),
                    rejectionReason
            );

            String eventJson = objectMapper.writeValueAsString(event);

            PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
                    .source("admin-service")
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