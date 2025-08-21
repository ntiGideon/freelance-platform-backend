package com.freelance.admin.events.processors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.admin.model.JobCreatedEvent;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Lambda handler for processing job.created events from EventBridge Sends notifications to category
 * subscribers via SNS
 */
public class NotifyJobCategorySubscribersHandler
    implements RequestHandler<ScheduledEvent, Void> {

  private final SnsClient snsClient;
  private final ObjectMapper objectMapper;

  public NotifyJobCategorySubscribersHandler() {
    this.snsClient = SnsClient.create();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context context) {
    context.getLogger().log("Processing EventBridge event: " + event.getSource());

    try {
      String eventDetailJson = objectMapper.writeValueAsString(event.getDetail());
      JobCreatedEvent jobCreatedEvent =
          objectMapper.readValue(eventDetailJson, JobCreatedEvent.class);

      context.getLogger().log("Processing job.created event for job: " + jobCreatedEvent.jobId());

      // Validate that we have an SNS topic to notify
      if (jobCreatedEvent.snsTopicArn() == null || jobCreatedEvent.snsTopicArn().isEmpty()) {
        context
            .getLogger()
            .log("No SNS topic ARN found for category: " + jobCreatedEvent.categoryId());
        return null;
      }

      // Send notification to category subscribers
      sendJobNotification(jobCreatedEvent, context);

    } catch (Exception e) {
      context.getLogger().log("Error processing job.created event: " + e.getMessage());
      e.printStackTrace();
    }

    return null;
  }

  private void sendJobNotification(JobCreatedEvent jobEvent, Context context) {
    try {
      String subject = createNotificationSubject(jobEvent);
      String message = createNotificationMessage(jobEvent);

      context.getLogger().log("Sending notification to topic: " + jobEvent.snsTopicArn());

      // Publish to SNS topic
      PublishRequest publishRequest =
          PublishRequest.builder()
              .topicArn(jobEvent.snsTopicArn())
              .subject(subject)
              .message(message)
              .messageAttributes(
                  Map.of(
                      "categoryId",
                          software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                              .dataType("String")
                              .stringValue(jobEvent.categoryId())
                              .build(),
                      "payAmount",
                          software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                              .dataType("Number")
                              .stringValue(jobEvent.payAmount().toString())
                              .build(),
                      "jobId",
                          software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                              .dataType("String")
                              .stringValue(jobEvent.jobId())
                              .build()))
              .build();

      PublishResponse response = snsClient.publish(publishRequest);

      context.getLogger().log("Successfully sent notification. MessageId: " + response.messageId());

    } catch (Exception e) {
      context.getLogger().log("Error sending SNS notification: " + e.getMessage());
      throw new RuntimeException("Failed to send job notification", e);
    }
  }

  private String createNotificationSubject(JobCreatedEvent jobEvent) {
    return String.format(
        "New %s job available - $%s",
        jobEvent.categoryName() != null ? jobEvent.categoryName() : "Job",
        jobEvent.payAmount().toString());
  }

  private String createNotificationMessage(JobCreatedEvent jobEvent) {
    StringBuilder message = new StringBuilder();

    message.append("🚀 New Job Posted!\n\n");

    message.append("📋 Title: ").append(jobEvent.name()).append("\n");

    if (jobEvent.categoryName() != null) {
      message.append("🏷️ Category: ").append(jobEvent.categoryName()).append("\n");
    }

    message.append("💰 Pay: $").append(jobEvent.payAmount()).append("\n");

    if (jobEvent.timeToCompleteSeconds() != null) {
      message
          .append("⏱️ Time to Complete: ")
          .append(formatDuration(jobEvent.timeToCompleteSeconds()))
          .append("\n");
    }

    if (jobEvent.expiryDate() != null) {
      message.append("📅 Expires: ").append(formatDateTime(jobEvent.expiryDate())).append("\n");
    }

    message.append("\n📝 Description:\n").append(jobEvent.description()).append("\n\n");

    // Include direct link to the job
    if (jobEvent.jobUrl() != null && !jobEvent.jobUrl().isEmpty()) {
      message.append("🔗 View Job: ").append(jobEvent.jobUrl()).append("\n\n");
    }
    
    message.append("Job ID: ").append(jobEvent.jobId()).append("\n");
    message.append("Apply now through the Freelance Platform!");

    return message.toString();
  }

  private String formatDuration(Long seconds) {
    if (seconds == null) return "Not specified";

    long days = seconds / (24 * 3600);
    long hours = (seconds % (24 * 3600)) / 3600;
    long minutes = (seconds % 3600) / 60;

    StringBuilder duration = new StringBuilder();

    if (days > 0) {
      duration.append(days).append(" day").append(days > 1 ? "s" : "");
    }

    if (hours > 0) {
      if (duration.length() > 0) duration.append(", ");
      duration.append(hours).append(" hour").append(hours > 1 ? "s" : "");
    }

    if (minutes > 0 && days == 0) {
      if (duration.length() > 0) duration.append(", ");
      duration.append(minutes).append(" minute").append(minutes > 1 ? "s" : "");
    }

    return duration.length() > 0 ? duration.toString() : "Less than a minute";
  }

  private String formatDateTime(String isoDateTime) {
    try {
      Instant instant = Instant.parse(isoDateTime);
      DateTimeFormatter formatter =
          DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm UTC").withZone(ZoneId.of("UTC"));
      return formatter.format(instant);
    } catch (Exception e) {
      return isoDateTime;
    }
  }
}

