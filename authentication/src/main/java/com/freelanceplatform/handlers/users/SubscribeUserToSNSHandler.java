package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.models.JobCategory;
import com.freelanceplatform.utils.Utils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SetSubscriptionAttributesRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SubscribeUserToSNSHandler implements RequestHandler<Map<String, Object>, Object> {
    
    private final SnsClient snsClient = SnsClient.create();
    private final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private final String CATEGORIES_TABLE = System.getenv( "CATEGORIES_TABLE" );
    private final String topicArn = System.getenv( "SNS_TOPIC_ARN" );
    
    @Override
    public Object handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        
        String subscriptionArn;
        
        String email = (String) input.get( "email" );
        
        if ( email == null || topicArn == null ) throw new IllegalArgumentException( "Missing email or SNS_TOPIC_ARN" );
        
        List<String> categoryIds = Utils.parsePreferredJobCategoryIds( input.get( "jobCategoryIds" ) );

        logger.log("Category IDs to fetch: " + categoryIds);
        
        if ( categoryIds == null || categoryIds.isEmpty() ) {
            logger.log( "No jobs to subscribe to" );
            return input;
        } else {
            
            subscriptionArn = getSubscriptionArn( email );
        }
        
        buildFilterPolicy( categoryIds, subscriptionArn );
        
        logger.log( "Subscribed " + email + " with filter policy: " + categoryIds );
        
        return input;
    }
    
    private String getSubscriptionArn (String email) {
        String subscriptionArn;
        // Subscribe user email (v2)
        SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                .topicArn( topicArn )
                .protocol( "email" )
                .endpoint( email )
                .build();
        
        SubscribeResponse subscribeResponse = snsClient.subscribe( subscribeRequest );
        subscriptionArn = subscribeResponse.subscriptionArn();
        return subscriptionArn;
    }
    
    private void buildFilterPolicy (List<String> categoryIds, String subscriptionArn) {
        // Build filter policy
        List<String> categoryNames = batchGetCategoryNames( categoryIds );
        
        if ( categoryNames != null && !categoryNames.isEmpty() && subscriptionArn != null ) {
            String filterPolicyJson = "{\"category\":[" +
                    categoryNames.stream()
                            .map( cat -> "\"" + cat.trim() + "\"" )
                            .collect( Collectors.joining( "," ) ) + "]}";
            
            snsClient.setSubscriptionAttributes( SetSubscriptionAttributesRequest.builder()
                    .subscriptionArn( subscriptionArn )
                    .attributeName( "FilterPolicy" )
                    .attributeValue( filterPolicyJson )
                    .build() );
        }
    }
    
    private List<String> batchGetCategoryNames (List<String> categoryIds) {
        System.out.println("Using DynamoDB table: " + CATEGORIES_TABLE);
        DynamoDbTable<JobCategory> jobCategoryTable = dynamoDbClient.table( CATEGORIES_TABLE, TableSchema.fromBean( JobCategory.class ) );

        System.out.println("Mapped table resource: " + jobCategoryTable.tableName());
        
        ReadBatch.Builder<JobCategory> readBatchBuilder = ReadBatch.builder( JobCategory.class )
                .mappedTableResource( jobCategoryTable );
        
        // Add each key individually
        categoryIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .forEach(id -> {
            System.out.println("Fetching category with ID: " + id);
            readBatchBuilder.addGetItem(Key.builder().partitionValue(id).build());
        });
        
        BatchGetItemEnhancedRequest batchRequest = BatchGetItemEnhancedRequest.builder()
                .readBatches( readBatchBuilder.build() )
                .build();
        
        return dynamoDbClient.batchGetItem( batchRequest )
                .resultsForTable( jobCategoryTable )
                .stream()
                .map( JobCategory::getName )
                .filter( name -> name != null && !name.isBlank() )
                .collect( Collectors.toList() );
    }
}
