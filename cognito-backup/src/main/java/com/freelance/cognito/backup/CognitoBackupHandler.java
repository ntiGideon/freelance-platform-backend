package com.freelance.cognito.backup;

import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.ListUsersRequest;
import com.amazonaws.services.cognitoidp.model.ListUsersResult;
import com.amazonaws.services.cognitoidp.model.UserType;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class CognitoBackupHandler implements RequestHandler<ScheduledEvent, Void> {

    private final AWSCognitoIdentityProvider cognitoClient;
    private final DynamoDB dynamoDB;
    private final String userPoolId;
    private final String primaryTableName;
    private final String secondaryTableName;
    private final long ttlDays;

    public CognitoBackupHandler() {
        this.cognitoClient = AWSCognitoIdentityProviderClientBuilder.defaultClient();
        this.dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder.defaultClient());
        this.userPoolId = System.getenv("USER_POOL_ID");
        this.primaryTableName = System.getenv("PRIMARY_BACKUP_TABLE");
        this.secondaryTableName = System.getenv("SECONDARY_BACKUP_TABLE");
        this.ttlDays = Long.parseLong(System.getenv("BACKUP_TTL_DAYS"));
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        try {
            // Get all users from Cognito
            ListUsersResult result = cognitoClient.listUsers(new ListUsersRequest()
                    .withUserPoolId(userPoolId));

            // Calculate TTL timestamp (current time + days in seconds)
            long ttlTimestamp = Instant.now().plusSeconds(ttlDays * 24 * 60 * 60).getEpochSecond();

            // Backup users to both DynamoDB tables
            for (UserType user : result.getUsers()) {
                Map<String, Object> userData = new HashMap<>();
                userData.put("userId", user.getUsername());
                userData.put("email", user.getAttributes().stream()
                        .filter(attr -> "email".equals(attr.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
                userData.put("userAttributes", user.getAttributes());
                userData.put("userStatus", user.getUserStatus());
                userData.put("enabled", user.getEnabled());
                userData.put("created", user.getUserCreateDate().getTime());
                userData.put("modified", user.getUserLastModifiedDate().getTime());
                userData.put("backupTimestamp", System.currentTimeMillis());
                userData.put("ttl", ttlTimestamp);

                // Write to primary table
                Table primaryTable = dynamoDB.getTable(primaryTableName);
                primaryTable.putItem(Item.fromMap(userData));

                // Write to secondary table
                Table secondaryTable = dynamoDB.getTable(secondaryTableName);
                secondaryTable.putItem(Item.fromMap(userData));
            }

            return null;
        } catch (Exception e) {
            context.getLogger().log("Error backing up Cognito users: " + e.getMessage());
            throw e;
        }
    }
}