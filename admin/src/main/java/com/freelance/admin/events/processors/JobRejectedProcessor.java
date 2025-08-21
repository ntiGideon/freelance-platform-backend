package com.freelance.admin.events.processors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.admin.events.JobRejectedEvent;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Processes job.rejected events and publishes to centralized notification topic
 */
public class JobRejectedProcessor implements RequestHandler<ScheduledEvent, Void> {

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String notificationTopicArn;

    public JobRejectedProcessor() {
        this.snsClient = SnsClient.create();
        this.objectMapper = new ObjectMapper();
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public Void handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Processing job.rejected event from EventBridge");

        try {
            // Parse the job.rejected event from EventBridge detail
            String eventDetailJson = objectMapper.writeValueAsString(event.getDetail());
            JobRejectedEvent jobRejectedEvent = objectMapper.readValue(eventDetailJson, JobRejectedEvent.class);

            context.getLogger().log("Processing job.rejected event for job: " + jobRejectedEvent.jobId());

            // Send event to centralized notification topic
            publishToNotificationTopic(jobRejectedEvent, context);

        } catch (Exception e) {
            context.getLogger().log("Error processing job.rejected event: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    private void publishToNotificationTopic(JobRejectedEvent jobEvent, Context context) {
        try {
            String eventJson = objectMapper.writeValueAsString(jobEvent);

            context.getLogger().log("Publishing job.rejected event to notification topic");

            PublishRequest publishRequest = PublishRequest.builder()
                    .topicArn(notificationTopicArn)
                    .message(eventJson)
                    .build();

            PublishResponse response = snsClient.publish(publishRequest);
            context.getLogger().log("Successfully published to notification topic. MessageId: " + response.messageId());

        } catch (Exception e) {
            context.getLogger().log("Error publishing to notification topic: " + e.getMessage());
            throw new RuntimeException("Failed to publish job rejected notification", e);
        }
    }
}