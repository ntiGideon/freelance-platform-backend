package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for GET /admin/jobs/statistics
 * Returns job statistics (Admin only)
 */
public class JobStatisticsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd hh:mma");

    public JobStatisticsHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String userId = RequestMapper.extractUserIdFromRequestContext(input, "admin");
            if (userId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            context.getLogger().log("Generating job statistics for admin");

            // Initialize counters
            int totalJobs = 0;
            int totalClaimedJobs = 0;
            int totalSubmittedJobs = 0;
            int totalPostedJobs = 0;

            // For date-based statistics
            Map<String, Integer> claimedByDate = new HashMap<>();
            List<Map<String, Object>> recentActivities = new ArrayList<>();

            Map<String, AttributeValue> lastEvaluatedKey = null;

            do {
                ScanRequest scanRequest = ScanRequest.builder()
                        .tableName(jobsTableName)
                        .exclusiveStartKey(lastEvaluatedKey)
                        .build();

                ScanResponse response = dynamoDbClient.scan(scanRequest);

                for (Map<String, AttributeValue> item : response.items()) {
                    totalJobs++;

                    // Get job status
                    String status = getStringValue(item, "status", "unknown");

                    // Count by status
                    if ("claimed".equalsIgnoreCase(status)) {
                        totalClaimedJobs++;
                    } else if ("submitted".equalsIgnoreCase(status)) {
                        totalSubmittedJobs++;
                    } else if ("open".equalsIgnoreCase(status) || "relisted".equalsIgnoreCase(status)) {
                        totalPostedJobs++;
                    }

                    // Track claimed jobs by date
                    if ("claimed".equalsIgnoreCase(status) && item.containsKey("claimedAt")) {
                        String claimedAt = getStringValue(item, "claimedAt");
                        if (claimedAt != null) {
                            try {
                                Instant claimedInstant = Instant.parse(claimedAt);
                                String dateKey = formatDate(claimedInstant);
                                claimedByDate.put(dateKey, claimedByDate.getOrDefault(dateKey, 0) + 1);

                                // Add to recent activities if within last 7 days
                                if (isWithinLastDays(claimedInstant, 7)) {
                                    addRecentActivity(recentActivities, item, "Claimed", claimedInstant);
                                }
                            } catch (Exception e) {
                                context.getLogger().log("Error parsing claimedAt: " + claimedAt);
                            }
                        }
                    }

                    // Track submitted jobs for recent activities
                    if ("submitted".equalsIgnoreCase(status) && item.containsKey("submittedAt")) {
                        String submittedAt = getStringValue(item, "submittedAt");
                        if (submittedAt != null) {
                            try {
                                Instant submittedInstant = Instant.parse(submittedAt);
                                if (isWithinLastDays(submittedInstant, 7)) {
                                    addRecentActivity(recentActivities, item, "Submitted", submittedInstant);
                                }
                            } catch (Exception e) {
                                context.getLogger().log("Error parsing submittedAt: " + submittedAt);
                            }
                        }
                    }

                    // Track job creation for recent activities
                    if (item.containsKey("createdAt")) {
                        String createdAt = getStringValue(item, "createdAt");
                        if (createdAt != null) {
                            try {
                                Instant createdInstant = Instant.parse(createdAt);
                                if (isWithinLastDays(createdInstant, 7)) {
                                    addRecentActivity(recentActivities, item, "Posted", createdInstant);
                                }
                            } catch (Exception e) {
                                context.getLogger().log("Error parsing createdAt: " + createdAt);
                            }
                        }
                    }
                }

                lastEvaluatedKey = response.lastEvaluatedKey();

            } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

            // Prepare date-based statistics
            List<Map<String, Object>> stats = prepareDateStats(claimedByDate);

            // Sort recent activities by date (newest first)
            recentActivities.sort((a, b) -> {
                Instant timeA = (Instant) a.get("timestamp");
                Instant timeB = (Instant) b.get("timestamp");
                return timeB.compareTo(timeA);
            });

            // Take only the 10 most recent activities
            List<Map<String, Object>> limitedRecent = recentActivities.stream()
                    .limit(10)
                    .map(this::formatRecentActivity)
                    .collect(Collectors.toList());

            // Build the response
            Map<String, Object> statistics = Map.of(
                    "totalJobs", totalJobs,
                    "totalClaimedJobs", totalClaimedJobs,
                    "totalSubmittedJobs", totalSubmittedJobs,
                    "totalPostedJobs", totalPostedJobs,
                    "stats", stats,
                    "recent", limitedRecent
            );

            return ResponseUtil.createSuccessResponse(200, statistics);

        } catch (Exception e) {
            context.getLogger().log("Error generating statistics: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Error generating statistics: " + e.getMessage());
        }
    }

    private String getStringValue(Map<String, AttributeValue> item, String key) {
        return getStringValue(item, key, null);
    }

    private String getStringValue(Map<String, AttributeValue> item, String key, String defaultValue) {
        if (item.containsKey(key) && item.get(key).s() != null) {
            return item.get(key).s();
        }
        return defaultValue;
    }

    private String formatDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).format(DATE_FORMAT);
    }

    private String formatDateTime(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT);
    }

    private boolean isWithinLastDays(Instant instant, int days) {
        Instant daysAgo = Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        return instant.isAfter(daysAgo);
    }

    private void addRecentActivity(List<Map<String, Object>> activities,
                                   Map<String, AttributeValue> item,
                                   String type,
                                   Instant timestamp) {
        Map<String, Object> activity = new HashMap<>();
        activity.put("timestamp", timestamp);
        activity.put("type", type);
        activity.put("jobId", getStringValue(item, "jobId"));
        activity.put("jobName", getStringValue(item, "name", "Unknown Job"));
        activity.put("status", getStringValue(item, "status"));

        if ("Claimed".equals(type)) {
            activity.put("claimerId", getStringValue(item, "claimerId"));
        }

        activities.add(activity);
    }

    private Map<String, Object> formatRecentActivity(Map<String, Object> activity) {
        Instant timestamp = (Instant) activity.get("timestamp");
        String type = (String) activity.get("type");
        String jobName = (String) activity.get("jobName");

        String description = "";
        switch (type) {
            case "Claimed":
                description = String.format("claimed %s", jobName);
                break;
            case "Submitted":
                description = String.format("submitted work for %s", jobName);
                break;
            case "Posted":
                description = String.format("posted new job: %s", jobName);
                break;
        }

        return Map.of(
                "dateTime", formatDateTime(timestamp),
                "type", type,
                "description", description
        );
    }

    private List<Map<String, Object>> prepareDateStats(Map<String, Integer> claimedByDate) {
        // Generate dates for the last 30 days
        List<LocalDate> last30Days = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 29; i >= 0; i--) {
            last30Days.add(today.minusDays(i));
        }

        // Create stats for each date
        return last30Days.stream()
                .map(date -> {
                    String dateKey = date.format(DATE_FORMAT);
                    int claimedCount = claimedByDate.getOrDefault(dateKey, 0);

                    return Map.<String, Object>of(
                            "date", dateKey,
                            "claimed", claimedCount
                    );
                })
                .collect(Collectors.toList());
    }
}