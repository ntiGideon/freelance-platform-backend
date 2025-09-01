package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.models.JobCategory;
import com.freelanceplatform.utils.Utils;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.List;
import java.util.Map;

import static com.freelanceplatform.utils.Utils.fetchCategories;

public class SubscribeUserToSNSHandler implements RequestHandler<Map<String, Object>, Object> {
    
    private final SnsClient snsClient = SnsClient.create();
    
    @Override
    public Object handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        
        String email = (String) input.get( "email" );
        
        if ( email == null || email.isBlank()) throw new IllegalArgumentException( "Missing email" );
        
        List<String> categoryIds = Utils.parsePreferredJobCategoryIds( input.get( "jobCategoryIds" ) );
        
        if ( categoryIds == null || categoryIds.isEmpty() ) {
            logger.log( "No jobs to subscribe to" );
            return input;
        }

        List<JobCategory> categories = fetchCategories(categoryIds);

        if (categories.isEmpty()) {
            logger.log("No matching categories found in DynamoDB");
            return input;
        }
        
        subscribeUserToCategories(email, categories, logger);
        
        logger.log( "Subscribed " + email + " to job categories" );
        
        return input;
    }
    
    private void subscribeUserToCategories(String email, List<JobCategory> categories, LambdaLogger logger) {
        categories.forEach( cat -> {
                    try {
                        SubscribeResponse response = snsClient.subscribe(
                                SubscribeRequest.builder()
                                        .topicArn( cat.getSnsTopicArn() )
                                        .protocol( "email" )
                                        .endpoint( email )
                                        .build()
                        );
                        logger.log( "Subscribed to topic " + cat.getSnsTopicArn() +
                                " (subscriptionArn: " + response.subscriptionArn() + ")" );
                    } catch ( Exception e ) {
                        logger.log( "Failed to subscribe to topic " + cat.getSnsTopicArn() +
                                ": " + e.getMessage() );
                    }
                } );
    }
}