package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.admin.auth.AdminAuthUtils;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.mappers.JobEntityMapper;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Handler for GET /admin/jobs/{jobId}
 * Returns detailed view of any job (Admin only)
 */
public class ViewJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;

    public ViewJobHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String jobId = RequestMapper.extractJobIdFromPath(input.getPath());
            String userId = RequestMapper.extractUserIdFromRequestContext(input, "admin");

            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }

            context.getLogger().log("Admin " + userId + " viewing job details for jobId: " + jobId);

            JobEntity job = getJobById(jobId, context);
            if (job == null) {
                return ResponseUtil.createErrorResponse(404, "Job not found");
            }

            // Create enhanced job view with additional metadata
            Map<String, Object> jobView = createEnhancedJobView(job);

            return ResponseUtil.createSuccessResponse(200, jobView);

        } catch (Exception e) {
            context.getLogger().log("Error getting job details: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private Map<String, Object> createEnhancedJobView(JobEntity job) {
        Map<String, Object> view = new HashMap<>();

        // Add base job fields
        view.put("jobId", job.jobId());
        view.put("ownerId", job.ownerId());
        view.put("categoryId", job.categoryId());
        view.put("name", job.name());
        view.put("description", job.description());
        view.put("payAmount", job.payAmount());
        view.put("timeToCompleteSeconds", job.timeToCompleteSeconds());
        view.put("expiryDate", job.expiryDate());
        view.put("status", job.status());
        view.put("createdAt", job.createdAt());
        view.put("updatedAt", job.updatedAt());
        view.put("claimerId", job.claimerId() != null ? job.claimerId() : "");
        view.put("claimedAt", job.claimedAt() != null ? job.claimedAt() : "");
        view.put("submissionDeadline", job.submissionDeadline() != null ? job.submissionDeadline() : "");

        // Add status-specific metadata
        if ("open".equals(job.status())) {
            view.put("isExpired", isJobExpired(job));
            view.put("timeUntilExpiry", getTimeUntilExpiry(job));
        } else if ("claimed".equals(job.status()) && job.submissionDeadline() != null) {
            view.put("timeRemaining", getTimeRemainingForSubmission(job));
            view.put("workInProgress", true);
        } else if ("submitted".equals(job.status())) {
            view.put("awaitingReview", true);
            view.put("actions", Map.of("approve", true, "reject", true));
        }

        // Add admin-specific metadata
        view.put("age", getJobAge(job));
        view.put("isExpired", isJobExpired(job));
        view.put("canBeRelisted", canJobBeRelisted(job));
        view.put("adminActions", getAdminActions(job));

        return view;
    }

    private boolean isJobExpired(JobEntity job) {
        try {
            Instant expiryDate = Instant.parse(job.expiryDate());
            return expiryDate.isBefore(Instant.now());
        } catch (Exception e) {
            return true; // Treat invalid dates as expired
        }
    }

    private String getTimeUntilExpiry(JobEntity job) {
        try {
            Instant expiryDate = Instant.parse(job.expiryDate());
            Instant now = Instant.now();

            if (expiryDate.isBefore(now)) {
                return "Expired";
            }

            Duration duration = Duration.between(now, expiryDate);
            return formatDuration(duration);
        } catch (Exception e) {
            return "Invalid expiry date";
        }
    }

    private String getTimeRemainingForSubmission(JobEntity job) {
        try {
            if (job.submissionDeadline() == null) {
                return "No deadline set";
            }

            Instant deadline = Instant.parse(job.submissionDeadline());
            Instant now = Instant.now();

            if (deadline.isBefore(now)) {
                return "Deadline passed";
            }

            Duration duration = Duration.between(now, deadline);
            return formatDuration(duration);
        } catch (Exception e) {
            return "Invalid deadline";
        }
    }

    private String getJobAge(JobEntity job) {
        try {
            Instant createdAt = Instant.parse(job.createdAt());
            Instant now = Instant.now();
            Duration duration = Duration.between(createdAt, now);
            return formatDuration(duration);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private boolean canJobBeRelisted(JobEntity job) {
        boolean isExpired = isJobExpired(job);

        return ("open".equals(job.status()) && isExpired) ||
                "approved_as_completed".equals(job.status()) ||
                "rejected".equals(job.status()) ||
                ("claimed".equals(job.status()) && isExpired) ||
                ("submitted".equals(job.status()) && isExpired) ||
                "cancelled".equals(job.status());
    }

    private Map<String, Boolean> getAdminActions(JobEntity job) {
        Map<String, Boolean> actions = new HashMap<>();

        actions.put("canApprove", "submitted".equals(job.status()));
        actions.put("canReject", "submitted".equals(job.status()));
        actions.put("canRelist", canJobBeRelisted(job));
        actions.put("canViewOwner", true);
        actions.put("canViewClaimer", job.claimerId() != null);
        actions.put("canForceComplete", !"approved_as_completed".equals(job.status()));

        return actions;
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();

        if (days > 0) {
            return String.format("%d days, %d hours", days, hours);
        } else if (hours > 0) {
            return String.format("%d hours, %d minutes", hours, minutes);
        } else {
            return String.format("%d minutes", minutes);
        }
    }

    private JobEntity getJobById(String jobId, Context context) {
        try {
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(getItemRequest);

            if (!response.hasItem()) {
                return null;
            }

            return JobEntityMapper.mapToJobEntity(response.item());

        } catch (Exception e) {
            context.getLogger().log("Error fetching job by ID: " + e.getMessage());
            throw new RuntimeException("Error fetching job", e);
        }
    }
}