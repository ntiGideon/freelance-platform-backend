package com.freelanceplatform.handlers.users;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.freelanceplatform.models.PaymentAccount;
import com.freelanceplatform.utils.AccountNumberGenerator;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Map;

public class CreateAccountHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final DynamoDbEnhancedClient dynamoDbClient = DynamoDbEnhancedClient.create();
    private final String ACCOUNTS_TABLE = System.getenv( "ACCOUNTS_TABLE" );
    
    @Override
    public Map<String, Object> handleRequest (Map<String, Object> input, Context context) {
        
        DynamoDbTable<PaymentAccount> accountTable = dynamoDbClient.table( ACCOUNTS_TABLE, TableSchema.fromBean( PaymentAccount.class ) );
        
        LambdaLogger logger = context.getLogger();
        
        String userId = (String) input.get( "userId" );
        String firstname = (String) input.get( "firstname" );
        String lastname = (String) input.get( "lastname" );
        
        if ( userId == null ) throw new IllegalArgumentException( "UserId missing" );
        
        // Generate account number and initial details
        String accountNumber = generateAccountNumber();
        // Create accountName from user's name
        String accountName = ( firstname != null ? firstname : "" ) + " " + ( lastname != null ? lastname : "" );
        double initialBalance = 0.0;
        
        PaymentAccount paymentAccount = new PaymentAccount();
        paymentAccount.setUserId( userId );
        paymentAccount.setAccountNumber( accountNumber );
        paymentAccount.setAccountName( accountName );
        paymentAccount.setInitialBalance( initialBalance );
        
        // Save to DynamoDB
        try {
            accountTable.putItem(paymentAccount);
            
            logger.log( "Created account " + accountNumber + " for userId " + userId );
        } catch ( Exception e ) {
            logger.log( "Failed to create account\n " + e.getMessage() );
            throw new RuntimeException( e );
        }
        return input;
    }
    
    private String generateAccountNumber () {
        AccountNumberGenerator accountNumberGenerator = new AccountNumberGenerator( dynamoDbClient, ACCOUNTS_TABLE );
        return accountNumberGenerator.generateUniqueAccountNumber();
    }
}