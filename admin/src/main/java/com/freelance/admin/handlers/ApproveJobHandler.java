package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.admin.auth.AdminAuthUtils;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.events.JobApprovedEvent;
import com.freelance.admin.exceptions.JobNotSubmittedException;
import com.freelance.admin.mappers.JobEntityMapper;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.time.Instant;
import java.util.Map;

public class ApproveJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

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
      String userId = RequestMapper.extractUserIdFromContext(input, "user");

      if (jobId == null) {
        return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
      }

      if (userId == null) {
        return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
      }

      context.getLogger().log("Processing job approval request for job: " + jobId + " by user: " + userId);

      // Check if user is admin
      boolean isAdmin = AdminAuthUtils.isAdminUser(userId);

      // Approve the job
      JobEntity approvedJob = approveJob(jobId, userId, isAdmin, context);

      // Publish job.approved event
      publishJobApprovedEvent(approvedJob, context);

      return ResponseUtil.createSuccessResponse(
              200,
              Map.of(
                      "message", "Job approved successfully",
                      "jobId", jobId,
                      "status", "approved_as_completed",
                      "approvedAt", approvedJob.updatedAt(),
                      "paymentProcessing", true,
                      "approvedByAdmin", isAdmin));

    } catch (JobNotSubmittedException e) {
      return ResponseUtil.createErrorResponse(400, e.getMessage());
    } catch (Exception e) {
      context.getLogger().log("Error approving job: " + e.getMessage());
      e.printStackTrace();
      return ResponseUtil.createErrorResponse(500, "Internal server error");
    }
  }

  private JobEntity approveJob(String jobId, String userId, boolean isAdmin, Context context)
          throws JobNotSubmittedException {
    try {
      Instant now = Instant.now();

      // Get current job to validate
      JobEntity currentJob = getJobById(jobId);
      if (currentJob == null) {
        throw new JobNotSubmittedException("Job not found");
      }

      validateJobForApproval(currentJob, userId, isAdmin);

      UpdateItemRequest.Builder updateRequestBuilder = UpdateItemRequest.builder()
              .tableName(jobsTableName)
              .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
              .updateExpression("SET #status = :approvedStatus, #updatedAt = :updatedAt")
              .expressionAttributeNames(Map.of(
                      "#status", "status",
                      "#updatedAt", "updatedAt"))
              .expressionAttributeValues(Map.of(
                      ":approvedStatus", AttributeValue.builder().s("approved_as_completed").build(),
                      ":updatedAt", AttributeValue.builder().s(now.toString()).build()));

      if (isAdmin) {
        updateRequestBuilder.conditionExpression("attribute_exists(jobId)");
      } else {
        updateRequestBuilder
                .conditionExpression("#status = :submittedStatus AND #ownerId = :ownerId")
                .expressionAttributeNames(Map.of("#ownerId", "ownerId"))
                .expressionAttributeValues(Map.of(
                        ":submittedStatus", AttributeValue.builder().s("submitted").build(),
                        ":ownerId", AttributeValue.builder().s(userId).build()));
      }

      dynamoDbClient.updateItem(updateRequestBuilder.build());

      context.getLogger().log("Successfully approved job " + jobId + " by " + (isAdmin ? "admin" : "owner"));

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
              .build();

    } catch (ConditionalCheckFailedException e) {
      throw new JobNotSubmittedException(
              isAdmin ? "Job not found" : "Job is not in submitted status or does not belong to you");
    } catch (Exception e) {
      context.getLogger().log("Error in approve operation: " + e.getMessage());
      throw new RuntimeException("Error approving job", e);
    }
  }

  private void validateJobForApproval(JobEntity job, String userId, boolean isAdmin)
          throws JobNotSubmittedException {

    // Admins can approve any job regardless of status or ownership
    if (isAdmin) {
      return;
    }

    // For non-admin users, check ownership
    if (!userId.equals(job.ownerId())) {
      throw new JobNotSubmittedException("Job does not belong to you");
    }

    // Check job status
    if (!"submitted".equals(job.status())) {
      throw new JobNotSubmittedException(
              "Job is not in submitted status (current: " + job.status() + ")");
    }

    // Check claimer exists
    if (job.claimerId() == null || job.claimerId().isEmpty()) {
      throw new JobNotSubmittedException("Job has no claimer");
    }
  }

  private void publishJobApprovedEvent(JobEntity job, Context context) {
    try {
      JobApprovedEvent event = new JobApprovedEvent(
              job.jobId(),
              job.ownerId(),
              job.claimerId(),
              job.categoryId(),
              job.name(),
              job.payAmount(),
              job.updatedAt());

      String eventJson = objectMapper.writeValueAsString(event);

      PutEventsRequestEntry eventEntry = PutEventsRequestEntry.builder()
              .source("jobs-service")
              .detailType("job.approved")
              .detail(eventJson)
              .eventBusName(eventBusName)
              .build();

      PutEventsRequest putEventsRequest = PutEventsRequest.builder()
              .entries(eventEntry)
              .build();

      var response = eventBridgeClient.putEvents(putEventsRequest);

      if (response.failedEntryCount() > 0) {
        context.getLogger().log("Failed to publish job.approved event: " + response.entries());
      } else {
        context.getLogger().log("Successfully published job.approved event for job: " + job.jobId());
      }

    } catch (Exception e) {
      context.getLogger().log("Error publishing job.approved event: " + e.getMessage());
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