package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelanceplatform.models.JobCategory;
import com.freelanceplatform.models.User;
import com.freelanceplatform.utils.Utils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicRequest;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicResponse;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;

import java.util.*;
import java.util.stream.Collectors;

import static com.freelanceplatform.utils.Utils.fetchCategories;

public class UpdateUserProfileHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    private final DynamoDbTable<User> userTable;
    private final SnsClient snsClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    public UpdateUserProfileHandler () {
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.create();
        String USERS_TABLE = System.getenv( "USERS_TABLE" );
        this.userTable = enhancedClient.table( USERS_TABLE, TableSchema.fromBean( User.class ) );
        this.snsClient = SnsClient.create();
    }
    
    @Override
    public Map<String, Object> handleRequest (Map<String, Object> input, Context context) {
        
        LambdaLogger logger = context.getLogger();
        
        try {
            String eventJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString( input );
            logger.log( "Update Lambda event: " + eventJson );
            
            //Get UserId from token
            String userId = getUserIdFromClaims( input );
            if ( userId == null ) {
                logger.log( "User Id is null" );
                return Map.of( "statusCode", 403, "body", "Unauthorized: Invalid token" );
            }
            
            Map<String, Object> body = parseRequestBody( input );
            
            String firstname = (String) body.get( "firstname" );
            String middlename = (String) body.getOrDefault( "middlename", "" );
            String lastname = (String) body.get( "lastname" );
            String phonenumber = (String) body.getOrDefault( "phonenumber", "" );
            String email = (String) body.get( "email" );
            List<String> newJobCategories = Utils.parsePreferredJobCategoryIds( body.get( "jobCategoryIds" ) );
            
            // Fetch user from DynamoDB
            User user = userTable.getItem( req -> req.key( k -> k.partitionValue( userId ) ) );
            
            if ( user == null ) {
                logger.log( "User not found" );
                return Map.of( "statusCode", 404, "body", "User not found" );
            }
            
            // Fetch old job categories
            List<String> oldJobCategoryIds = user.getJobCategoryIds() != null ? user.getJobCategoryIds() : Collections.emptyList();
            
            // Determine if job categories have changed
            List<String> added = new ArrayList<>( newJobCategories );
            added.removeAll( oldJobCategoryIds );
            
            List<String> removed = new ArrayList<>( oldJobCategoryIds );
            removed.removeAll( newJobCategories );
            
            // Update user profile
            user.setFirstname( firstname );
            user.setMiddlename( middlename );
            user.setLastname( lastname );
            user.setPhonenumber( phonenumber );
            user.setJobCategoryIds( newJobCategories );
            
            // Save updated user to DynamoDB
            userTable.updateItem( user );
            
            // Send SNS notification
            Map<String, String> addedCategoryTopicArns = fetchCategoryTopicArns( added );
            Map<String, String> removedCategoryTopicArns = fetchCategoryTopicArns( removed );
            
            addedCategoryTopicArns.forEach( (categoryId, topicArn) -> subscribeToCategory( email, topicArn ) );
            removedCategoryTopicArns.forEach( (categoryId, topicArn) -> unsubscribeFromCategory( email, topicArn ) );
            
            logger.log( "User profile updated successfully" );
            return Map.of( "message", "User profile updated successfully" );
        } catch ( Exception e ) {
            logger.log( "Error while updating user profile: " + e.getMessage() );
            return Map.of( "statusCode", 500, "body", "Error updating user profile" + e.getMessage() );
        }
    }
    
    /**
     * Extracts the user ID from the "requestContext" of the Lambda event.
     * <p>
     * The event is expected to contain a "requestContext" with an "authorizer"
     * containing a "claims" map with a "sub" key containing the user ID.
     *
     * @param event The Lambda event
     * @return The user ID or null if not found
     */
    @SuppressWarnings( "unchecked" )
    private String getUserIdFromClaims (Map<String, Object> event) {
        Map<String, Object> requestContext = (Map<String, Object>) event.get( "requestContext" );
        if ( requestContext == null ) return null;
        
        Map<String, Object> authorizer = (Map<String, Object>) requestContext.get( "authorizer" );
        if ( authorizer == null ) return null;
        
        Map<String, Object> claims = (Map<String, Object>) authorizer.get( "claims" );
        if ( claims == null ) return null;
        
        return (String) claims.get( "sub" );
    }
    
    /**
     * Parses the request body from the event.
     *
     * @param event The Lambda event
     * @return The parsed request body as a Map
     */
    @SuppressWarnings( "unchecked" )
    private Map<String, Object> parseRequestBody (Map<String, Object> event) {
        Object bodyObj = event.get( "body" );
        if ( bodyObj == null ) {
            return new HashMap<>();
        }
        
        // If API Gateway proxy integration has already parsed JSON → Map
        if ( bodyObj instanceof Map ) {
            return (Map<String, Object>) bodyObj;
        }
        
        // Otherwise, it's a raw JSON string → parse manually
        try {
            return MAPPER.readValue( bodyObj.toString(), Map.class );
        } catch ( Exception e ) {
            throw new RuntimeException( "Failed to parse request body: " + e.getMessage(), e );
        }
    }
    
    /**
     * Given a list of job category IDs, fetches the categories and returns a map of
     * category ID to SNS topic ARN.
     *
     * @param categoryIds The list of category IDs
     * @return A map of category ID to SNS topic ARN
     */
    private Map<String, String> fetchCategoryTopicArns (List<String> categoryIds) {
        if ( categoryIds == null || categoryIds.isEmpty() ) {
            return Collections.emptyMap();
        }
        List<JobCategory> categories = fetchCategories( categoryIds );
        return categories.stream()
                .collect( Collectors.toMap(
                        JobCategory::getCategoryId,
                        JobCategory::getSnsTopicArn
                ) );
    }
    
    /**
     * Subscribes the user to the specified SNS topic.
     *
     * @param email    The user's email address
     * @param topicArn The ARN of the SNS topic
     */
    private void subscribeToCategory (String email, String topicArn) {
        snsClient.subscribe( SubscribeRequest.builder()
                .topicArn( topicArn )
                .protocol( "email" )
                .endpoint( email )
                .build() );
    }
    
    /**
     * Unsubscribes the user from the specified SNS topic.
     *
     * @param email    The user's email address
     * @param topicArn The ARN of the SNS topic
     */
    private void unsubscribeFromCategory (String email, String topicArn) {
        ListSubscriptionsByTopicResponse subs = snsClient.listSubscriptionsByTopic(
                ListSubscriptionsByTopicRequest.builder().topicArn( topicArn ).build() );
        
        subs.subscriptions().stream().filter( s -> s.endpoint().equals( email ) )
                .forEach( s -> snsClient.unsubscribe( UnsubscribeRequest.builder()
                        .subscriptionArn( s.subscriptionArn() )
                        .build() ) );
    }
}