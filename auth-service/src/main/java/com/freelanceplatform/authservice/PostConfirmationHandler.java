package com.freelanceplatform.authservice;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.CognitoUserPoolPostConfirmationEvent;
import com.amazonaws.services.stepfunctions.AWSStepFunctions;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClientBuilder;
import com.amazonaws.services.stepfunctions.model.StartExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class PostConfirmationHandler implements RequestHandler<CognitoUserPoolPostConfirmationEvent, Object> {
    
    private static final String STATE_MACHINE_ARN = System.getenv("STATE_MACHINE_ARN");
    private final AWSStepFunctions sfnClient = AWSStepFunctionsClientBuilder.defaultClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public Object handleRequest(CognitoUserPoolPostConfirmationEvent event, Context context) {
        if ("PostConfirmation_ConfirmSignUp".equals(event.getTriggerSource())) {
            if (STATE_MACHINE_ARN == null || STATE_MACHINE_ARN.isEmpty()) {
                context.getLogger().log("Step Function ARN environment variable is not set");
                throw new IllegalStateException("Step Function ARN environment variable is missing");
            }
            try {
                String username = event.getUserName();
                Map<String, String> userAttributes = event.getRequest().getUserAttributes();
                
                Map<String, Object> input = new HashMap<>();
                input.put("username", username);
                input.put("userAttributes", userAttributes);
                
                String inputJson = objectMapper.writeValueAsString(input);
                
                StartExecutionRequest request = new StartExecutionRequest()
                        .withStateMachineArn(STATE_MACHINE_ARN)
                        .withInput(inputJson);
                
                sfnClient.startExecution(request);
                
                context.getLogger().log("Started Step Function execution for user: " + username);
            } catch (Exception e) {
                context.getLogger().log("Error starting Step Function execution: " + e );
                throw new RuntimeException(e);
            }
        }
        return event;
    }
}