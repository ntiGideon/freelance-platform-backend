package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.utils.Utils;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class SubscribeUserToSNSHandler implements RequestHandler<Map<String, Object>, Object> {
    
    private final SnsClient snsClient = SnsClient.create();
    private final String topicArn = System.getenv( "SNS_TOPIC_ARN" );
    
    @Override
    public Object handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        
        String subscriptionArn = null;
        
        String email = (String) input.get( "email" );
        
        if ( email == null || topicArn == null ) throw new IllegalArgumentException( "Missing email or SNS_TOPIC_ARN" );
        
        if ( input.get( "preferredJobCategories" ).equals( List.of( "" ) ) ) {
            logger.log( "No jobs to subscribe to" );
        } else {
            
            // Subscribe user email (v2)
            SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                    .topicArn( topicArn )
                    .protocol( "email" )
                    .endpoint( email )
                    .build();
            
            SubscribeResponse subscribeResponse = snsClient.subscribe( subscribeRequest );
            subscriptionArn = subscribeResponse.subscriptionArn();
        }
        
        // Build filter policy
        List<String> categories = Utils.parsePreferredJobCategories( input.get( "preferredJobCategories" ) );
        
        if ( categories != null && !categories.isEmpty() && subscriptionArn != null ) {
            String filterPolicyJson = "{\"category\":[" +
                                      categories.stream()
                                              .map( cat -> "\"" + cat.trim() + "\"" )
                                              .collect( Collectors.joining( "," ) ) + "]}";
            
            snsClient.setSubscriptionAttributes( SetSubscriptionAttributesRequest.builder()
                    .subscriptionArn( subscriptionArn )
                    .attributeName( "FilterPolicy" )
                    .attributeValue( filterPolicyJson )
                    .build() );
        }
        
        logger.log( "Subscribed " + email + " with filter policy: " + categories );
        
        return input;
    }
}