package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.exceptions.JobNotOwnedException;
import com.freelance.jobs.exceptions.JobNotRelistableException;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.model.JobCreatedEvent;
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
 * Handler for POST /job/owner/relist/{jobId}
 * Relists an expired job by resetting its status to open and optionally extending expiry
 */
public class RelistJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final EventBridgeClient eventBridgeClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;
    private final String eventBusName;

    public RelistJobHandler() {
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

            // Parse relist options from request body
            RelistOptions options = parseRelistOptions(input.getBody());

            context.getLogger().log("Relisting job " + jobId + " by owner " + ownerId);

            // Relist the job
            JobEntity relistedJob = relistJob(jobId, ownerId, options, context);
            
            // Publish job.created event (since it's available again)
            publishJobCreatedEvent(relistedJob, context);

            return ResponseUtil.createSuccessResponse(200, Map.of(
                    "message", "Job relisted successfully",
                    "jobId", jobId,
                    "status", "open",
                    "newExpiryDate", relistedJob.expiryDate(),
                    "relistedAt", relistedJob.updatedAt()
            ));

        } catch (JobNotRelistableException e) {
            return ResponseUtil.createErrorResponse(400, e.getMessage());
        } catch (JobNotOwnedException e) {
            return ResponseUtil.createErrorResponse(403, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error relisting job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity relistJob(String jobId, String ownerId, RelistOptions options, Context context) 
            throws JobNotRelistableException, JobNotOwnedException {
        try {
            Instant now = Instant.now();
            
            // Get current job to validate
            JobEntity currentJob = getJobById(jobId);
            if (currentJob == null) {
                throw new JobNotRelistableException("Job not found");
            }

            // Validate job can be relisted by this owner
            validateJobForRelist(currentJob, ownerId);

            // Calculate new expiry date
            Instant newExpiryDate;
            if (options.expiryDays() > 0) {
                newExpiryDate = now.plus(options.expiryDays(), ChronoUnit.DAYS);
            } else {
                // Default to 7 days from now
                newExpiryDate = now.plus(7, ChronoUnit.DAYS);
            }

            // Update TTL for DynamoDB
            Instant ttlInstant = newExpiryDate.plus(1, ChronoUnit.DAYS); // TTL 1 day after expiry

            // Atomic update to relist job
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .updateExpression("SET #status = :openStatus, #expiryDate = :newExpiryDate, #updatedAt = :updatedAt, #ttl = :ttl REMOVE #claimerId, #claimedAt, #submissionDeadline, #submittedAt")
                    .conditionExpression("#ownerId = :ownerId")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#expiryDate", "expiryDate",
                            "#ownerId", "ownerId",
                            "#updatedAt", "updatedAt",
                            "#ttl", "ttl",
                            "#claimerId", "claimerId",
                            "#claimedAt", "claimedAt",
                            "#submissionDeadline", "submissionDeadline",
                            "#submittedAt", "submittedAt"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":openStatus", AttributeValue.builder().s("open").build(),
                            ":newExpiryDate", AttributeValue.builder().s(newExpiryDate.toString()).build(),
                            ":ownerId", AttributeValue.builder().s(ownerId).build(),
                            ":updatedAt", AttributeValue.builder().s(now.toString()).build(),
                            ":ttl", AttributeValue.builder().n(String.valueOf(ttlInstant.getEpochSecond())).build()
                    ))
                    .build();

            dynamoDbClient.updateItem(updateRequest);

            context.getLogger().log("Successfully relisted job " + jobId + " by owner " + ownerId);

            // Return updated job entity
            return JobEntity.builder()
                    .jobId(currentJob.jobId())
                    .ownerId(currentJob.ownerId())
                    .categoryId(currentJob.categoryId())
                    .name(currentJob.name())
                    .description(currentJob.description())
                    .payAmount(currentJob.payAmount())
                    .timeToCompleteSeconds(currentJob.timeToCompleteSeconds())
                    .expiryDate(newExpiryDate.toString())
                    .status("open")
                    .createdAt(currentJob.createdAt())
                    .updatedAt(now.toString())
                    .claimerId(null)
                    .claimedAt(null)
                    .submissionDeadline(null)
                    .submittedAt(null)
                    .build();

        } catch (ConditionalCheckFailedException e) {
            throw new JobNotOwnedException("Job does not belong to you");
        } catch (Exception e) {
            context.getLogger().log("Error in relist operation: " + e.getMessage());
            throw new RuntimeException("Error relisting job", e);
        }
    }

    private void validateJobForRelist(JobEntity job, String ownerId) 
            throws JobNotRelistableException, JobNotOwnedException {
        
        if (!ownerId.equals(job.ownerId())) {
            throw new JobNotOwnedException("Job does not belong to you");
        }

        // Job can be relisted if it's expired (open but past expiry) or completed
        boolean isExpired = false;
        try {
            Instant expiryDate = Instant.parse(job.expiryDate());
            isExpired = expiryDate.isBefore(Instant.now());
        } catch (Exception e) {
            isExpired = true; // Treat invalid dates as expired
        }

        boolean canRelist = ("open".equals(job.status()) && isExpired) ||
                           "approved_as_completed".equals(job.status()) ||
                           ("claimed".equals(job.status()) && isExpired) ||
                           ("submitted".equals(job.status()) && isExpired);

        if (!canRelist) {
            throw new JobNotRelistableException("Job cannot be relisted. Current status: " + job.status() + 
                                               (isExpired ? " (expired)" : " (not expired)"));
        }
    }

    private void publishJobCreatedEvent(JobEntity job, Context context) {
        try {
            // Get category info for SNS topic
            String categoryName = "Unknown"; // Could fetch from categories table if needed
            String snsTopicArn = ""; // Could fetch category SNS topic if needed

            JobCreatedEvent event = JobCreatedEvent.create(
                    job.jobId(),
                    job.ownerId(),
                    job.categoryId(),
                    categoryName,
                    snsTopicArn,
                    job.name(),
                    job.description(),
                    job.payAmount(),
                    job.timeToCompleteSeconds(),
                    job.status(),
                    job.expiryDate(),
                    job.updatedAt(), // Use updated time as "relisted at"
                    "" // No job URL for now
            );

            String eventJson = objectMapper.writeValueAsString(event);

            PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
                    .source("jobs-service")
                    .detailType("job.created")
                    .detail(eventJson)
                    .eventBusName(eventBusName)
                    .build();

            PutEventsRequest putEventsRequest = PutEventsRequest.builder()
                    .entries(eventEntry)
                    .build();

            var response = eventBridgeClient.putEvents(putEventsRequest);

            if (response.failedEntryCount() > 0) {
                context.getLogger().log("Failed to publish job.created event for relisted job: " + response.entries());
            } else {
                context.getLogger().log("Successfully published job.created event for relisted job: " + job.jobId());
            }

        } catch (Exception e) {
            context.getLogger().log("Error publishing job.created event for relisted job: " + e.getMessage());
        }
    }

    private RelistOptions parseRelistOptions(String requestBody) {
        try {
            if (requestBody == null || requestBody.trim().isEmpty()) {
                return new RelistOptions(7); // Default 7 days
            }

            Map<String, Object> body = objectMapper.readValue(requestBody, Map.class);
            Object expiryDaysObj = body.get("expiryDays");
            
            int expiryDays = 7; // Default
            if (expiryDaysObj instanceof Number) {
                expiryDays = ((Number) expiryDaysObj).intValue();
                if (expiryDays < 1 || expiryDays > 90) {
                    expiryDays = 7; // Reset to default if invalid
                }
            }
            
            return new RelistOptions(expiryDays);
        } catch (Exception e) {
            return new RelistOptions(7); // Default on parse error
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

    // Helper records
    private record RelistOptions(int expiryDays) {}
}