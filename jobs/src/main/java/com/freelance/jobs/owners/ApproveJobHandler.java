package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.events.JobApprovedEvent;
import com.freelance.jobs.exceptions.JobNotOwnedException;
import com.freelance.jobs.exceptions.JobNotSubmittedException;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.model.ApproveJobRequest;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * Handler for POST /job/owner/approve/{jobId} Approves a submitted job and triggers payment
 * workflow
 */
public class ApproveJobHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final DynamoDbClient dynamoDbClient;
  private final EventBridgeClient eventBridgeClient;
  private final ObjectMapper objectMapper;
  private final String jobsTableName;
  private final String eventBusName;

  public ApproveJobHandler() {
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
      String approvalMessage = null;
      if (input.getBody() != null && !input.getBody().trim().isEmpty()) {
          try {
              ApproveJobRequest approveRequest = objectMapper.readValue(input.getBody(), ApproveJobRequest.class);
              if (!approveRequest.isValid()) {
                  return ResponseUtil.createErrorResponse(400, "Invalid request: message too long (max 1000 characters)");
              }
              approvalMessage = approveRequest.trimmedMessage();
          } catch (Exception e) {
              return ResponseUtil.createErrorResponse(400, "Invalid JSON in request body");
          }
      }

      context.getLogger().log("Approving job " + jobId + " by owner " + ownerId + 
          (approvalMessage != null ? " with message" : " without message"));

      // Approve the job
      JobEntity approvedJob = approveJob(jobId, ownerId, approvalMessage, context);

      // Publish job.approved event
      publishJobApprovedEvent(approvedJob, context);

      return ResponseUtil.createSuccessResponse(
          200,
          Map.of(
              "message",
              "Job approved successfully",
              "jobId",
              jobId,
              "status",
              "approved_as_completed",
              "approvedAt",
              approvedJob.updatedAt(),
              "paymentProcessing",
              true));

    } catch (JobNotSubmittedException e) {
      return ResponseUtil.createErrorResponse(400, e.getMessage());
    } catch (JobNotOwnedException e) {
      return ResponseUtil.createErrorResponse(403, e.getMessage());
    } catch (Exception e) {
      context.getLogger().log("Error approving job: " + e.getMessage());
      e.printStackTrace();
      return ResponseUtil.createErrorResponse(500, "Internal server error");
    }
  }

  private JobEntity approveJob(String jobId, String ownerId, String approvalMessage, Context context)
      throws JobNotSubmittedException, JobNotOwnedException {
    try {
      Instant now = Instant.now();

      // Get current job to validate
      JobEntity currentJob = getJobById(jobId);
      if (currentJob == null) {
        throw new JobNotSubmittedException("Job not found");
      }

      // Validate job can be approved by this owner
      validateJobForApproval(currentJob, ownerId);

      // Atomic update to set status to "approved_as_completed" with optional message
      String updateExpression = approvalMessage != null ?
          "SET #status = :approvedStatus, #updatedAt = :updatedAt, #approvalMessage = :approvalMessage" :
          "SET #status = :approvedStatus, #updatedAt = :updatedAt";
      
      Map<String, String> expressionAttributeNames = Map.of(
          "#status", "status",
          "#ownerId", "ownerId",
          "#updatedAt", "updatedAt",
          "#approvalMessage", "approvalMessage"
      );
      
      Map<String, AttributeValue> expressionAttributeValues = Map.of(
          ":approvedStatus", AttributeValue.builder().s("approved_as_completed").build(),
          ":submittedStatus", AttributeValue.builder().s("submitted").build(),
          ":ownerId", AttributeValue.builder().s(ownerId).build(),
          ":updatedAt", AttributeValue.builder().s(now.toString()).build(),
          ":approvalMessage", AttributeValue.builder().s(approvalMessage != null ? approvalMessage : "").build()
      );
      
      UpdateItemRequest updateRequest =
          UpdateItemRequest.builder()
              .tableName(jobsTableName)
              .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
              .updateExpression(updateExpression)
              .conditionExpression("#status = :submittedStatus AND #ownerId = :ownerId")
              .expressionAttributeNames(expressionAttributeNames)
              .expressionAttributeValues(expressionAttributeValues)
              .build();

      dynamoDbClient.updateItem(updateRequest);

      context.getLogger().log("Successfully approved job " + jobId + " by owner " + ownerId);

      return JobEntity.builder()
          .jobId(currentJob.jobId())
          .ownerId(currentJob.ownerId())
          .categoryId(currentJob.categoryId())
          .name(currentJob.name())
          .description(currentJob.description())
          .payAmount(currentJob.payAmount())
          .timeToCompleteSeconds(currentJob.timeToCompleteSeconds())
          .expiryDate(currentJob.expiryDate())
          .status("approved_as_completed")
          .createdAt(currentJob.createdAt())
          .updatedAt(now.toString())
          .claimerId(currentJob.claimerId())
          .claimedAt(currentJob.claimedAt())
          .submissionDeadline(currentJob.submissionDeadline())
          .submittedAt(currentJob.submittedAt())
          .submissionMessage(currentJob.submissionMessage())
          .approvalMessage(approvalMessage)
          .rejectionMessage(currentJob.rejectionMessage())
          .build();

    } catch (ConditionalCheckFailedException e) {
      throw new JobNotSubmittedException(
          "Job is not in submitted status or does not belong to you");
    } catch (Exception e) {
      context.getLogger().log("Error in approve operation: " + e.getMessage());
      throw new RuntimeException("Error approving job", e);
    }
  }

  private void validateJobForApproval(JobEntity job, String ownerId)
      throws JobNotSubmittedException, JobNotOwnedException {

    if (!ownerId.equals(job.ownerId())) {
      throw new JobNotOwnedException("Job does not belong to you");
    }

    if (!"submitted".equals(job.status())) {
      throw new JobNotSubmittedException(
          "Job is not in submitted status (current: " + job.status() + ")");
    }

    if (job.claimerId() == null || job.claimerId().isEmpty()) {
      throw new JobNotSubmittedException("Job has no claimer");
    }
  }

  private void publishJobApprovedEvent(JobEntity job, Context context) {
    try {
      JobApprovedEvent event =
          new JobApprovedEvent(
              job.jobId(),
              job.ownerId(),
              job.claimerId(),
              null,  // claimerEmail not available when owner approves, will fallback to user table lookup
              job.categoryId(),
              job.name(),
              job.payAmount(),
              job.updatedAt());

      String eventJson = objectMapper.writeValueAsString(event);

      PutEventsRequestEntry eventEntry =
          PutEventsRequestEntry.builder()
              .source("jobs-service")
              .detailType("job.approved")
              .detail(eventJson)
              .eventBusName(eventBusName)
              .build();

      PutEventsRequest putEventsRequest = PutEventsRequest.builder().entries(eventEntry).build();

      var response = eventBridgeClient.putEvents(putEventsRequest);

      if (response.failedEntryCount() > 0) {
        context.getLogger().log("Failed to publish job.approved event: " + response.entries());
      } else {
        context
            .getLogger()
            .log("Successfully published job.approved event for job: " + job.jobId());
      }

    } catch (Exception e) {
      context.getLogger().log("Error publishing job.approved event: " + e.getMessage());
    }
  }

  private JobEntity getJobById(String jobId) {
    GetItemRequest getItemRequest =
        GetItemRequest.builder()
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

