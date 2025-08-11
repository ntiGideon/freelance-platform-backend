package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class SaveUserDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final String USERS_TABLE = System.getenv("USERS_TABLE");
    private final LambdaLogger logger;
    
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String userId = (String) input.get("userId");
        if (userId == null) throw new IllegalArgumentException( "userId missing in input" );
        
        // Get user attributes
        Map<String, String> userAttributes = (Map<String, String>) input.get("userAttributes");
        
        if (userAttributes == null) throw new IllegalArgumentException( "userAttributes missing in input" );
        
        // Prepare item to put into DynamoDB
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(userId).build());
        item.put("email", AttributeValue.builder().s(userAttributes.get("email")).build());
        item.put("firstName", AttributeValue.builder().s(userAttributes.getOrDefault("given_name", "")).build());
        item.put("lastName", AttributeValue.builder().s(userAttributes.getOrDefault("family_name", "")).build());
        item.put("jobCategories", AttributeValue.builder().ss(userAttributes.getOrDefault("custom:job_categories",
                "").split(",")).build());
        item.put("phoneNumber", AttributeValue.builder().s(userAttributes.getOrDefault("phone_number", "")).build());
        
        try {
            PutItemRequest request = PutItemRequest.builder()
                    .tableName(USERS_TABLE)
                    .item(item)
                    .build();
            
            dynamoDbClient.putItem(request);
            
            logger.log("Saved user data for userId: " + userId);
        } catch ( Exception e ) {
            logger.log( "Failed to save user data: " + e.getMessage() );
            throw new RuntimeException( e );
        }
        
        return input; // pass input forward
    }
}