package com.freelanceplatform.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelanceplatform.models.JobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Utils {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger( Utils.class );
    private static final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private static final String CATEGORIES_TABLE = System.getenv( "CATEGORIES_TABLE" );
    
    private Utils () {} // prevent instantiation
    
    /**
     * Attempts to parse a JSON string or List of job category IDs into a List of Strings.
     * If the input is null, an empty list is returned.
     * If the input is a JSON string, it is parsed using Jackson's ObjectMapper.
     * If the input is a List, it is converted to a Stream, filtered for non-null elements,
     * mapped to a Stream of Strings, and collected into a List.
     * If the input is neither a JSON string nor a List, or if any exception occurs during
     * the parsing process, an empty list is returned and an error is logged.
     * @param categoriesObj the input to parse, either a JSON string or a List
     * @return the parsed List of Strings, or an empty List if any error occurs
     */
    public static List<String> parsePreferredJobCategoryIds (Object categoriesObj) {
        if ( categoriesObj == null ) return List.of();
        
        try {
            if ( categoriesObj instanceof String jsonStr ) {
                // Frontend sent JSON.stringify output
                return mapper.readValue( jsonStr, new TypeReference<>() {
                } );
            } else if ( categoriesObj instanceof List<?> ) {
                // Already a list
                return ( (List<?>) categoriesObj ).stream()
                        .filter( Objects::nonNull )
                        .map( Object::toString )
                        .collect( Collectors.toList() );
            }
        } catch ( Exception e ) {
            log.error("Failed to parse job category IDs: {}", e.getMessage());
        }
        return List.of();
    }
    
    /**
     * Fetches job categories from DynamoDB based on a list of category IDs.
     * @param categoryIds the list of category IDs to fetch
     * @return a List of JobCategory objects, or an empty List if any error occurs
     */
    public static List<JobCategory> fetchCategories(List<String> categoryIds) {
        DynamoDbTable<JobCategory> categoryTable = dynamoDbClient.table(CATEGORIES_TABLE, TableSchema.fromBean(JobCategory.class));
        
        ReadBatch.Builder<JobCategory> readBatchBuilder =
                ReadBatch.builder(JobCategory.class).mappedTableResource(categoryTable);
        
        categoryIds.forEach(id ->
                readBatchBuilder.addGetItem( Key.builder().partitionValue(id).build())
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
}