package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;

import java.util.Map;

public class AssignUserRoleHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
    private final String USER_POOL_ID = System.getenv( "USER_POOL_ID" );
    private static final String USER = "USER";
    
    
    @Override
    public Map<String, Object> handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        
        String email = null;
        
        Map<String, String> userAttributes = (Map<String, String>) input.get( "userAttributes" );
        
        if ( userAttributes != null ) {
            email = userAttributes.get( "email" );
        }
        
        if ( email == null ) throw new IllegalArgumentException( "Email is required to assign user role" );
        
        try {
            //Add user to cognito group
            AdminAddUserToGroupRequest addUserToGroupRequest = AdminAddUserToGroupRequest.builder()
                    .userPoolId( USER_POOL_ID )
                    .username( email )
                    .groupName( USER )
                    .build();
            
            cognitoClient.adminAddUserToGroup( addUserToGroupRequest );
            
            logger.log( "Successfully assigned user role to user: " + email );
            
            // Add role to output for next steps
            input.put( "role", USER );
            
        } catch ( Exception e ) {
            logger.log( "Error assigning user role: " + e );
            throw new RuntimeException( e );
        }
        
        return input;
    }
}