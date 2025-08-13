package com.freelance.jobs.seekers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.events.JobClaimedEvent;
import com.freelance.jobs.exceptions.JobAlreadyClaimedException;
import com.freelance.jobs.exceptions.JobNotAvailableException;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * Handler for POST /job/seeker/claim/{jobId}
 * Atomically claims a job for the seeker and starts the countdown
 */
public class ClaimJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;
    private final String eventBusName;

    public ClaimJobHandler() {
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
            String seekerId = RequestMapper.extractSeekerIdFromContext(input);

            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }
            
            if (seekerId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            context.getLogger().log("Claiming job " + jobId + " for seeker " + seekerId);

            // Atomically claim the job
            JobEntity claimedJob = claimJob(jobId, seekerId, context);
            
            // Publish job.claimed event
            publishJobClaimedEvent(claimedJob, context);

            return ResponseUtil.createSuccessResponse(200, Map.of(
                    "message", "Job claimed successfully",
                    "jobId", jobId,
                    "claimedAt", claimedJob.claimedAt(),
                    "submissionDeadline", claimedJob.submissionDeadline()
            ));

        } catch (JobAlreadyClaimedException e) {
            return ResponseUtil.createErrorResponse(409, "Job has already been claimed by another user");
        } catch (JobNotAvailableException e) {
            return ResponseUtil.createErrorResponse(403, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error claiming job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity claimJob(String jobId, String seekerId, Context context) throws JobAlreadyClaimedException, JobNotAvailableException {
        try {
            Instant now = Instant.now();
            
            // First, get the current job to validate it
            JobEntity currentJob = getJobById(jobId);
            if (currentJob == null) {
                throw new JobNotAvailableException("Job not found");
            }

            // Validate job is available for claiming
            validateJobForClaiming(currentJob);

            // Calculate submission deadline
            Instant submissionDeadline = now.plus(currentJob.timeToCompleteSeconds(), ChronoUnit.SECONDS);

            // Atomic update with condition that job is still "open" and not claimed
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .updateExpression("SET #status = :claimedStatus, #claimerId = :seekerId, #claimedAt = :claimedAt, #submissionDeadline = :deadline, #updatedAt = :updatedAt")
                    .conditionExpression("#status = :openStatus AND attribute_not_exists(#claimerId)")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#claimerId", "claimerId",
                            "#claimedAt", "claimedAt",
                            "#submissionDeadline", "submissionDeadline",
                            "#updatedAt", "updatedAt"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":claimedStatus", AttributeValue.builder().s("claimed").build(),
                            ":openStatus", AttributeValue.builder().s("open").build(),
                            ":seekerId", AttributeValue.builder().s(seekerId).build(),
                            ":claimedAt", AttributeValue.builder().s(now.toString()).build(),
                            ":deadline", AttributeValue.builder().s(submissionDeadline.toString()).build(),
                            ":updatedAt", AttributeValue.builder().s(now.toString()).build()
                    ))
                    .build();

            dynamoDbClient.updateItem(updateRequest);

            context.getLogger().log("Successfully claimed job " + jobId + " for seeker " + seekerId);

            // Return updated job entity
            return JobEntity.builder()
                    .jobId(currentJob.jobId())
                    .ownerId(currentJob.ownerId())
                    .categoryId(currentJob.categoryId())
                    .name(currentJob.name())
                    .description(currentJob.description())
                    .payAmount(currentJob.payAmount())
                    .timeToCompleteSeconds(currentJob.timeToCompleteSeconds())
                    .expiryDate(currentJob.expiryDate())
                    .status("claimed")
                    .createdAt(currentJob.createdAt())
                    .updatedAt(now.toString())
                    .claimerId(seekerId)
                    .claimedAt(now.toString())
                    .submissionDeadline(submissionDeadline.toString())
                    .submittedAt(null)
                    .build();

        } catch (ConditionalCheckFailedException e) {
            throw new JobAlreadyClaimedException("Job has already been claimed");
        } catch (Exception e) {
            context.getLogger().log("Error in atomic claim operation: " + e.getMessage());
            throw new RuntimeException("Error claiming job", e);
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

    private void validateJobForClaiming(JobEntity job) throws JobNotAvailableException {
        if (!"open".equals(job.status())) {
            throw new JobNotAvailableException("Job is not available for claiming (status: " + job.status() + ")");
        }

        try {
            Instant expiryDate = Instant.parse(job.expiryDate());
            if (expiryDate.isBefore(Instant.now())) {
                throw new JobNotAvailableException("Job has expired");
            }
        } catch (Exception e) {
            throw new JobNotAvailableException("Job has invalid expiry date");
        }
    }

    private void publishJobClaimedEvent(JobEntity job, Context context) {
        try {
            JobClaimedEvent event = new JobClaimedEvent(
                    job.jobId(),
                    job.ownerId(),
                    job.claimerId(),
                    job.categoryId(),
                    job.name(),
                    job.payAmount(),
                    job.claimedAt(),
                    job.submissionDeadline()
            );

            String eventJson = objectMapper.writeValueAsString(event);

            PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
                    .source("jobs-service")
                    .detailType("job.claimed")
                    .detail(eventJson)
                    .eventBusName(eventBusName)
                    .build();

            PutEventsRequest putEventsRequest = PutEventsRequest.builder()
                    .entries(eventEntry)
                    .build();

            var response = eventBridgeClient.putEvents(putEventsRequest);

            if (response.failedEntryCount() > 0) {
                context.getLogger().log("Failed to publish job.claimed event: " + response.entries());
            } else {
                context.getLogger().log("Successfully published job.claimed event for job: " + job.jobId());
            }

        } catch (Exception e) {
            context.getLogger().log("Error publishing job.claimed event: " + e.getMessage());
            // Don't fail the whole operation just because event publishing failed
        }
    }


}