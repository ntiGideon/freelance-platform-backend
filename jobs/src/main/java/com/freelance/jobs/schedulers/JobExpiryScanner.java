package com.freelance.jobs.schedulers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.events.JobExpiredEvent;
import com.freelance.jobs.mappers.JobEntityMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Scheduled Lambda function that scans for expired jobs and queues them for processing Triggered by
 * EventBridge every 5 minutes Architecture: EventBridge Schedule -> JobExpiryScanner -> SQS ->
 * JobExpiryProcessor
 */
public class JobExpiryScanner implements RequestHandler<ScheduledEvent, String> {

  private final DynamoDbClient dynamoDbClient;
  private final SqsClient sqsClient;
  private final ObjectMapper objectMapper;
  private final String jobsTableName;
  private final String expiryQueueUrl;

  public JobExpiryScanner() {
    this.dynamoDbClient = DynamoDbClient.create();
    this.sqsClient = SqsClient.create();
    this.objectMapper = new ObjectMapper();
    this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    this.expiryQueueUrl = System.getenv("JOB_EXPIRY_QUEUE_URL");
  }

  @Override
  public String handleRequest(ScheduledEvent input, Context context) {
    context.getLogger().log("Starting job expiry scan...");

    try {
      List<JobEntity> expiredJobs = findExpiredJobs(context);
      context.getLogger().log("Found " + expiredJobs.size() + " expired jobs");

      int processed = 0;
      int failed = 0;

      for (JobEntity job : expiredJobs) {
        try {
          markJobAsExpired(job, context);

          // Send to SQS for notification processing
          queueJobForExpiration(job, context);

          processed++;
        } catch (Exception e) {
          context
              .getLogger()
              .log("Error processing expired job " + job.jobId() + ": " + e.getMessage());
          failed++;
        }
      }

      String result =
          String.format("Job expiry scan completed. Processed: %d, Failed: %d", processed, failed);
      context.getLogger().log(result);
      return result;

    } catch (Exception e) {
      context.getLogger().log("Error in job expiry scan: " + e.getMessage());
      e.printStackTrace();
      throw new RuntimeException("Job expiry scan failed", e);
    }
  }

  private List<JobEntity> findExpiredJobs(Context context) {
    try {
      Instant now = Instant.now();
      String nowIso = now.toString();

      // Query jobs that are not already expired and have expiryDate < now
      // We'll use a scan with filter since we need to check expiry across all statuses
      ScanRequest scanRequest =
          ScanRequest.builder()
              .tableName(jobsTableName)
              .filterExpression("#status <> :expiredStatus AND #expiryDate < :now")
              .expressionAttributeNames(
                  Map.of(
                      "#status", "status",
                      "#expiryDate", "expiryDate"))
              .expressionAttributeValues(
                  Map.of(
                      ":expiredStatus", AttributeValue.builder().s("expired").build(),
                      ":now", AttributeValue.builder().s(nowIso).build()))
              .build();

      ScanResponse response = dynamoDbClient.scan(scanRequest);
      List<JobEntity> expiredJobs = new ArrayList<>();

      for (Map<String, AttributeValue> item : response.items()) {
        try {
          JobEntity job = JobEntityMapper.mapToJobEntity(item);
          // Double-check expiry in application layer for safety
          Instant expiryDate = Instant.parse(job.expiryDate());
          if (expiryDate.isBefore(now)) {
            expiredJobs.add(job);
          }
        } catch (Exception e) {
          context.getLogger().log("Error parsing job item: " + e.getMessage());
        }
      }

      return expiredJobs;

    } catch (Exception e) {
      context.getLogger().log("Error finding expired jobs: " + e.getMessage());
      throw new RuntimeException("Error scanning for expired jobs", e);
    }
  }

  private void markJobAsExpired(JobEntity job, Context context) {
    try {
      String now = Instant.now().toString();

      UpdateItemRequest updateRequest =
          UpdateItemRequest.builder()
              .tableName(jobsTableName)
              .key(Map.of("jobId", AttributeValue.builder().s(job.jobId()).build()))
              .updateExpression(
                  "SET #status = :expiredStatus, #updatedAt = :updatedAt, #expiredAt = :expiredAt")
              .expressionAttributeNames(
                  Map.of(
                      "#status", "status",
                      "#updatedAt", "updatedAt",
                      "#expiredAt", "expiredAt"))
              .expressionAttributeValues(
                  Map.of(
                      ":expiredStatus", AttributeValue.builder().s("expired").build(),
                      ":updatedAt", AttributeValue.builder().s(now).build(),
                      ":expiredAt", AttributeValue.builder().s(now).build()))
              .conditionExpression(
                  "#status <> :expiredStatus") // Only update if not already expired
              .build();

      dynamoDbClient.updateItem(updateRequest);
      context.getLogger().log("Marked job " + job.jobId() + " as expired");

    } catch (ConditionalCheckFailedException e) {
      // Job was already marked as expired by another process, ignore
      context.getLogger().log("Job " + job.jobId() + " already expired");
    } catch (Exception e) {
      context.getLogger().log("Error marking job as expired: " + e.getMessage());
      throw new RuntimeException("Error updating job status to expired", e);
    }
  }

  private void queueJobForExpiration(JobEntity job, Context context) {
    try {
      String now = Instant.now().toString();

      JobExpiredEvent expiredEvent =
          JobExpiredEvent.create(
              job.jobId(),
              job.ownerId(),
              job.name(),
              job.description(),
              job.payAmount(),
              job.expiryDate(),
              now,
              job.categoryId(),
              job.status());

      String messageBody = objectMapper.writeValueAsString(expiredEvent);

      SendMessageRequest sendMessageRequest =
          SendMessageRequest.builder()
              .queueUrl(expiryQueueUrl)
              .messageBody(messageBody)
              .messageGroupId("job-expiry") // For FIFO queue
              .messageDeduplicationId(job.jobId() + "-" + now) // Prevent duplicates
              .build();

      sqsClient.sendMessage(sendMessageRequest);
      context.getLogger().log("Queued job " + job.jobId() + " for expiry processing");

    } catch (Exception e) {
      context.getLogger().log("Error queuing job for expiration: " + e.getMessage());
      throw new RuntimeException("Error sending job to expiry queue", e);
    }
  }

}

