package com.freelance.payment;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.events.JobApprovedEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;


import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PaymentProcessorHandler implements RequestHandler<ScheduledEvent, Void> {

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String paymentRecordsTable;
    private final String accountsTable;

    public PaymentProcessorHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        this.paymentRecordsTable = System.getenv("PAYMENT_RECORDS_TABLE");
        this.accountsTable = System.getenv("ACCOUNTS_TABLE");
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        try {
            JobApprovedEvent jobApprovedEvent = objectMapper.readValue(
                    (JsonParser) event.getDetail(),
                    JobApprovedEvent.class
            );

            context.getLogger().log("Processing payment for job: " + jobApprovedEvent.jobId());

            createPaymentRecord(jobApprovedEvent, context);

            updateAccountBalance(jobApprovedEvent, context);

            context.getLogger().log("Payment processed successfully for job: " + jobApprovedEvent.jobId());

            return null;
        } catch (Exception e) {
            context.getLogger().log("Error processing payment: " + e.getMessage());
            throw new RuntimeException("Payment processing failed", e);
        }
    }

    private void createPaymentRecord(JobApprovedEvent event, Context context) {
        String paymentId = UUID.randomUUID().toString();
        String paymentDate = Instant.now().toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("paymentId", AttributeValue.builder().s(paymentId).build());
        item.put("jobId", AttributeValue.builder().s(event.jobId()).build());
        item.put("userId", AttributeValue.builder().s(event.claimerId()).build());
        item.put("amount", AttributeValue.builder().n(event.payAmount().toString()).build());
        item.put("paymentDate", AttributeValue.builder().s(paymentDate).build());
        item.put("jobName", AttributeValue.builder().s(event.jobName()).build());
        item.put("categoryId", AttributeValue.builder().s(event.categoryId()).build());
        item.put("ownerId", AttributeValue.builder().s(event.ownerId()).build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(paymentRecordsTable)
                .item(item)
                .build();

        dynamoDbClient.putItem(request);

        context.getLogger().log("Created payment record: " + paymentId);
    }

    private void updateAccountBalance(JobApprovedEvent event, Context context) {
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(accountsTable)
                .key(Map.of("userId", AttributeValue.builder().s(event.claimerId()).build()))
                .updateExpression("SET balance = balance + :amount")
                .expressionAttributeValues(
                        Map.of(":amount", AttributeValue.builder().n(event.payAmount().toString()).build())
                )
                .build();

        dynamoDbClient.updateItem(request);

        context.getLogger().log("Updated account balance for user: " + event.claimerId());
    }

}