package com.freelanceplatform.utils;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class AccountNumberGenerator {
    
    private static final String ACCOUNT_NUMBER_PREFIX = "ACCT-";
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private static final String ACCOUNT_NUMBER_INDEX = "AccountNumberIndex";
    
    public AccountNumberGenerator(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }
    
    public String generateUniqueAccountNumber() {
        Random random = new Random();
        String accountNumber;
        boolean exists;
        
        do {
            int number = 10000000 + random.nextInt(90000000); // Generate 8-digit number
            accountNumber = ACCOUNT_NUMBER_PREFIX + number;
            
            exists = checkAccountNumberExists(accountNumber);
        } while (exists);
        
        return accountNumber;
    }
    
    private boolean checkAccountNumberExists(String accountNumber) {
        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":acctNum", AttributeValue.builder().s(accountNumber).build());
        
        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(tableName)
                .indexName(ACCOUNT_NUMBER_INDEX ) // Use the GSI here
                .keyConditionExpression("accountNumber = :acctNum")
                .expressionAttributeValues(expressionValues)
                .limit(1) // we only need to know if at least one exists
                .build();
        
        try {
            QueryResponse queryResponse = dynamoDbClient.query(queryRequest);
            return queryResponse.count() > 0;
        } catch (Exception e) {
            // Log or handle exception as needed
            // Assuming safe to say does not exist on error here to avoid blocking
            return false;
        }
    }
}