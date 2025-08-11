package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;


public class SubscribeUserToSNSHandler implements RequestHandler<Map<String, Object>, Object> {
    
    private final SnsClient snsClient = SnsClient.create();
    private final String topicArn = System.getenv( "SNS_TOPIC_ARN" );
    
    
    @Override
    public Object handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        String email = (String) input.get( "email" );
        String preferredCategoriesCsv = (String) input.get( "preferredJobCategories" );
        
        if ( email == null || topicArn == null ) throw new IllegalArgumentException( "Missing email or SNS_TOPIC_ARN" );
        
        // Subscribe user email (v2)
        SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                .topicArn( topicArn )
                .protocol( "email" )
                .endpoint( email )
                .build();
        
        SubscribeResponse subscribeResponse = snsClient.subscribe( subscribeRequest );
        String subscriptionArn = subscribeResponse.subscriptionArn();
        
        // Build filter policy
        if ( preferredCategoriesCsv != null && !preferredCategoriesCsv.isEmpty() ) {
            String[] categories = preferredCategoriesCsv.split( "," );
            String filterPolicyJson = "{\"category\":[" +
                    Arrays.stream( categories )
                            .map( cat -> "\"" + cat.trim() + "\"" )
                            .collect( Collectors.joining( "," ) ) + "]}";
            
            SetSubscriptionAttributesRequest attrRequest = SetSubscriptionAttributesRequest.builder()
                    .subscriptionArn( subscriptionArn )
                    .attributeName( "FilterPolicy" )
                    .attributeValue( filterPolicyJson )
                    .build();
            
            snsClient.setSubscriptionAttributes( attrRequest );
        }
        
        logger.log( "Subscribed " + email + " with filter policy: " + preferredCategoriesCsv );
        
        return input;
    }
}