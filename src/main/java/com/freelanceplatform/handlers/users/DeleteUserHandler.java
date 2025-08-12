package com.freelanceplatform.handlers.users;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.models.PaymentAccount;
import com.freelanceplatform.models.User;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public class DeleteUserHandler implements RequestHandler<Map<String, Object>, String> {
    private final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private final String USERS_TABLE = System.getenv( "USERS_TABLE" );
    private final String ACCOUNTS_TABLE = System.getenv( "ACCOUNTS_TABLE" );
    
    
    @Override
    public String handleRequest (Map<String, Object> event, Context context) {
        LambdaLogger logger = context.getLogger();
        
        // Extract username (email) from EventBridge event
        Map<String, Object> detail = (Map<String, Object>) event.get( "detail" );
        Map<String, Object> requestParams = (Map<String, Object>) detail.get( "requestParameters" );
        
        String email = (String) requestParams.get( "username" );
        logger.log( "Deleting data for user: " + email + "\n" );
        
        if ( email == null || email.trim().isEmpty() ) {
            String errorMsg = "Invalid input: email is required";
            logger.log( errorMsg );
            throw new IllegalArgumentException( errorMsg );
        }
        
        try {
            // Query UsersTable by email GSI
            String userId = getUserIdByEmail( email );
            
            if ( userId == null ) {
                String errorMsg = "No user found for email: " + email;
                logger.log( errorMsg );
                throw new IllegalArgumentException( errorMsg );
            }
            
            // Delete user data
            deleteItemFromTable( USERS_TABLE, userId, logger );
            
            // Delete account data
            deleteItemFromTable( ACCOUNTS_TABLE, userId, logger );
            
            logger.log( "Successfully deleted user and account data for userId: " + userId );
            return "Deleted user and account data for userId: " + userId;
            
        } catch ( Exception e ) {
            String err = "Failed to delete user/account data: " + e.getMessage();
            logger.log( err );
            throw e;
        }
    }
    
    private String getUserIdByEmail (String email) {
        DynamoDbTable<User> usersTable = dynamoDbClient.table( USERS_TABLE, TableSchema.fromBean( User.class ) );
        
        // Query GSI and get first matching item
        Iterator<Page<User>> results = usersTable.index( "EmailIndex" )
                .query( r -> r.queryConditional( QueryConditional.keyEqualTo(
                        Key.builder().partitionValue( email ).build()
                ) ).limit( 1 ) )
                .iterator();
        
        if ( results.hasNext() ) {
            Page<User> page = results.next();
            if ( !page.items().isEmpty() ) {
                User user = page.items().get( 0 );
                return user.getUserId();
            }
        }
        
        return null;
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