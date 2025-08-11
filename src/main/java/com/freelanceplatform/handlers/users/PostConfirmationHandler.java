package com.freelanceplatform.handlers.users;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.CognitoUserPoolPostConfirmationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class PostConfirmationHandler implements RequestHandler<CognitoUserPoolPostConfirmationEvent, Object> {
    
    private static final String STATE_MACHINE_ARN = System.getenv( "STATE_MACHINE_ARN" );
    private final SfnClient sfnClient = SfnClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LambdaLogger logger;
    
    @Override
    public Object handleRequest (CognitoUserPoolPostConfirmationEvent event, Context context) {
        if ( "PostConfirmation_ConfirmSignUp".equals( event.getTriggerSource() ) ) {
            if ( STATE_MACHINE_ARN == null || STATE_MACHINE_ARN.isEmpty() ) {
                logger.log( "Step Function ARN environment variable is not set" );
                throw new IllegalStateException( "Step Function ARN environment variable is missing" );
            }
            try {
                String username = event.getUserName();
                Map<String, String> userAttributes = event.getRequest().getUserAttributes();
                
                Map<String, Object> input = new HashMap<>();
                input.put( "username", username );
                input.put( "userAttributes", userAttributes );
                
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