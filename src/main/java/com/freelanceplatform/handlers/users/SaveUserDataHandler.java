package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.models.User;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Map;


public class SaveUserDataHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    public static final String USER = "USER";
    private final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private final String USERS_TABLE = System.getenv( "USERS_TABLE" );
    
    @Override
    public Map<String, Object> handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        String userId = (String) input.get( "userId" );
        if ( userId == null ) throw new IllegalArgumentException( "userId missing in input" );
        
        // Get user attributes
        Map<String, String> userAttributes = (Map<String, String>) input.get( "userAttributes" );
        
        if ( userAttributes == null ) throw new IllegalArgumentException( "userAttributes missing in input" );
        
        // Get role from input (set by AssignUserRole step)
        String role = (String) input.getOrDefault( "role", USER );
        
        // Map the incoming payload to a User object
        User user = new User();
        user.setCognitoId( userAttributes.get("sub") );
        user.setUserId( userId );
        user.setEmail( userAttributes.get("email") );
        user.setFirstname( userAttributes.get("given_name") );
        user.setMiddlename( userAttributes.getOrDefault("middle_name", "") );
        user.setLastname( userAttributes.get("family_name") );
        user.setPhonenumber( userAttributes.getOrDefault("phone_number", "") );
        user.setPreferredJobCategories( userAttributes.getOrDefault( "custom:job_categories", "" ));
        user.setRole(role);
        
        // Save to DynamoDB
        DynamoDbTable<User> userTable = dynamoDbClient.table(USERS_TABLE, TableSchema.fromBean(User.class));
        userTable.putItem(user);
        
        logger.log( "User saved to DB" );
        
        return input;
    }
}