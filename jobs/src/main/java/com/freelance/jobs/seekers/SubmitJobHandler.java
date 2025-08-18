package com.freelance.jobs.seekers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.events.JobSubmittedEvent;
import com.freelance.jobs.exceptions.JobNotClaimedException;
import com.freelance.jobs.exceptions.SubmissionDeadlineExceededException;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * Handler for POST /job/seeker/submit/{jobId}
 * Submits a completed job if deadline hasn't passed
 */
public class SubmitJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;
    private final String eventBusName;

    public SubmitJobHandler() {
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
            String seekerEmail = RequestMapper.extractUserEmailFromRequestContext(input);

            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }
            
            if (seekerId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            context.getLogger().log("Submitting job " + jobId + " by seeker " + seekerId);

            // Submit the job
            JobEntity submittedJob = submitJob(jobId, seekerId, context);
            
            // Publish job.submitted event with claimer email
            publishJobSubmittedEvent(submittedJob, seekerEmail, context);

            return ResponseUtil.createSuccessResponse(200, Map.of(
                    "message", "Job submitted successfully",
                    "jobId", jobId,
                    "submittedAt", submittedJob.submittedAt(),
                    "status", submittedJob.status()
            ));

        } catch (JobNotClaimedException e) {
            return ResponseUtil.createErrorResponse(403, "Job is not claimed by you");
        } catch (SubmissionDeadlineExceededException e) {
            return ResponseUtil.createErrorResponse(409, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error submitting job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity submitJob(String jobId, String seekerId, Context context) 
            throws JobNotClaimedException, SubmissionDeadlineExceededException {
        try {
            Instant now = Instant.now();
            
            // Get current job to validate
            JobEntity currentJob = getJobById(jobId);
            if (currentJob == null) {
                throw new JobNotClaimedException("Job not found");
            }

            // Validate job can be submitted by this seeker
            validateJobForSubmission(currentJob, seekerId, now);

            // Atomic update to set status to "submitted"
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .updateExpression("SET #status = :submittedStatus, #submittedAt = :submittedAt, #updatedAt = :updatedAt")
                    .conditionExpression("#status = :claimedStatus AND #claimerId = :seekerId")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#claimerId", "claimerId",
                            "#submittedAt", "submittedAt",
                            "#updatedAt", "updatedAt"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":submittedStatus", AttributeValue.builder().s("submitted").build(),
                            ":claimedStatus", AttributeValue.builder().s("claimed").build(),
                            ":seekerId", AttributeValue.builder().s(seekerId).build(),
                            ":submittedAt", AttributeValue.builder().s(now.toString()).build(),
                            ":updatedAt", AttributeValue.builder().s(now.toString()).build()
                    ))
                    .build();

            dynamoDbClient.updateItem(updateRequest);

            context.getLogger().log("Successfully submitted job " + jobId + " by seeker " + seekerId);

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
                    .status("submitted")
                    .createdAt(currentJob.createdAt())
                    .updatedAt(now.toString())
                    .claimerId(currentJob.claimerId())
                    .claimedAt(currentJob.claimedAt())
                    .submissionDeadline(currentJob.submissionDeadline())
                    .submittedAt(now.toString())
                    .build();

        } catch (ConditionalCheckFailedException e) {
            throw new JobNotClaimedException("Job is not claimed by you or has already been submitted");
        } catch (Exception e) {
            context.getLogger().log("Error in submit operation: " + e.getMessage());
            throw new RuntimeException("Error submitting job", e);
        }
    }

    private void validateJobForSubmission(JobEntity job, String seekerId, Instant now) 
            throws JobNotClaimedException, SubmissionDeadlineExceededException {
        
        if (!"claimed".equals(job.status())) {
            throw new JobNotClaimedException("Job is not in claimed status (current: " + job.status() + ")");
        }

        if (!seekerId.equals(job.claimerId())) {
            throw new JobNotClaimedException("Job is not claimed by you");
        }

        // Check if submission deadline has passed
        try {
            Instant deadline = Instant.parse(job.submissionDeadline());
            if (now.isAfter(deadline)) {
                // Job has timed out - reset it to open status
                resetJobToOpen(job.jobId());
                throw new SubmissionDeadlineExceededException("Submission deadline has passed. Job has been reset to open status.");
            }
        } catch (Exception e) {
            if (e instanceof SubmissionDeadlineExceededException) {
                throw e;
            }
            throw new JobNotClaimedException("Job has invalid submission deadline");
        }
    }

    private void resetJobToOpen(String jobId) {
        try {
            UpdateItemRequest resetRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .updateExpression("SET #status = :openStatus, #updatedAt = :updatedAt REMOVE #claimerId, #claimedAt, #submissionDeadline")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#updatedAt", "updatedAt",
                            "#claimerId", "claimerId",
                            "#claimedAt", "claimedAt",
                            "#submissionDeadline", "submissionDeadline"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":openStatus", AttributeValue.builder().s("open").build(),
                            ":updatedAt", AttributeValue.builder().s(Instant.now().toString()).build()
                    ))
                    .build();

            dynamoDbClient.updateItem(resetRequest);
        } catch (Exception e) {
            // Log but don't fail the operation
            System.err.println("Error resetting job to open status: " + e.getMessage());
        }
    }

    private void publishJobSubmittedEvent(JobEntity job, String claimerEmail, Context context) {
        try {
            JobSubmittedEvent event = new JobSubmittedEvent(
                    job.jobId(),
                    job.ownerId(),
                    job.claimerId(),
                    claimerEmail,  // From Cognito headers
                    job.categoryId(),
                    job.name(),
                    job.payAmount(),
                    job.submittedAt()
            );

            String eventJson = objectMapper.writeValueAsString(event);

            PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
                    .source("jobs-service")
                    .detailType("job.submitted")
                    .detail(eventJson)
                    .eventBusName(eventBusName)
                    .build();

            PutEventsRequest putEventsRequest = PutEventsRequest.builder()
                    .entries(eventEntry)
                    .build();

            var response = eventBridgeClient.putEvents(putEventsRequest);

            if (response.failedEntryCount() > 0) {
                context.getLogger().log("Failed to publish job.submitted event: " + response.entries());
            } else {
                context.getLogger().log("Successfully published job.submitted event for job: " + job.jobId());
            }

        } catch (Exception e) {
            context.getLogger().log("Error publishing job.submitted event: " + e.getMessage());
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