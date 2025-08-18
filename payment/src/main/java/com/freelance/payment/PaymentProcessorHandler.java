package com.freelance.payment;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.payment.events.PaymentCompletedEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PaymentProcessorHandler implements RequestHandler<ScheduledEvent, Void> {

    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String paymentRecordsTable;
    private final String accountsTable;
    private final String notificationTopicArn;

    public PaymentProcessorHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.snsClient = SnsClient.create();
        this.objectMapper = new ObjectMapper();
        this.paymentRecordsTable = System.getenv("PAYMENT_RECORDS_TABLE");
        this.accountsTable = System.getenv("ACCOUNTS_TABLE");
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        try {
            JobApprovedEvent jobApprovedEvent = objectMapper.readValue(
                    (JsonParser) event.getDetail(),
                    JobApprovedEvent.class
            );

            // Process payment
            String paymentId = createPaymentRecord(jobApprovedEvent, context);
            updateAccountBalance(jobApprovedEvent, context);

            // Send payment notification
            sendPaymentNotification(paymentId, jobApprovedEvent, context);

            context.getLogger().log("Payment processed successfully for job: " + jobApprovedEvent.jobId());
            return null;
        } catch (Exception e) {
            context.getLogger().log("Error processing payment: " + e.getMessage());
            throw new RuntimeException("Payment processing failed", e);
        }
    }


    private String createPaymentRecord(JobApprovedEvent event, Context context) {
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

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(paymentRecordsTable)
                .item(item)
                .build());

        context.getLogger().log("Created payment record: " + paymentId);
        return paymentId;
    }

    private void updateAccountBalance(JobApprovedEvent event, Context context) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(accountsTable)
                .key(Map.of("userId", AttributeValue.builder().s(event.claimerId()).build()))
                .updateExpression("SET balance = balance + :amount")
                .expressionAttributeValues(
                        Map.of(":amount", AttributeValue.builder().n(event.payAmount().toString()).build())
                )
                .build());
    }

    private void sendPaymentNotification(String paymentId, JobApprovedEvent event, Context context) {
        try {
            PaymentCompletedEvent paymentEvent = new PaymentCompletedEvent(
                    paymentId,
                    event.jobId(),
                    event.jobName(),
                    event.claimerId(),
                    event.payAmount(),
                    Instant.now().toString(),
                    event.ownerId()
            );

            String eventJson = objectMapper.writeValueAsString(paymentEvent);

            snsClient.publish(PublishRequest.builder()
                    .topicArn(notificationTopicArn)
                    .message(eventJson)
                    .build());

            context.getLogger().log("Payment notification sent for job: " + event.jobId());
        } catch (Exception e) {
            context.getLogger().log("Failed to send payment notification: " + e.getMessage());
        }
    }
}