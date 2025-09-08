package com.freelance.payment.notifications;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.payment.events.PaymentCompletedEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

public class PaymentNotificationHandler implements RequestHandler<SNSEvent, Void> {

    private final SesClient sesClient;
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String usersTableName;
    private final String fromEmail;

    public PaymentNotificationHandler() {
        this.sesClient = SesClient.create();
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        this.usersTableName = System.getenv("USERS_TABLE_NAME");
        this.fromEmail = System.getenv("FROM_EMAIL_ADDRESS");
    }

    @Override
    public Void handleRequest(SNSEvent event, Context context) {
        context.getLogger().log("Processing " + event.getRecords().size() + " payment notification(s)");

        for (SNSEvent.SNSRecord record : event.getRecords()) {
            try {
                processNotification(record.getSNS().getMessage(), context);
            } catch (Exception e) {
                context.getLogger().log("Error processing payment notification: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return null;
    }

    private void processNotification(String message, Context context) throws Exception {
        JsonNode eventNode = objectMapper.readTree(message);
        String eventType = eventNode.get("eventType").asText();

        context.getLogger().log("Processing notification for event type: " + eventType);

        if ("payment.completed".equals(eventType)) {
            handlePaymentCompletedNotification(message, context);
        } else {
            context.getLogger().log("Unknown payment event type: " + eventType);
        }
    }

    private void handlePaymentCompletedNotification(String message, Context context) throws Exception {
        PaymentCompletedEvent event = objectMapper.readValue(message, PaymentCompletedEvent.class);

        UserInfo userInfo = getUserInfo(event.userId(), context);
        if (userInfo != null && userInfo.email() != null) {
            String subject = createPaymentCompletedSubject(event);
            String body = createPaymentCompletedBody(event);
            sendEmail(userInfo.email(), userInfo.name(), subject, body, context);
        } else {
            context.getLogger().log("Cannot send notification - user info not available for: " + event.userId());
        }

        // Similarly for owner
        UserInfo ownerInfo = getUserInfo(event.ownerId(), context);
        if (ownerInfo != null && ownerInfo.email() != null) {
            String subject = createPaymentCompletedOwnerSubject(event);
            String body = createPaymentCompletedOwnerBody(event);
            sendEmail(ownerInfo.email(), ownerInfo.name(), subject, body, context);
        }
    }

    private UserInfo getUserInfo(String userId, Context context) { // ← Add Context parameter
        if (usersTableName == null) {
            throw new RuntimeException("USERS_TABLE_NAME environment variable is not set");
        }

        try {
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(usersTableName)
                    .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(getItemRequest);

            if (!response.hasItem()) {
                context.getLogger().log("User not found in table: " + userId);
                return null; // ← Return null instead of throwing exception
            }

            Map<String, AttributeValue> item = response.item();
            String email = getStringValue(item, "email");
            String name = getStringValue(item, "name");

            if (email == null) {
                context.getLogger().log("User found but no email: " + userId);
                return null;
            }

            return new UserInfo(userId, email, name);

        } catch (Exception e) {
            context.getLogger().log("Error retrieving user info: " + e.getMessage());
            return null; // ← Return null instead of throwing
        }
    }

    private void sendEmail(String toEmail, String toName, String subject, String body, Context context) {
        try {
            String displayName = toName != null ? toName : "User";

            SendEmailRequest request = SendEmailRequest.builder()
                    .source(fromEmail != null ? fromEmail : "noreply@freelanceplatform.com")
                    .destination(Destination.builder().toAddresses(toEmail).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).build())
                            .body(Body.builder()
                                    .html(Content.builder().data(createHtmlBody(displayName, body)).build())
                                    .text(Content.builder().data(body).build())
                                    .build())
                            .build())
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            context.getLogger().log("Successfully sent email to " + toEmail + ". MessageId: " + response.messageId());
        } catch (Exception e) {
            context.getLogger().log("Error sending email to " + toEmail + ": " + e.getMessage());
        }
    }

    private String createHtmlBody(String userName, String textBody) {
        return String.format(
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <style>
                        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                        .header { background-color: #4CAF50; color: white; padding: 20px; text-align: center; }
                        .content { padding: 20px; background-color: #f9f9f9; }
                        .footer { text-align: center; padding: 20px; color: #666; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header">
                            <h1>Freelance Platform</h1>
                        </div>
                        <div class="content">
                            <p>Hi %s,</p>
                            <p>%s</p>
                            <p>Best regards,<br>The Freelance Platform Team</p>
                        </div>
                        <div class="footer">
                            <p>This is an automated message. Please do not reply to this email.</p>
                        </div>
                    </div>
                </body>
                </html>
                """,
                userName, textBody.replace("\n", "<br>"));
    }

    // Email subject creators
    private String createPaymentCompletedSubject(PaymentCompletedEvent event) {
        return String.format("💰 Payment Received - $%s for \"%s\"", event.amount(), event.jobName());
    }

    private String createPaymentCompletedOwnerSubject(PaymentCompletedEvent event) {
        return String.format("✅ Payment Processed - $%s for \"%s\"", event.amount(), event.jobName());
    }

    // Email body creators
    private String createPaymentCompletedBody(PaymentCompletedEvent event) {
        StringBuilder body = new StringBuilder();
        body.append("Your payment has been successfully processed and credited to your account!\n\n");
        body.append("Payment Details:\n");
        body.append("• Job Title: ").append(event.jobName()).append("\n");
        body.append("• Amount: $").append(event.amount()).append("\n");
        body.append("• Payment Date: ").append(formatDateTime(event.paymentDate())).append("\n");
        body.append("• Payment ID: ").append(event.paymentId()).append("\n");
        body.append("\nWhat happens next:\n");
        body.append("• The funds are now available in your account\n");
        body.append("• You can withdraw the funds at any time\n");
        body.append("• This payment will appear in your transaction history\n\n");
        body.append("Thank you for using our platform!\n");
        body.append("\nJob ID: ").append(event.jobId());
        return body.toString();
    }

    private String createPaymentCompletedOwnerBody(PaymentCompletedEvent event) {
        StringBuilder body = new StringBuilder();
        body.append("The payment for your job has been successfully processed.\n\n");
        body.append("Payment Details:\n");
        body.append("• Job Title: ").append(event.jobName()).append("\n");
        body.append("• Amount Paid: $").append(event.amount()).append("\n");
        body.append("• Payment Date: ").append(formatDateTime(event.paymentDate())).append("\n");
        body.append("• Payment ID: ").append(event.paymentId()).append("\n");
        body.append("\nThis completes the transaction for this job.\n");
        body.append("Thank you for using our platform!\n");
        body.append("\nJob ID: ").append(event.jobId());
        return body.toString();
    }

    private String formatDateTime(String isoDateTime) {
        try {
            Instant instant = Instant.parse(isoDateTime);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm UTC")
                    .withZone(ZoneId.of("UTC"));
            return formatter.format(instant);
        } catch (Exception e) {
            return isoDateTime;
        }
    }

    private String getStringValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value != null && value.s() != null ? value.s() : null;
    }

    private record UserInfo(String userId, String email, String name) {}
}