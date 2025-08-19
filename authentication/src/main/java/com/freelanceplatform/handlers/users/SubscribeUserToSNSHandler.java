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
    
    @Override
    public Object handleRequest (Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        
        String subscriptionArn;
        
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
        
        List<String> subscribedTopics = subscribeUserToCategories(email, categories, logger);
        
        logger.log( "Subscribed " + email + " to job categories" );
        
        return input;
    }
    
    private List<JobCategory> fetchCategories(List<String> categoryIds) {
        DynamoDbTable<JobCategory> categoryTable = dynamoDbClient.table(CATEGORIES_TABLE, TableSchema.fromBean(JobCategory.class));

        ReadBatch.Builder<JobCategory> readBatchBuilder =
                ReadBatch.builder(JobCategory.class).mappedTableResource(categoryTable);

        categoryIds.forEach(id ->
                readBatchBuilder.addGetItem(Key.builder().partitionValue(id).build())
        );

        BatchGetItemEnhancedRequest request = BatchGetItemEnhancedRequest.builder()
                .readBatches(readBatchBuilder.build())
                .build();

        return dynamoDbClient.batchGetItem(request)
                .resultsForTable(categoryTable)
                .stream()
                .filter(cat -> cat.getSnsTopicArn() != null && !cat.getSnsTopicArn().isBlank())
                .collect(Collectors.toList());
    }
    
    private List<String> subscribeUserToCategories(String email, List<JobCategory> categories, LambdaLogger logger) {
        return categories.stream()
                .map(cat -> {
                    try {
                        SubscribeResponse response = snsClient.subscribe(
                                SubscribeRequest.builder()
                                        .topicArn(cat.getSnsTopicArn())
                                        .protocol("email")
                                        .endpoint(email)
                                        .build()
                        );
                        logger.log("Subscribed to topic " + cat.getSnsTopicArn() +
                                   " (subscriptionArn: " + response.subscriptionArn() + ")");
                        return cat.getSnsTopicArn();
                    } catch (Exception e) {
                        logger.log("Failed to subscribe to topic " + cat.getSnsTopicArn() +
                                   ": " + e.getMessage());
                        return null;
                    }
                })
                .filter(arn -> arn != null)
                .collect(Collectors.toList());
    }
}
