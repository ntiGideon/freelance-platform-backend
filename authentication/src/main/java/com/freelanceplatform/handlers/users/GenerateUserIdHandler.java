package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.util.Map;

public class GenerateUserIdHandler implements RequestHandler<Map<String,Object>, Map<String,Object>> {
    
    
    @Override
    public Map<String,Object> handleRequest(Map<String,Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("GenerateUserIdHandler invoked");
        
        // Generate unique userId
        String userId = (String) input.get("cognitoId");
        
        input.put("userId", userId);
        logger.log("Generated userId: " + userId);
        
        return input;
    }
}