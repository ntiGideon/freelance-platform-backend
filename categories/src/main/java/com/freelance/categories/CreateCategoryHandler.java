package com.freelance.categories;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.categories.entity.CategoryEntity;
import com.freelance.categories.model.CreateCategoryRequest;
import com.freelance.categories.model.CreateCategoryResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class CreateCategoryHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final DynamoDbClient dynamoDbClient;
  private final ObjectMapper objectMapper;
  private final String categoriesTableName;

  public CreateCategoryHandler() {
    this.dynamoDbClient = DynamoDbClient.create();
    this.objectMapper = new ObjectMapper();
    this.categoriesTableName = System.getenv("CATEGORIES_TABLE_NAME");
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent input, Context context) {
    try {
      // Handle base64 encoded request body
      String requestBody = input.getBody();
      if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded()) {
        requestBody = new String(Base64.getDecoder().decode(requestBody), StandardCharsets.UTF_8);
      }
      
      CreateCategoryRequest request =
          objectMapper.readValue(requestBody, CreateCategoryRequest.class);

      if (!request.isValid()) {
        return createErrorResponse(400, "Category name is required");
      }

      // Generate UUID for categoryId instead of deriving from name
      String categoryId = UUID.randomUUID().toString();

      // TODO: Add duplicate name checking later when NameIndex is stable

      // Create category without SNS topic ARN (will be created by stream processor)
      CategoryEntity categoryEntity =
          CategoryEntity.builder()
              .categoryId(categoryId)
              .name(request.trimmedName())
              .description(request.trimmedDescription())
              .snsTopicArn(null) // Will be populated by stream processor
              .createdAt(Instant.now().toString())
              .build();

      saveCategoryToDatabase(categoryEntity);

      // Note: SNS topic ARN will be null initially, populated by stream processor
      CreateCategoryResponse response =
          new CreateCategoryResponse(
              categoryEntity.categoryId(),
              categoryEntity.name(),
              categoryEntity.description(),
              "SNS topic will be created automatically");

      return createSuccessResponse(201, response);

    } catch (Exception e) {
      context.getLogger().log("Error creating category: " + e.getMessage());
      return createErrorResponse(500, "Internal server error");
    }
  }

  private boolean categoryNameExists(String name) {
    try {
      QueryRequest queryByNameRequest =
          QueryRequest.builder()
              .tableName(categoriesTableName)
              .indexName("NameIndex")
              .keyConditionExpression("name = :name")
              .expressionAttributeValues(
                  Map.of(":name", AttributeValue.builder().s(name.trim()).build()))
              .build();

      QueryResponse queryByNameResponse = dynamoDbClient.query(queryByNameRequest);
      return !queryByNameResponse.items().isEmpty();

    } catch (Exception e) {
      throw new RuntimeException("Error checking category name existence", e);
    }
  }


  private void saveCategoryToDatabase(CategoryEntity categoryEntity) {
    try {
      Map<String, AttributeValue> item = new HashMap<>();
      item.put("categoryId", AttributeValue.builder().s(categoryEntity.categoryId()).build());
      item.put("name", AttributeValue.builder().s(categoryEntity.name()).build());

      if (categoryEntity.description() != null) {
        item.put("description", AttributeValue.builder().s(categoryEntity.description()).build());
      }

      // Don't include snsTopicArn initially - it will be added by stream processor
      item.put("createdAt", AttributeValue.builder().s(categoryEntity.createdAt()).build());

      PutItemRequest putItemRequest =
          PutItemRequest.builder()
              .tableName(categoriesTableName)
              .item(item)
              .conditionExpression("attribute_not_exists(categoryId)")
              .build();

      dynamoDbClient.putItem(putItemRequest);

    } catch (ConditionalCheckFailedException e) {
      throw new RuntimeException("Category already exists", e);
    } catch (Exception e) {
      throw new RuntimeException("Error saving category to database", e);
    }
  }

  private APIGatewayProxyResponseEvent createSuccessResponse(int statusCode, Object body) {
    try {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(
              Map.of(
                  "Content-Type", "application/json",
                  "Access-Control-Allow-Origin", "*"))
          .withBody(objectMapper.writeValueAsString(body));
    } catch (Exception e) {
      throw new RuntimeException("Error creating success response", e);
    }
  }

  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    try {
      Map<String, String> errorBody = Map.of("error", message);
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(
              Map.of(
                  "Content-Type", "application/json",
                  "Access-Control-Allow-Origin", "*"))
          .withBody(objectMapper.writeValueAsString(errorBody));
    } catch (Exception e) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody("{\"error\":\"Internal server error\"}");
    }
  }
}

