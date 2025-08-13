package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.model.CategoryInfo;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.model.CreateJobRequest;
import com.freelance.jobs.model.CreateJobResponse;
import com.freelance.jobs.model.JobCreatedEvent;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * Lambda handler for creating jobs Flow: Validate → Create Job → Publish Event → Return Response
 */
public class CreateJobHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final DynamoDbClient dynamoDbClient;
  private final EventBridgeClient eventBridgeClient;
  private final ObjectMapper objectMapper;
  private final String jobsTableName;
  private final String categoriesTableName;
  private final String eventBusName;

  public CreateJobHandler() {
    this.dynamoDbClient = DynamoDbClient.create();
    this.eventBridgeClient = EventBridgeClient.create();
    this.objectMapper = new ObjectMapper();
    this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    this.categoriesTableName = System.getenv("CATEGORIES_TABLE_NAME");
    this.eventBusName = System.getenv("EVENT_BUS_NAME");
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent input, Context context) {
    try {
      String ownerId = RequestMapper.extractOwnerIdFromContext(input);
      if (ownerId == null) {
        return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
      }

      // Handle base64 encoded request body
      String requestBody = input.getBody();
      if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded()) {
        requestBody = new String(Base64.getDecoder().decode(requestBody), StandardCharsets.UTF_8);
      }
      
      CreateJobRequest request = objectMapper.readValue(requestBody, CreateJobRequest.class);

      if (!request.isValid()) {
        return ResponseUtil.createErrorResponse(400, request.getValidationError());
      }

      context
          .getLogger()
          .log("Creating job for owner: " + ownerId + ", category: " + request.categoryId());

      CategoryInfo categoryInfo = getCategoryInfo(request.trimmedCategoryId());
      if (categoryInfo == null) {
        return ResponseUtil.createErrorResponse(404, "Category not found: " + request.categoryId());
      }

      if (!categoryInfo.hasValidSnsTopicArn()) {
        context
            .getLogger()
            .log("Warning: Category " + categoryInfo.categoryId() + " has no SNS topic ARN");
      }

      JobEntity jobEntity = createJobEntity(request, ownerId);

      saveJobToDatabase(jobEntity, context);
      
      String jobUrl = createJobUrl(input, jobEntity.jobId());
      publishJobCreatedEvent(jobEntity, categoryInfo, jobUrl, context);

      CreateJobResponse response =
          new CreateJobResponse(
              jobEntity.jobId(),
              jobEntity.ownerId(),
              jobEntity.categoryId(),
              jobEntity.name(),
              jobEntity.description(),
              jobEntity.payAmount(),
              jobEntity.timeToCompleteSeconds(),
              jobEntity.status(),
              jobEntity.expiryDate(),
              jobEntity.createdAt(),
              categoryInfo.snsTopicArn());

      return ResponseUtil.createSuccessResponse(201, response);

    } catch (Exception e) {
      context.getLogger().log("Error creating job: " + e.getMessage());
      e.printStackTrace();
      return ResponseUtil.createErrorResponse(500, "Internal server error");
    }
  }


  private String createJobUrl(APIGatewayProxyRequestEvent input, String jobId) {
    try {
      // Extract the base URL from API Gateway request context
      var requestContext = input.getRequestContext();
      if (requestContext != null && requestContext.getDomainName() != null) {
        String protocol = "https"; // API Gateway always uses HTTPS
        String domainName = requestContext.getDomainName();
        String stage = requestContext.getStage();
        
        // Construct the base URL: https://domain/stage
        String baseUrl = String.format("%s://%s/%s", protocol, domainName, stage);
        
        // Append the job-specific path
        return String.format("%s/job/owner/view/%s", baseUrl, jobId);
      }
      
      throw new RuntimeException("Request context or domain name not available for URL generation");
      
    } catch (Exception e) {
      throw new RuntimeException("Failed to create job URL: " + e.getMessage(), e);
    }
  }

  private CategoryInfo getCategoryInfo(String categoryId) {
    try {
      GetItemRequest getItemRequest =
          GetItemRequest.builder()
              .tableName(categoriesTableName)
              .key(Map.of("categoryId", AttributeValue.builder().s(categoryId).build()))
              .build();

      GetItemResponse response = dynamoDbClient.getItem(getItemRequest);

      if (!response.hasItem()) {
        return null;
      }

      Map<String, AttributeValue> item = response.item();

      String name = item.containsKey("name") ? item.get("name").s() : null;
      String snsTopicArn = item.containsKey("snsTopicArn") ? item.get("snsTopicArn").s() : null;

      return new CategoryInfo(categoryId, name, snsTopicArn);

    } catch (Exception e) {
      throw new RuntimeException("Error fetching category info for: " + categoryId, e);
    }
  }

  private JobEntity createJobEntity(CreateJobRequest request, String ownerId) {
    Instant now = Instant.now();
    Instant expiryInstant = now.plus(request.expirySeconds(), ChronoUnit.SECONDS);

    return JobEntity.builder()
        .jobId(UUID.randomUUID().toString())
        .ownerId(ownerId)
        .categoryId(request.trimmedCategoryId())
        .name(request.trimmedName())
        .description(request.trimmedDescription())
        .payAmount(request.payAmount())
        .timeToCompleteSeconds(request.timeToCompleteSeconds())
        .expiryDate(expiryInstant.toString())
        .status("open")
        .createdAt(now.toString())
        .updatedAt(now.toString())
        .build();
  }

  private void saveJobToDatabase(JobEntity jobEntity, Context context) {
    try {
      Map<String, AttributeValue> item = new HashMap<>();
      item.put("jobId", AttributeValue.builder().s(jobEntity.jobId()).build());
      item.put("ownerId", AttributeValue.builder().s(jobEntity.ownerId()).build());
      item.put("categoryId", AttributeValue.builder().s(jobEntity.categoryId()).build());
      item.put("name", AttributeValue.builder().s(jobEntity.name()).build());
      item.put("description", AttributeValue.builder().s(jobEntity.description()).build());
      item.put("payAmount", AttributeValue.builder().n(jobEntity.payAmount().toString()).build());
      item.put(
          "timeToCompleteSeconds",
          AttributeValue.builder().n(jobEntity.timeToCompleteSeconds().toString()).build());
      item.put("expiryDate", AttributeValue.builder().s(jobEntity.expiryDate()).build());
      item.put("status", AttributeValue.builder().s(jobEntity.status()).build());
      item.put("createdAt", AttributeValue.builder().s(jobEntity.createdAt()).build());
      item.put("updatedAt", AttributeValue.builder().s(jobEntity.updatedAt()).build());

      Instant expiryInstant = Instant.parse(jobEntity.expiryDate());
      item.put(
          "ttl",
          AttributeValue.builder().n(String.valueOf(expiryInstant.getEpochSecond())).build());

      PutItemRequest putItemRequest =
          PutItemRequest.builder()
              .tableName(jobsTableName)
              .item(item)
              .conditionExpression("attribute_not_exists(jobId)")
              .build();

      dynamoDbClient.putItem(putItemRequest);
      context.getLogger().log("Job saved to database: " + jobEntity.jobId());

    } catch (ConditionalCheckFailedException e) {
      throw new RuntimeException("Job with this ID already exists", e);
    } catch (Exception e) {
      throw new RuntimeException("Error saving job to database", e);
    }
  }

  private void publishJobCreatedEvent(
      JobEntity jobEntity, CategoryInfo categoryInfo, String jobUrl, Context context) {
    try {
      JobCreatedEvent event =
          JobCreatedEvent.create(
              jobEntity.jobId(),
              jobEntity.ownerId(),
              jobEntity.categoryId(),
              categoryInfo.name(),
              categoryInfo.snsTopicArn(),
              jobEntity.name(),
              jobEntity.description(),
              jobEntity.payAmount(),
              jobEntity.timeToCompleteSeconds(),
              jobEntity.status(),
              jobEntity.expiryDate(),
              jobEntity.createdAt(),
              jobUrl);

      String eventJson = objectMapper.writeValueAsString(event);

      PutEventsRequestEntry eventEntry =
          PutEventsRequestEntry.builder()
              .source("jobs-service")
              .detailType("job.created")
              .detail(eventJson)
              .eventBusName(eventBusName)
              .build();

      PutEventsRequest putEventsRequest = PutEventsRequest.builder().entries(eventEntry).build();

      var response = eventBridgeClient.putEvents(putEventsRequest);

      if (response.failedEntryCount() > 0) {
        context.getLogger().log("Failed to publish some events: " + response.entries());
      } else {
        context
            .getLogger()
            .log("Successfully published job.created event for job: " + jobEntity.jobId());
      }

    } catch (Exception e) {
      context.getLogger().log("Error publishing job.created event: " + e.getMessage());
    }
  }

}

