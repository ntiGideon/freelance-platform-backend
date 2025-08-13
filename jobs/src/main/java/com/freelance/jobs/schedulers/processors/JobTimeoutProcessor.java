package com.freelance.jobs.schedulers.processors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.events.JobTimedOutEvent;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Lambda function that processes individual timed out jobs from SQS queue
 * Sends notifications via SNS to both job owner and claimer
 * Architecture: SQS -> JobTimeoutProcessor -> SNS -> JobNotificationHandler
 */
public class JobTimeoutProcessor implements RequestHandler<SQSEvent, String> {

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String notificationTopicArn;

    public JobTimeoutProcessor() {
        this.snsClient = SnsClient.create();
        this.objectMapper = new ObjectMapper();
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public String handleRequest(SQSEvent input, Context context) {
        context.getLogger().log("Processing " + input.getRecords().size() + " timed out job(s)");
        
        int processed = 0;
        int failed = 0;
        
        for (SQSEvent.SQSMessage record : input.getRecords()) {
            try {
                processTimedOutJob(record.getBody(), context);
                processed++;
            } catch (Exception e) {
                context.getLogger().log("Error processing timed out job: " + e.getMessage());
                e.printStackTrace();
                failed++;
                // Continue processing other messages even if one fails
            }
        }
        
        String result = String.format("Processed %d timed out jobs, %d failed", processed, failed);
        context.getLogger().log(result);
        return result;
    }

    private void processTimedOutJob(String messageBody, Context context) throws Exception {
        // Parse the timed out job event from SQS message
        JobTimedOutEvent timedOutEvent = objectMapper.readValue(messageBody, JobTimedOutEvent.class);
        
        context.getLogger().log("Processing timed out job: " + timedOutEvent.jobId() + 
                               " (owner: " + timedOutEvent.ownerId() + 
                               ", claimer: " + timedOutEvent.claimerId() + ")");

        // Send notification via SNS to the centralized notification system
        sendTimeoutNotification(timedOutEvent, context);
        
        context.getLogger().log("Successfully processed timed out job: " + timedOutEvent.jobId());
    }

    private void sendTimeoutNotification(JobTimedOutEvent timedOutEvent, Context context) throws Exception {
        try {
            // Convert to JSON for SNS message
            String eventJson = objectMapper.writeValueAsString(timedOutEvent);

            PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(notificationTopicArn)
                    .message(eventJson)
                    .subject("Job Timed Out: " + timedOutEvent.jobName())
                    .messageAttributes(java.util.Map.of(
                            "eventType", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(timedOutEvent.eventType())
                                    .build(),
                            "jobId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(timedOutEvent.jobId())
                                    .build(),
                            "ownerId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(timedOutEvent.ownerId())
                                    .build(),
                            "claimerId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(timedOutEvent.claimerId())
                                    .build()
                    ))
                    .build();

            snsClient.publish(publishRequest);
            
            context.getLogger().log("Sent timeout notification for job: " + timedOutEvent.jobId());
            
        } catch (Exception e) {
            context.getLogger().log("Error sending timeout notification: " + e.getMessage());
            throw new RuntimeException("Failed to send timeout notification", e);
        }
    }
}