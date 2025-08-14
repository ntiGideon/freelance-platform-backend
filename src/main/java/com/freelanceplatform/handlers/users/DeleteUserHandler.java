package com.freelanceplatform.handlers.users;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.freelanceplatform.models.PaymentAccount;
import com.freelanceplatform.models.User;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Map;
import java.util.Objects;

public class DeleteUserHandler implements RequestHandler<Map<String, Object>, String> {
    private final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private final String USERS_TABLE = System.getenv( "USERS_TABLE" );
    private final String ACCOUNTS_TABLE = System.getenv( "ACCOUNTS_TABLE" );
    
    
    @Override
    public String handleRequest (Map<String, Object> event, Context context) {
        LambdaLogger logger = context.getLogger();
        ObjectMapper mapper = new ObjectMapper().enable( SerializationFeature.INDENT_OUTPUT );
        
        try {
            String eventJson = mapper.writeValueAsString( event );
            logger.log( "Incoming event:\n" + eventJson + "\n" );
        } catch ( Exception e ) {
            logger.log( "Failed to serialize event: " + e.getMessage() + "\n" );
        }
        
        // Extract username (sub) from EventBridge event
        Map<String, Object> detail = (Map<String, Object>) event.get( "detail" );
        Map<String, Object> requestParams = (Map<String, Object>) detail.get( "requestParameters" );
        
        String username = (String) requestParams.get( "username" );
        logger.log( "Deleting data for user: " + username + "\n" );
        
        if ( username == null || username.trim().isEmpty() ) {
            String errorMsg = "Invalid input: username is required";
            logger.log( errorMsg );
            throw new IllegalArgumentException( errorMsg );
        }
        
        try {
            // Delete user data
            deleteItemFromTable( USERS_TABLE, username, logger );
            
            // Delete account data
            deleteItemFromTable( ACCOUNTS_TABLE, username, logger );
            
            logger.log( "Successfully deleted user and account data for user: " + username );
            return "Deleted user and account data for user: " + username;
            
        } catch ( Exception e ) {
            String err = "Failed to delete user/account data: " + e.getMessage();
            logger.log( err );
            throw e;
        }
    }
    
    private void deleteItemFromTable (String tableName, String keyValue, LambdaLogger logger) {
        DynamoDbTable<?> table;
        
        if ( Objects.equals( tableName, USERS_TABLE ) ) {
            table = dynamoDbClient.table( tableName, TableSchema.fromBean( User.class ) );
        } else {
            table = dynamoDbClient.table( tableName, TableSchema.fromBean( PaymentAccount.class ) );
        }
        
        Key key = Key.builder().partitionValue( keyValue ).build();
        
        table.deleteItem( r -> r.key( key ) );
        
        logger.log( "Deleted item with key " + keyValue + " from table " + tableName );
    }
}