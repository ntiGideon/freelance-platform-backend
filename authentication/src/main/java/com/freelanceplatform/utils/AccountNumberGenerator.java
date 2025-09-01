package com.freelanceplatform.utils;

import com.freelanceplatform.models.PaymentAccount;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.Random;

public class AccountNumberGenerator {
    
    private static final String ACCOUNT_NUMBER_PREFIX = "ACCT-";
    private static final String ACCOUNT_NUMBER_INDEX = "AccountNumberIndex";
    private final DynamoDbEnhancedClient dynamoDbClient;
    private final String tableName;
    
    public AccountNumberGenerator (DynamoDbEnhancedClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }
    
    public String generateUniqueAccountNumber () {
        Random random = new Random();
        String accountNumber;
        boolean exists;
        
        do {
            int number = 10000000 + random.nextInt( 90000000 ); // Generate 8-digit number
            accountNumber = ACCOUNT_NUMBER_PREFIX + number;
            
            exists = checkAccountNumberExists( accountNumber );
        } while ( exists );
        
        return accountNumber;
    }
    
    private boolean checkAccountNumberExists (String accountNumber) {
        try {
            // Reference the table mapped to your POJO
            DynamoDbTable<PaymentAccount> mappedTable = dynamoDbClient.table( tableName, TableSchema.fromBean( PaymentAccount.class ) );
            
            // Reference the GSI
            DynamoDbIndex<PaymentAccount> accountNumberIndex = mappedTable.index( ACCOUNT_NUMBER_INDEX );
            
            // Query the index with the partition key value
            return accountNumberIndex.query( r -> r
                            .queryConditional( QueryConditional.keyEqualTo( k -> k.partitionValue( accountNumber ) ) )
                            .limit( 1 ) // just check existence
                    )
                    .stream()
                    .findFirst()       // first page of results
                    .map( page -> !page.items().isEmpty() ) // check if items are present
                    .orElse( false );
        } catch ( Exception e ) {
            return false;
        }
    }
}