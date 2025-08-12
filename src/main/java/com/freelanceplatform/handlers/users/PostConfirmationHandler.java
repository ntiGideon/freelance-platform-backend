package com.freelanceplatform.handlers.users;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.CognitoUserPoolPostConfirmationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.util.HashMap;
import java.util.Map;

public class PostConfirmationHandler implements RequestHandler<CognitoUserPoolPostConfirmationEvent, CognitoUserPoolPostConfirmationEvent> {
    
    private static final String STATE_MACHINE_ARN = System.getenv( "STATE_MACHINE_ARN" );
    private static final String USER = "USER";
    private final SfnClient sfnClient = SfnClient.create();
    private final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public CognitoUserPoolPostConfirmationEvent handleRequest (CognitoUserPoolPostConfirmationEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        if ( "PostConfirmation_ConfirmSignUp".equals( event.getTriggerSource() ) ) {
            if ( STATE_MACHINE_ARN == null || STATE_MACHINE_ARN.isEmpty() ) {
                logger.log( "Step Function ARN environment variable is not set" );
                throw new IllegalStateException( "Step Function ARN environment variable is missing" );
            }
            try {
                String username = event.getUserName();
                String userPoolId = event.getUserPoolId();
                
                //Add user to cognito group
                AdminAddUserToGroupRequest addUserToGroupRequest = AdminAddUserToGroupRequest.builder()
                        .userPoolId( userPoolId )
                        .username( username )
                        .groupName( USER )
                        .build();
                
                cognitoClient.adminAddUserToGroup( addUserToGroupRequest );
                
                Map<String, Object> input = new HashMap<>();
                
                String cognitoSub = event.getRequest().getUserAttributes().get( "sub" );
                
                input.put( "userId", cognitoSub );
                input.put( "cognitoId", cognitoSub );
                input.put( "email", event.getRequest().getUserAttributes().get( "email" ) );
                input.put( "firstname", event.getRequest().getUserAttributes().get( "given_name" ) );
                input.put( "lastname", event.getRequest().getUserAttributes().get( "family_name" ) );
                
                if ( event.getRequest().getUserAttributes().containsKey( "custom:middle_name" ) ) {
                    input.put( "middlename", event.getRequest().getUserAttributes().get( "custom:middle_name" ) );
                }
                
                if ( event.getRequest().getUserAttributes().containsKey( "phone_number" ) ) {
                    input.put( "phonenumber", event.getRequest().getUserAttributes().get( "phone_number" ) );
                }
                
                if ( event.getRequest().getUserAttributes().containsKey( "custom:job_category" ) ) {
                    input.put( "preferredJobCategories", event.getRequest().getUserAttributes().get( "custom:job_category" ) );
                }
                
                String inputJson = objectMapper.writeValueAsString( input );
                
                StartExecutionRequest request = StartExecutionRequest.builder()
                        .stateMachineArn( STATE_MACHINE_ARN )
                        .input( inputJson )
                        .build();
                
                sfnClient.startExecution( request );
                
                logger.log( "Started Step Function execution for user: " + username );
            } catch ( Exception e ) {
                logger.log( "Error starting Step Function execution: " + e );
                throw new RuntimeException( e );
            }
        }
        return event;
    }
}