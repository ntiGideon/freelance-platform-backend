package com.freelanceplatform.authservice;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.authservice.utils.AccountNumberGenerator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

public class CreateAccountHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final String ACCOUNTS_TABLE = System.getenv( "ACCOUNTS_TABLE" );
    
    @Override
    public Map<String, Object> handleRequest (Map<String, Object> input, Context context) {
        String userId = (String) input.get( "userId" );
        String firstname = (String) input.get( "firstname" );
        String lastname = (String) input.get( "lastname" );
        
        if ( userId == null ) throw new IllegalArgumentException( "UserId missing" );
        
        // Generate account number and initial details
        String accountNumber = generateAccountNumber();
        // Create accountName from user's name
        String accountName = ( firstname != null ? firstname : "" ) + " " + ( lastname != null ? lastname : "" );
        double initialBalance = 0.0;
        
        Map<String, AttributeValue> item = new HashMap<>();
        item.put( "userId", AttributeValue.builder().s( userId ).build() );
        item.put( "accountNumber", AttributeValue.builder().s( accountNumber ).build() );
        item.put( "accountName", AttributeValue.builder().s( accountName ).build() );
        item.put( "balance", AttributeValue.builder().n( String.valueOf( initialBalance ) ).build() );
        
        try {
            PutItemRequest request = PutItemRequest.builder()
                    .tableName( ACCOUNTS_TABLE )
                    .item( item )
                    .build();
            
            dynamoDbClient.putItem( request );
            
            context.getLogger().log( "Created account " + accountNumber + " for userId: " + userId );
        } catch ( Exception e ) {
            context.getLogger().log( "Failed to create account: " + e.getMessage() );
            throw new RuntimeException( e );
        }
        
        return input;
    }
    
    private String generateAccountNumber () {
        AccountNumberGenerator accountNumberGenerator = new AccountNumberGenerator( dynamoDbClient, ACCOUNTS_TABLE );
        return accountNumberGenerator.generateUniqueAccountNumber();
    }
}