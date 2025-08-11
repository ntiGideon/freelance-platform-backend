package com.freelanceplatform.handlers.users;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class DeleteUserHandler implements RequestHandler<Map<String, String>, String> {
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final String USERS_TABLE = System.getenv( "USERS_TABLE" );
    private final String ACCOUNTS_TABLE = System.getenv( "ACCOUNTS_TABLE" );
    private final LambdaLogger logger;
    
    @Override
    public String handleRequest (Map<String, String> event, Context context) {
        String userId = event.get( "userId" );
        
        if ( userId == null || userId.trim().isEmpty() ) {
            String errorMsg = "Invalid input: userId is required";
            logger.log( errorMsg );
            throw new IllegalArgumentException( errorMsg );
        }
        
        try {
            // Delete user data
            deleteItemFromTable( USERS_TABLE, userId );
            
            // Delete account data
            deleteItemFromTable( ACCOUNTS_TABLE, userId );
            
            logger.log( "Successfully deleted user and account data for userId: " + userId );
            return "Deleted user and account data for userId: " + userId;
            
        } catch ( Exception e ) {
            String err = "Failed to delete user/account data: " + e.getMessage();
            logger.log( err );
            throw e;
        }
    }
    
    private void deleteItemFromTable (String tableName, String keyValue) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put( "userId", AttributeValue.builder().s( keyValue ).build() );
        
        DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                .tableName( tableName )
                .key( key )
                .build();
        
        dynamoDbClient.deleteItem( deleteRequest );
        logger.log( "Deleted item with key " + keyValue + " from table " + tableName );
    }
}