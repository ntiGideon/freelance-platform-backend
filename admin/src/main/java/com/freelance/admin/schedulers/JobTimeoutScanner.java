package com.freelance.admin.schedulers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.events.JobTimedOutEvent;
import com.freelance.admin.mappers.JobEntityMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scheduled Lambda function that scans for claimed jobs that have exceeded their time_to_complete
 * Reverts them to "open" status and queues timeout notifications
 * Triggered by EventBridge every 2 minutes for more responsive timeout handling
 */
public class JobTimeoutScanner implements RequestHandler<ScheduledEvent, String> {

    private final DynamoDbClient dynamoDbClient;
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;
    private final String timeoutQueueUrl;

    public JobTimeoutScanner() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.sqsClient = SqsClient.create();
        this.objectMapper = new ObjectMapper();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
        this.timeoutQueueUrl = System.getenv("JOB_TIMEOUT_QUEUE_URL");
    }

    @Override
    public String handleRequest(ScheduledEvent input, Context context) {
        context.getLogger().log("Starting job timeout scan...");
        
        try {
            List<JobEntity> timedOutJobs = findTimedOutJobs(context);
            context.getLogger().log("Found " + timedOutJobs.size() + " timed out jobs");
            
            int processed = 0;
            int failed = 0;
            
            for (JobEntity job : timedOutJobs) {
                try {
                    // Revert job to open status and clear claimer data
                    revertJobToOpen(job, context);
                    
                    // Send to SQS for timeout notification processing
                    queueJobForTimeout(job, context);
                    
                    processed++;
                } catch (Exception e) {
                    context.getLogger().log("Error processing timed out job " + job.jobId() + ": " + e.getMessage());
                    failed++;
                }
            }
            
            String result = String.format("Job timeout scan completed. Processed: %d, Failed: %d", processed, failed);
            context.getLogger().log(result);
            return result;
            
        } catch (Exception e) {
            context.getLogger().log("Error in job timeout scan: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Job timeout scan failed", e);
        }
    }

    private List<JobEntity> findTimedOutJobs(Context context) {
        try {
            Instant now = Instant.now();
            
            // Query jobs with status "claimed" that have exceeded their time_to_complete
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(jobsTableName)
                    .filterExpression("#status = :claimedStatus AND attribute_exists(#claimedAt) AND attribute_exists(#submissionDeadline)")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#claimedAt", "claimedAt",
                            "#submissionDeadline", "submissionDeadline"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":claimedStatus", AttributeValue.builder().s("claimed").build()
                    ))
                    .build();

            ScanResponse response = dynamoDbClient.scan(scanRequest);
            List<JobEntity> timedOutJobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                try {
                    JobEntity job = JobEntityMapper.mapToJobEntity(item);
                    
                    // Check if submission deadline has passed
                    if (job.submissionDeadline() != null) {
                        Instant submissionDeadline = Instant.parse(job.submissionDeadline());
                        if (submissionDeadline.isBefore(now)) {
                            timedOutJobs.add(job);
                            context.getLogger().log("Job " + job.jobId() + " timed out. Deadline: " + 
                                job.submissionDeadline() + ", Now: " + now);
                        }
                    }
                } catch (Exception e) {
                    context.getLogger().log("Error parsing job item for timeout: " + e.getMessage());
                }
            }

            return timedOutJobs;
            
        } catch (Exception e) {
            context.getLogger().log("Error finding timed out jobs: " + e.getMessage());
            throw new RuntimeException("Error scanning for timed out jobs", e);
        }
    }

    private void revertJobToOpen(JobEntity job, Context context) {
        try {
            String now = Instant.now().toString();
            
            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(job.jobId()).build()))
                    .updateExpression("SET #status = :openStatus, #updatedAt = :updatedAt, #timedOutAt = :timedOutAt REMOVE #claimerId, #claimedAt, #submissionDeadline")
                    .expressionAttributeNames(Map.of(
                            "#status", "status",
                            "#updatedAt", "updatedAt",
                            "#timedOutAt", "timedOutAt",
                            "#claimerId", "claimerId",
                            "#claimedAt", "claimedAt",
                            "#submissionDeadline", "submissionDeadline"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":openStatus", AttributeValue.builder().s("open").build(),
                            ":updatedAt", AttributeValue.builder().s(now).build(),
                            ":timedOutAt", AttributeValue.builder().s(now).build(),
                            ":claimedStatus", AttributeValue.builder().s("claimed").build()
                    ))
                    .conditionExpression("#status = :claimedStatus") // Only revert if still claimed
                    .build();

            dynamoDbClient.updateItem(updateRequest);
            context.getLogger().log("Reverted job " + job.jobId() + " to open status due to timeout");
            
        } catch (ConditionalCheckFailedException e) {
            // Job was already updated by another process (e.g., submitted), ignore
            context.getLogger().log("Job " + job.jobId() + " status changed before timeout processing");
        } catch (Exception e) {
            context.getLogger().log("Error reverting job to open: " + e.getMessage());
            throw new RuntimeException("Error reverting job status", e);
        }
    }

    private void queueJobForTimeout(JobEntity job, Context context) {
        try {
            String now = Instant.now().toString();
            
            JobTimedOutEvent timedOutEvent = JobTimedOutEvent.create(
                    job.jobId(),
                    job.ownerId(),
                    job.claimerId(),
                    job.name(),
                    job.description(),
                    job.payAmount(),
                    job.claimedAt(),
                    job.submissionDeadline(),
                    now,
                    job.timeToCompleteSeconds(),
                    job.categoryId()
            );

            String messageBody = objectMapper.writeValueAsString(timedOutEvent);

            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(timeoutQueueUrl)
                    .messageBody(messageBody)
                    .messageGroupId("job-timeout") // For FIFO queue
                    .messageDeduplicationId(job.jobId() + "-timeout-" + now) // Prevent duplicates
                    .build();

            sqsClient.sendMessage(sendMessageRequest);
            context.getLogger().log("Queued job " + job.jobId() + " for timeout processing");
            
        } catch (Exception e) {
            context.getLogger().log("Error queuing job for timeout: " + e.getMessage());
            throw new RuntimeException("Error sending job to timeout queue", e);
        }
    }

}