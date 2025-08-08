package com.freelanceplatform.authservice;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

import java.util.HashMap;
import java.util.Map;

public class DeleteUserHandler implements RequestHandler<Map<String, String>, String> {
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final String USERS_TABLE = System.getenv( "USERS_TABLE" );
    private final String ACCOUNTS_TABLE = System.getenv( "ACCOUNTS_TABLE" );
    
    @Override
    public String handleRequest (Map<String, String> event, Context context) {
        String userId = event.get( "userId" );
        if ( userId == null || userId.isEmpty() ) throw new IllegalArgumentException( "UserId is required" );
        
        // Delete from Users table
        Map<String, AttributeValue> userKey = new HashMap<>();
        userKey.put( "userId", AttributeValue.builder().s( userId ).build() );
        
        DeleteItemRequest deleteUserRequest = DeleteItemRequest.builder()
                .tableName( USERS_TABLE )
                .key( userKey )
                .build();
        
        dynamoDbClient.deleteItem( deleteUserRequest );
        
        // Delete from Accounts table
        Map<String, AttributeValue> accountKey = new HashMap<>();
        accountKey.put( "userId", AttributeValue.builder().s( userId ).build() );
        
        DeleteItemRequest deleteAccountRequest = DeleteItemRequest.builder()
                .tableName( ACCOUNTS_TABLE )
                .key( accountKey )
                .build();
        
        dynamoDbClient.deleteItem( deleteAccountRequest );
        
        context.getLogger().log( "Deleted user and account data for userId: " + userId );
        
        return "User and account deleted for userId: " + userId;
    }
}