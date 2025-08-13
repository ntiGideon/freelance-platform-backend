package com.freelance.jobs.schedulers.processors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.events.JobExpiredEvent;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Lambda function that processes individual expired jobs from SQS queue
 * Sends notifications via SNS to the centralized notification system
 * Architecture: SQS -> JobExpiryProcessor -> SNS -> JobNotificationHandler
 */
public class JobExpiryProcessor implements RequestHandler<SQSEvent, String> {

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String notificationTopicArn;

    public JobExpiryProcessor() {
        this.snsClient = SnsClient.create();
        this.objectMapper = new ObjectMapper();
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public String handleRequest(SQSEvent input, Context context) {
        context.getLogger().log("Processing " + input.getRecords().size() + " expired job(s)");
        
        int processed = 0;
        int failed = 0;
        
        for (SQSEvent.SQSMessage record : input.getRecords()) {
            try {
                processExpiredJob(record.getBody(), context);
                processed++;
            } catch (Exception e) {
                context.getLogger().log("Error processing expired job: " + e.getMessage());
                e.printStackTrace();
                failed++;
                // Continue processing other messages even if one fails
            }
        }
        
        String result = String.format("Processed %d expired jobs, %d failed", processed, failed);
        context.getLogger().log(result);
        return result;
    }

    private void processExpiredJob(String messageBody, Context context) throws Exception {
        // Parse the expired job event from SQS message
        JobExpiredEvent expiredEvent = objectMapper.readValue(messageBody, JobExpiredEvent.class);
        
        context.getLogger().log("Processing expired job: " + expiredEvent.jobId() + 
                               " (owner: " + expiredEvent.ownerId() + ")");

        // Send notification via SNS to the centralized notification system
        sendExpiryNotification(expiredEvent, context);
        
        context.getLogger().log("Successfully processed expired job: " + expiredEvent.jobId());
    }

    private void sendExpiryNotification(JobExpiredEvent expiredEvent, Context context) throws Exception {
        try {
            // Convert to JSON for SNS message
            String eventJson = objectMapper.writeValueAsString(expiredEvent);

            PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(notificationTopicArn)
                    .message(eventJson)
                    .subject("Job Expired: " + expiredEvent.jobName())
                    .messageAttributes(java.util.Map.of(
                            "eventType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(expiredEvent.eventType())
                                    .build(),
                            "jobId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(expiredEvent.jobId())
                                    .build(),
                            "ownerId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(expiredEvent.ownerId())
                                    .build()
                    ))
                    .build();

            snsClient.publish(publishRequest);
            
            context.getLogger().log("Sent expiry notification for job: " + expiredEvent.jobId());
            
        } catch (Exception e) {
            context.getLogger().log("Error sending expiry notification: " + e.getMessage());
            throw new RuntimeException("Failed to send expiry notification", e);
        }
    }
}