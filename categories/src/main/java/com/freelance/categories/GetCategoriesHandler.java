package com.freelance.categories;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.categories.model.CategoryResponse;
import com.freelance.categories.model.GetCategoriesResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class GetCategoriesHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final DynamoDbClient dynamoDbClient;
  private final ObjectMapper objectMapper;
  private final String categoriesTableName;

  public GetCategoriesHandler() {
    this.dynamoDbClient = DynamoDbClient.create();
    this.objectMapper = new ObjectMapper();
    this.categoriesTableName = System.getenv("CATEGORIES_TABLE_NAME");
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent input, Context context) {
    try {
      List<CategoryResponse> categories = getAllCategories();

      GetCategoriesResponse response = new GetCategoriesResponse(categories, categories.size());

      return createSuccessResponse(200, response);

    } catch (Exception e) {
      context.getLogger().log("Error retrieving categories: " + e.getMessage());
      return createErrorResponse(500, "Internal server error");
    }
  }

  private List<CategoryResponse> getAllCategories() {
    try {
      List<CategoryResponse> categories = new ArrayList<>();

      ScanRequest scanRequest = ScanRequest.builder().tableName(categoriesTableName).build();

      ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

      for (Map<String, AttributeValue> item : scanResponse.items()) {
        CategoryResponse category =
            new CategoryResponse(
                item.containsKey("categoryId") ? item.get("categoryId").s() : null,
                item.containsKey("name") ? item.get("name").s() : null,
                item.containsKey("description") ? item.get("description").s() : null,
                item.containsKey("snsTopicArn") ? item.get("snsTopicArn").s() : null,
                item.containsKey("createdAt") ? item.get("createdAt").s() : null);

        categories.add(category);
      }

      // Handle pagination if there are more items
      String lastEvaluatedKey = null;
      while (scanResponse.lastEvaluatedKey() != null
          && !scanResponse.lastEvaluatedKey().isEmpty()) {
        scanRequest =
            ScanRequest.builder()
                .tableName(categoriesTableName)
                .exclusiveStartKey(scanResponse.lastEvaluatedKey())
                .build();

        scanResponse = dynamoDbClient.scan(scanRequest);

        for (Map<String, AttributeValue> item : scanResponse.items()) {
          CategoryResponse category =
              new CategoryResponse(
                  item.containsKey("categoryId") ? item.get("categoryId").s() : null,
                  item.containsKey("name") ? item.get("name").s() : null,
                  item.containsKey("description") ? item.get("description").s() : null,
                  item.containsKey("snsTopicArn") ? item.get("snsTopicArn").s() : null,
                  item.containsKey("createdAt") ? item.get("createdAt").s() : null);

          categories.add(category);
        }
      }

      return categories;

    } catch (Exception e) {
      throw new RuntimeException("Error scanning categories from database", e);
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

