package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class GenerateUserIdHandler implements RequestHandler<Map<String,Object>, Map<String,Object>> {
    
    private final LambdaLogger logger;
    
    @Override
    public Map<String,Object> handleRequest(Map<String,Object> input, Context context) {
        logger.log("GenerateUserIdHandler invoked");
        
        // Generate unique userId
        String userId = UUID.randomUUID().toString();
        
        // Add userId to the input and return
        input.put("userId", userId);
        
        logger.log("Generated userId: " + userId);
        
        return input;
    }
}