package com.freelance.categories;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.CreateTopicResponse;

/**
 * Processes DynamoDB Stream events for Categories table Creates SNS topics when new categories are
 * added (from any source)
 */
public class CategoryStreamProcessor implements RequestHandler<DynamodbEvent, Void> {

  private final DynamoDbClient dynamoDbClient;
  private final SnsClient snsClient;
  private final String categoriesTableName;

  public CategoryStreamProcessor() {
    this.dynamoDbClient = DynamoDbClient.create();
    this.snsClient = SnsClient.create();
    this.categoriesTableName = System.getenv("CATEGORIES_TABLE_NAME");
  }

  @Override
  public Void handleRequest(DynamodbEvent event, Context context) {
    context.getLogger().log("Processing " + event.getRecords().size() + " DynamoDB stream records");

    for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
      try {
        processRecord(record, context);
      } catch (Exception e) {
        context.getLogger().log("Error processing record: " + e.getMessage());
      }
    }

    return null;
  }

  private void processRecord(DynamodbEvent.DynamodbStreamRecord record, Context context) {
    String eventName = record.getEventName();
    context.getLogger().log("Processing event: " + eventName);

    if (!"INSERT".equals(eventName)) {
      context.getLogger().log("Skipping non-INSERT event: " + eventName);
      return;
    }

    StreamRecord streamRecord = record.getDynamodb();
    Map<String, AttributeValue> newImage = streamRecord.getNewImage();

    if (newImage == null || newImage.isEmpty()) {
      context.getLogger().log("No new image found in stream record");
      return;
    }

    // Extract category information
    AttributeValue categoryIdAttr = newImage.get("categoryId");
    AttributeValue snsTopicArnAttr = newImage.get("snsTopicArn");

    if (categoryIdAttr == null || categoryIdAttr.getS() == null) {
      context.getLogger().log("No categoryId found in stream record");
      return;
    }

    String categoryId = categoryIdAttr.getS();

    // Check if SNS topic ARN already exists (created via API)
    if (snsTopicArnAttr != null
        && snsTopicArnAttr.getS() != null
        && !snsTopicArnAttr.getS().isEmpty()) {
      context.getLogger().log("SNS topic already exists for category: " + categoryId);
      return;
    }

    context.getLogger().log("Creating SNS topic for category: " + categoryId);

    String snsTopicArn = createSnsTopicForCategory(categoryId, context);

    if (snsTopicArn != null) {
      // Update the category record with the SNS topic ARN
      updateCategoryWithSnsTopicArn(categoryId, snsTopicArn, context);
      context
          .getLogger()
          .log(
              "Successfully updated category "
                  + categoryId
                  + " with SNS topic ARN: "
                  + snsTopicArn);
    }
  }

  private String createSnsTopicForCategory(String categoryId, Context context) {
    try {
      // Create a topic name using category ID (remove hyphens for SNS compatibility)
      String topicName = "jobs-categories-" + categoryId.replace("-", "");

      CreateTopicRequest createTopicRequest = CreateTopicRequest.builder().name(topicName).build();

      CreateTopicResponse response = snsClient.createTopic(createTopicRequest);
      String topicArn = response.topicArn();

      context.getLogger().log("Created SNS topic: " + topicArn + " for category: " + categoryId);
      return topicArn;

    } catch (Exception e) {
      context
          .getLogger()
          .log("Error creating SNS topic for category " + categoryId + ": " + e.getMessage());
      return null;
    }
  }

  private void updateCategoryWithSnsTopicArn(
      String categoryId, String snsTopicArn, Context context) {
    try {
      UpdateItemRequest updateRequest =
          UpdateItemRequest.builder()
              .tableName(categoriesTableName)
              .key(
                  Map.of(
                      "categoryId",
                      software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                          .s(categoryId)
                          .build()))
              .updateExpression("SET snsTopicArn = :snsTopicArn")
              .expressionAttributeValues(
                  Map.of(
                      ":snsTopicArn",
                      software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                          .s(snsTopicArn)
                          .build()))
              .build();

      dynamoDbClient.updateItem(updateRequest);

    } catch (Exception e) {
      context
          .getLogger()
          .log("Error updating category " + categoryId + " with SNS topic ARN: " + e.getMessage());
      throw new RuntimeException("Failed to update category with SNS topic ARN", e);
    }
  }
}

