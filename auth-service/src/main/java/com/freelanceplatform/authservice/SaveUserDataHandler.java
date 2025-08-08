package com.freelanceplatform.authservice;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

public class SaveUserDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final String USERS_TABLE = System.getenv("USERS_TABLE");
    
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String userId = (String) input.get("userId");
        if (userId == null) throw new IllegalArgumentException( "userId missing in input" );
        Map<String, String> userAttributes = (Map<String, String>) input.get("userAttributes");
        
        // Prepare item to put into DynamoDB
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(userId).build());
        item.put("email", AttributeValue.builder().s(userAttributes.get("email")).build());
        item.put("firstName", AttributeValue.builder().s(userAttributes.getOrDefault("given_name", "")).build());
        item.put("lastName", AttributeValue.builder().s(userAttributes.getOrDefault("family_name", "")).build());
        item.put("preferredJobCategories", AttributeValue.builder().ss(userAttributes.getOrDefault("custom:preferred_job_categories", "").split(",")).build());
        item.put("phoneNumber", AttributeValue.builder().s(userAttributes.getOrDefault("phone_number", "")).build());
        
        PutItemRequest request = PutItemRequest.builder()
                .tableName(USERS_TABLE)
                .item(item)
                .build();
        
        dynamoDbClient.putItem(request);
        
        context.getLogger().log("Saved user data for userId: " + userId);
        
        return input; // pass input forward
    }
}