package com.freelance.jobs.notifications;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.events.JobApprovedEvent;
import com.freelance.jobs.events.JobClaimedEvent;
import com.freelance.jobs.events.JobExpiredEvent;
import com.freelance.jobs.events.JobRejectedEvent;
import com.freelance.jobs.events.JobSubmittedEvent;
import com.freelance.jobs.events.JobTimedOutEvent;
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

/**
 * Job notification handler that processes all job events from the central SNS topic and sends
 * targeted notifications via SES email
 */
public class JobNotificationHandler implements RequestHandler<SNSEvent, Void> {

  private final SesClient sesClient;
  private final DynamoDbClient dynamoDbClient;
  private final ObjectMapper objectMapper;
  private final String usersTableName;
  private final String fromEmail;

  public JobNotificationHandler() {
    this.sesClient = SesClient.create();
    this.dynamoDbClient = DynamoDbClient.create();
    this.objectMapper = new ObjectMapper();
    this.usersTableName = System.getenv("USERS_TABLE_NAME");
    this.fromEmail = System.getenv("FROM_EMAIL_ADDRESS");
  }

  @Override
  public Void handleRequest(SNSEvent event, Context context) {
    context.getLogger().log("Processing " + event.getRecords().size() + " SNS notification(s)");

    for (SNSEvent.SNSRecord record : event.getRecords()) {
      try {
        processNotification(record.getSNS().getMessage(), context);
      } catch (Exception e) {
        context.getLogger().log("Error processing notification: " + e.getMessage());
        e.printStackTrace();
      }
    }

    return null;
  }

  private void processNotification(String message, Context context) throws Exception {
    JsonNode eventNode = objectMapper.readTree(message);
    String eventType = eventNode.get("eventType").asText();

    context.getLogger().log("Processing notification for event type: " + eventType);

    switch (eventType) {
      case "job.claimed":
        handleJobClaimedNotification(message, context);
        break;
      case "job.submitted":
        handleJobSubmittedNotification(message, context);
        break;
      case "job.approved":
        handleJobApprovedNotification(message, context);
        break;
      case "job.rejected":
        handleJobRejectedNotification(message, context);
        break;
      case "job.expired":
        handleJobExpiredNotification(message, context);
        break;
      case "job.timedout":
        handleJobTimedOutNotification(message, context);
        break;
      default:
        context.getLogger().log("Unknown event type: " + eventType);
    }
  }

  private void handleJobClaimedNotification(String message, Context context) throws Exception {
    JobClaimedEvent event = objectMapper.readValue(message, JobClaimedEvent.class);

    // Send notification to job owner
    UserInfo ownerInfo = getUserInfo(event.ownerId());
    if (ownerInfo != null && ownerInfo.email() != null) {
      String subject = createJobClaimedSubject(event);
      String body = createJobClaimedBody(event);
      sendEmail(ownerInfo.email(), ownerInfo.name(), subject, body, context);
    }
  }

  private void handleJobSubmittedNotification(String message, Context context) throws Exception {
    JobSubmittedEvent event = objectMapper.readValue(message, JobSubmittedEvent.class);

    // Send notification to job owner
    UserInfo ownerInfo = getUserInfo(event.ownerId());
    if (ownerInfo != null && ownerInfo.email() != null) {
      String subject = createJobSubmittedSubject(event);
      String body = createJobSubmittedBody(event);
      sendEmail(ownerInfo.email(), ownerInfo.name(), subject, body, context);
    }
  }

  private void handleJobApprovedNotification(String message, Context context) throws Exception {
    JobApprovedEvent event = objectMapper.readValue(message, JobApprovedEvent.class);

    // Send notification to job seeker
    UserInfo seekerInfo = getUserInfo(event.claimerId());
    if (seekerInfo != null && seekerInfo.email() != null) {
      String subject = createJobApprovedSubject(event);
      String body = createJobApprovedBody(event);
      sendEmail(seekerInfo.email(), seekerInfo.name(), subject, body, context);
    }
  }

  private void handleJobRejectedNotification(String message, Context context) throws Exception {
    JobRejectedEvent event = objectMapper.readValue(message, JobRejectedEvent.class);

    // Send notification to job seeker
    UserInfo seekerInfo = getUserInfo(event.claimerId());
    if (seekerInfo != null && seekerInfo.email() != null) {
      String subject = createJobRejectedSubject(event);
      String body = createJobRejectedBody(event);
      sendEmail(seekerInfo.email(), seekerInfo.name(), subject, body, context);
    }
  }

  private void handleJobExpiredNotification(String message, Context context) throws Exception {
    JobExpiredEvent event = objectMapper.readValue(message, JobExpiredEvent.class);

    // Send notification to job owner
    UserInfo ownerInfo = getUserInfo(event.ownerId());
    if (ownerInfo != null && ownerInfo.email() != null) {
      String subject = createJobExpiredSubject(event);
      String body = createJobExpiredBody(event);
      sendEmail(ownerInfo.email(), ownerInfo.name(), subject, body, context);
    }
  }

  private void handleJobTimedOutNotification(String message, Context context) throws Exception {
    JobTimedOutEvent event = objectMapper.readValue(message, JobTimedOutEvent.class);

    // Send notification to job owner
    UserInfo ownerInfo = getUserInfo(event.ownerId());
    if (ownerInfo != null && ownerInfo.email() != null) {
      String ownerSubject = createJobTimedOutOwnerSubject(event);
      String ownerBody = createJobTimedOutOwnerBody(event);
      sendEmail(ownerInfo.email(), ownerInfo.name(), ownerSubject, ownerBody, context);
    }

    // Send notification to claimer (the freelancer who timed out)
    UserInfo claimerInfo = getUserInfo(event.claimerId());
    if (claimerInfo != null && claimerInfo.email() != null) {
      String claimerSubject = createJobTimedOutClaimerSubject(event);
      String claimerBody = createJobTimedOutClaimerBody(event);
      sendEmail(claimerInfo.email(), claimerInfo.name(), claimerSubject, claimerBody, context);
    }
  }

  private UserInfo getUserInfo(String userId) {
    if (usersTableName == null) {
      throw new RuntimeException("USERS_TABLE_NAME environment variable is not set");
    }

    try {
      GetItemRequest getItemRequest =
          GetItemRequest.builder()
              .tableName(usersTableName)
              .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
              .build();

      GetItemResponse response = dynamoDbClient.getItem(getItemRequest);
      if (response.hasItem()) {
        Map<String, AttributeValue> item = response.item();
        String email = getStringValue(item, "email");
        String name = getStringValue(item, "name");
        
        if (email == null) {
          throw new RuntimeException("User " + userId + " has no email address");
        }
        
        return new UserInfo(userId, email, name);
      } else {
        throw new RuntimeException("User not found: " + userId);
      }

    } catch (Exception e) {
      throw new RuntimeException("Failed to retrieve user info for " + userId + ": " + e.getMessage(), e);
    }
  }

  private void sendEmail(
      String toEmail, String toName, String subject, String body, Context context) {
    try {
      String displayName = toName != null ? toName : "User";

      SendEmailRequest request =
          SendEmailRequest.builder()
              .source(fromEmail != null ? fromEmail : "noreply@freelanceplatform.com")
              .destination(Destination.builder().toAddresses(toEmail).build())
              .message(
                  Message.builder()
                      .subject(Content.builder().data(subject).build())
                      .body(
                          Body.builder()
                              .html(
                                  Content.builder().data(createHtmlBody(displayName, body)).build())
                              .text(Content.builder().data(body).build())
                              .build())
                      .build())
              .build();

      SendEmailResponse response = sesClient.sendEmail(request);
      context
          .getLogger()
          .log("Successfully sent email to " + toEmail + ". MessageId: " + response.messageId());

    } catch (Exception e) {
      context.getLogger().log("Error sending email to " + toEmail + ": " + e.getMessage());
      // Don't throw exception - log and continue
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
  private String createJobClaimedSubject(JobClaimedEvent event) {
    return String.format("🎯 Your job \"%s\" has been claimed!", event.jobName());
  }

  private String createJobSubmittedSubject(JobSubmittedEvent event) {
    return String.format("✅ Job \"%s\" submitted for review", event.jobName());
  }

  private String createJobApprovedSubject(JobApprovedEvent event) {
    return String.format("🎉 Job \"%s\" approved - $%s", event.jobName(), event.payAmount());
  }

  private String createJobRejectedSubject(JobRejectedEvent event) {
    return String.format("📝 Job \"%s\" needs revision", event.jobName());
  }

  private String createJobExpiredSubject(JobExpiredEvent event) {
    return String.format("⏰ Job \"%s\" has expired", event.jobName());
  }

  private String createJobTimedOutOwnerSubject(JobTimedOutEvent event) {
    return String.format("⏱️ Job \"%s\" timed out - now available again", event.jobName());
  }

  private String createJobTimedOutClaimerSubject(JobTimedOutEvent event) {
    return String.format("⏱️ Your deadline for \"%s\" has passed", event.jobName());
  }

  // Email body creators
  private String createJobClaimedBody(JobClaimedEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("Great news! Your job has been claimed by a freelancer.\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Claimed at: ").append(formatDateTime(event.claimedAt())).append("\n");
    if (event.submissionDeadline() != null) {
      body.append("• Submission deadline: ")
          .append(formatDateTime(event.submissionDeadline()))
          .append("\n");
    }
    body.append("\nWhat happens next:\n");
    body.append("• The freelancer is now working on your job\n");
    body.append("• You'll be notified when they submit their work\n");
    body.append("• You can then review and approve/reject the submission\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }

  private String createJobSubmittedBody(JobSubmittedEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("Your job has been completed and submitted for review!\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Submitted at: ").append(formatDateTime(event.submittedAt())).append("\n");
    body.append("\nAction Required:\n");
    body.append("Please review the submitted work and either:\n");
    body.append("• APPROVE if the work meets your requirements\n");
    body.append("• REJECT if revisions are needed\n\n");
    body.append("Note: Payment will be handled separately after approval.\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }

  private String createJobApprovedBody(JobApprovedEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("Congratulations! Your work has been approved!\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Approved at: ").append(formatDateTime(event.approvedAt())).append("\n");
    body.append("\nNext Steps:\n");
    body.append("• The job owner has accepted your work\n");
    body.append("• Payment processing will be handled separately\n");
    body.append("• You can view this completed job in your history\n\n");
    body.append("Great work! Keep up the excellent service.\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }

  private String createJobRejectedBody(JobRejectedEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("Your job submission needs revision.\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Rejected at: ").append(formatDateTime(event.rejectedAt())).append("\n");
    if (event.rejectionReason() != null && !event.rejectionReason().isEmpty()) {
      body.append("\nFeedback from client:\n");
      body.append("\"").append(event.rejectionReason()).append("\"\n");
    }
    body.append("\nWhat happens next:\n");
    body.append("• The job is now open again for anyone to claim\n");
    body.append("• You can re-claim and work on it if you'd like\n");
    body.append("• Other freelancers can also claim this job\n\n");
    body.append("Tips for success:\n");
    body.append("• Carefully review the job requirements\n");
    body.append("• Consider the client's feedback for future submissions\n");
    body.append("• Communicate with clients if requirements are unclear\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }

  private String createJobExpiredBody(JobExpiredEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("Your job has expired and is no longer available for freelancers to claim.\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Original expiry date: ").append(formatDateTime(event.expiryDate())).append("\n");
    body.append("• Status when expired: ").append(getStatusDescription(event.originalStatus())).append("\n");
    body.append("• Expired at: ").append(formatDateTime(event.expiredAt())).append("\n");
    body.append("\nWhat happens next:\n");
    body.append("• The job is now marked as expired\n");
    body.append("• No freelancers can claim or work on this job\n");
    body.append("• You can create a new job if you still need this work done\n\n");
    body.append("Options:\n");
    body.append("• Post a similar job with updated requirements\n");
    body.append("• Extend the deadline for future jobs to get more responses\n");
    body.append("• Consider adjusting the budget or requirements to attract freelancers\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }
  
  private String createJobTimedOutOwnerBody(JobTimedOutEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("A freelancer's time to complete your job has expired, and the job is now available for others to claim.\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Time allowed: ").append(formatDuration(event.timeToCompleteSeconds())).append("\n");
    body.append("• Claimed at: ").append(formatDateTime(event.claimedAt())).append("\n");
    body.append("• Deadline was: ").append(formatDateTime(event.submissionDeadline())).append("\n");
    body.append("• Timed out at: ").append(formatDateTime(event.timedOutAt())).append("\n");
    body.append("\nWhat happened:\n");
    body.append("• The freelancer did not submit their work before the deadline\n");
    body.append("• The job has been automatically reopened for new claims\n");
    body.append("• Other freelancers can now claim and work on this job\n\n");
    body.append("Next steps:\n");
    body.append("• Wait for another freelancer to claim the job\n");
    body.append("• Consider extending the time limit if the work is complex\n");
    body.append("• Review if the requirements are clear and achievable\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }

  private String createJobTimedOutClaimerBody(JobTimedOutEvent event) {
    StringBuilder body = new StringBuilder();
    body.append("Your deadline to complete this job has passed, and you can no longer submit work for it.\n\n");
    body.append("Job Details:\n");
    body.append("• Title: ").append(event.jobName()).append("\n");
    body.append("• Amount: $").append(event.payAmount()).append("\n");
    body.append("• Time allowed: ").append(formatDuration(event.timeToCompleteSeconds())).append("\n");
    body.append("• You claimed it at: ").append(formatDateTime(event.claimedAt())).append("\n");
    body.append("• Deadline was: ").append(formatDateTime(event.submissionDeadline())).append("\n");
    body.append("• Timed out at: ").append(formatDateTime(event.timedOutAt())).append("\n");
    body.append("\nWhat happened:\n");
    body.append("• Your time to complete the work has expired\n");
    body.append("• The job is now available for other freelancers to claim\n");
    body.append("• You cannot submit work for this job anymore\n\n");
    body.append("For future success:\n");
    body.append("• Only claim jobs you can complete within the time limit\n");
    body.append("• Communicate with the job owner if you need clarification\n");
    body.append("• Submit work before the deadline, even if it's not perfect\n");
    body.append("• Consider the time needed before claiming complex jobs\n");
    body.append("\nJob ID: ").append(event.jobId());
    return body.toString();
  }

  private String getStatusDescription(String status) {
    return switch (status != null ? status.toLowerCase() : "unknown") {
      case "open" -> "Open (No freelancer had claimed it)";
      case "claimed" -> "Claimed (A freelancer was working on it)";
      case "submitted" -> "Submitted (Work was submitted, pending your review)";
      default -> "Unknown status";
    };
  }
  
  private String formatDuration(Long seconds) {
    if (seconds == null) return "Unknown";
    
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long secs = seconds % 60;
    
    if (hours > 0) {
      return String.format("%d hours, %d minutes", hours, minutes);
    } else if (minutes > 0) {
      return String.format("%d minutes", minutes);
    } else {
      return String.format("%d seconds", secs);
    }
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

  private String getStringValue(Map<String, AttributeValue> item, String key) {
    AttributeValue value = item.get(key);
    return value != null && value.s() != null ? value.s() : null;
  }

  private record UserInfo(String userId, String email, String name) {}
}
