package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.models.User;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;
import java.util.Map;


public class SaveUserDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    private final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private final String USERS_TABLE = System.getenv( "USERS_TABLE" );
    
    @Override
    @SuppressWarnings( "unchecked" )
    public Map<String, Object> handleRequest (Map<String, Object> input, Context context) {
        
        LambdaLogger logger = context.getLogger();
        DynamoDbTable<User> userTable = dynamoDbClient.table( USERS_TABLE, TableSchema.fromBean( User.class ) );
        
        // Get userId
        String userId = (String) input.get( "userId" );
        if ( userId == null ) throw new IllegalArgumentException( "userId missing in input" );
        
        // Map the incoming payload to a User object
        User user = new User();
        user.setUserId( userId );
        user.setEmail( (String) input.get( "email" ) );
        user.setFirstname( (String) input.get( "firstname" ) );
        user.setMiddlename( (String) input.getOrDefault( "middlename", "" ) );
        user.setLastname( (String) input.get( "lastname" ) );
        user.setPhonenumber( (String) input.getOrDefault( "phonenumber", "" ) );
        user.setJobCategoryIds( (List<String>) input.get( "jobCategoryIds" ) );
        
        // Save to DynamoDB
        userTable.putItem( user );
        
        logger.log( "User saved to DB" );
        
        return input;
    }
}