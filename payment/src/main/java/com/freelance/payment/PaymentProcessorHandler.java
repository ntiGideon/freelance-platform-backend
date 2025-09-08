package com.freelance.payment;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.payment.events.PaymentCompletedEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

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
            // Serialize the event detail to JSON string and deserialize to JobApprovedEvent
            String eventDetailJson = objectMapper.writeValueAsString(event.getDetail());
            JobApprovedEvent jobApprovedEvent = objectMapper.readValue(eventDetailJson, JobApprovedEvent.class);

            // Validate event
            validateJobApprovedEvent(jobApprovedEvent);

            // Process payment
            String paymentId = createPaymentRecord(jobApprovedEvent, context);
            updateAccountBalance(jobApprovedEvent, context);

            // Send payment notification
            sendPaymentNotification(paymentId, jobApprovedEvent, context);

            context.getLogger().log(String.format(
                    "{\"event\":\"PaymentProcessed\",\"jobId\":\"%s\",\"paymentId\":\"%s\",\"amount\":\"%s\"}",
                    jobApprovedEvent.jobId(), paymentId, jobApprovedEvent.payAmount()));
            return null;
        } catch (Exception e) {
            context.getLogger().log(String.format(
                    "{\"event\":\"PaymentProcessingError\",\"error\":\"%s\",\"stackTrace\":\"%s\"}",
                    e.getMessage(), Arrays.toString(e.getStackTrace())));
            throw new RuntimeException("Payment processing failed", e);
        }
    }

    private void validateJobApprovedEvent(JobApprovedEvent event) {
        Objects.requireNonNull(event, "JobApprovedEvent cannot be null");
        Objects.requireNonNull(event.jobId(), "jobId cannot be null");
        Objects.requireNonNull(event.claimerId(), "claimerId cannot be null");
        Objects.requireNonNull(event.ownerId(), "ownerId cannot be null");
        Objects.requireNonNull(event.jobName(), "jobName cannot be null");
        Objects.requireNonNull(event.categoryId(), "categoryId cannot be null");
        Objects.requireNonNull(event.payAmount(), "payAmount cannot be null");
        if (event.payAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("payAmount must be positive");
        }
        if (event.jobId().isBlank() || event.claimerId().isBlank() || event.ownerId().isBlank() ||
                event.jobName().isBlank() || event.categoryId().isBlank()) {
            throw new IllegalArgumentException("Event fields cannot be empty");
        }
    }

    private String createPaymentRecord(JobApprovedEvent event, Context context) {
        // Validate environment variable
        if (paymentRecordsTable == null || paymentRecordsTable.isBlank()) {
            context.getLogger().log("{\"event\":\"PaymentRecordCreationError\",\"error\":\"PAYMENT_RECORDS_TABLE environment variable is not set\"}");
            throw new IllegalStateException("PAYMENT_RECORDS_TABLE environment variable is not set");
        }

        // Check for idempotency
        if (paymentExists(event.jobId())) {
            context.getLogger().log(String.format(
                    "{\"event\":\"PaymentRecordExists\",\"jobId\":\"%s\"}", event.jobId()));
            return event.jobId(); // Or retrieve existing paymentId
        }

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

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(paymentRecordsTable)
                    .item(item)
                    .conditionExpression("attribute_not_exists(paymentId)")
                    .build());
            context.getLogger().log(String.format(
                    "{\"event\":\"PaymentRecordCreated\",\"paymentId\":\"%s\",\"jobId\":\"%s\",\"amount\":\"%s\"}",
                    paymentId, event.jobId(), event.payAmount()));
        } catch (Exception e) {
            context.getLogger().log(String.format(
                    "{\"event\":\"PaymentRecordCreationError\",\"jobId\":\"%s\",\"error\":\"%s\"}",
                    event.jobId(), e.getMessage()));
            throw new RuntimeException("Failed to create payment record for job: " + event.jobId(), e);
        }

        return paymentId;
    }

    private boolean paymentExists(String jobId) {
        try {
            GetItemRequest request = GetItemRequest.builder()
                    .tableName(paymentRecordsTable)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .build();
            return dynamoDbClient.getItem(request).hasItem();
        } catch (Exception e) {
            throw new RuntimeException("Failed to check payment existence for job: " + jobId, e);
        }
    }

    private void updateAccountBalance(JobApprovedEvent event, Context context) {
        if (accountsTable == null || accountsTable.isBlank()) {
            context.getLogger().log("{\"event\":\"AccountUpdateError\",\"error\":\"ACCOUNTS_TABLE environment variable is not set\"}");
            throw new IllegalStateException("ACCOUNTS_TABLE environment variable is not set");
        }

        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(accountsTable)
                    .key(Map.of("userId", AttributeValue.builder().s(event.claimerId()).build()))
                    .updateExpression("SET balance = balance + :amount")
                    .expressionAttributeValues(
                            Map.of(":amount", AttributeValue.builder().n(event.payAmount().toString()).build())
                    )
                    .build());
            context.getLogger().log(String.format(
                    "{\"event\":\"AccountBalanceUpdated\",\"userId\":\"%s\",\"amount\":\"%s\"}",
                    event.claimerId(), event.payAmount()));
        } catch (Exception e) {
            context.getLogger().log(String.format(
                    "{\"event\":\"AccountUpdateError\",\"userId\":\"%s\",\"error\":\"%s\"}",
                    event.claimerId(), e.getMessage()));
            throw new RuntimeException("Failed to update account balance for user: " + event.claimerId(), e);
        }
    }

    private void sendPaymentNotification(String paymentId, JobApprovedEvent event, Context context) {
        if (notificationTopicArn == null || notificationTopicArn.isBlank()) {
            context.getLogger().log("{\"event\":\"NotificationError\",\"error\":\"NOTIFICATION_TOPIC_ARN environment variable is not set\"}");
            throw new IllegalStateException("NOTIFICATION_TOPIC_ARN environment variable is not set");
        }

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

            context.getLogger().log(String.format(
                    "{\"event\":\"PaymentNotificationSent\",\"jobId\":\"%s\",\"paymentId\":\"%s\"}",
                    event.jobId(), paymentId));
        } catch (Exception e) {
            context.getLogger().log(String.format(
                    "{\"event\":\"NotificationError\",\"jobId\":\"%s\",\"error\":\"%s\"}",
                    event.jobId(), e.getMessage()));
            throw new RuntimeException("Failed to send payment notification for job: " + event.jobId(), e);
        }
    }
}