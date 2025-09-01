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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for GET /admin/jobs/statistics
 * Returns job statistics with configurable time periods (Admin only)
 */
public class JobStatisticsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yy-MM-dd hh:mma");

    // Default values
    private static final int DEFAULT_STATS_DAYS = 30;
    private static final int DEFAULT_RECENT_DAYS = 7;
    private static final int DEFAULT_RECENT_LIMIT = 10;

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

            // Get query parameters for flexible time periods
            int statsDays = parseInt(RequestMapper.getQueryParameter(input, "statsDays"), DEFAULT_STATS_DAYS);
            int recentDays = parseInt(RequestMapper.getQueryParameter(input, "recentDays"), DEFAULT_RECENT_DAYS);
            int recentLimit = parseInt(RequestMapper.getQueryParameter(input, "recentLimit"), DEFAULT_RECENT_LIMIT);

            // Validate parameters
            statsDays = Math.min(Math.max(statsDays, 1), 365);
            recentDays = Math.min(Math.max(recentDays, 1), 90);
            recentLimit = Math.min(Math.max(recentLimit, 1), 50);

            context.getLogger().log(String.format("Generating stats for %d days, recent activities for %d days (limit: %d)",
                    statsDays, recentDays, recentLimit));

            // Initialize counters
            StatisticsCounters counters = new StatisticsCounters();
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
                    processJobItem(item, counters, claimedByDate, recentActivities, statsDays, recentDays);
                }

                lastEvaluatedKey = response.lastEvaluatedKey();

            } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

            // Prepare date-based statistics for the requested period
            List<Map<String, Object>> stats = prepareDateStats(claimedByDate, statsDays);

            // Sort and limit recent activities
            List<Map<String, Object>> limitedRecent = processRecentActivities(recentActivities, recentLimit);

            // Build the response with metadata about the time periods used
            Map<String, Object> statistics = createResponse(counters, stats, limitedRecent, statsDays, recentDays, recentLimit);

            return ResponseUtil.createSuccessResponse(200, statistics);

        } catch (Exception e) {
            context.getLogger().log("Error generating statistics: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Error generating statistics: " + e.getMessage());
        }
    }

    /**
     * Process a single job item and update counters/activities
     */
    private void processJobItem(Map<String, AttributeValue> item, StatisticsCounters counters,
                                Map<String, Integer> claimedByDate, List<Map<String, Object>> recentActivities,
                                int statsDays, int recentDays) {
        counters.totalJobs++;

        String status = getStringValue(item, "status", "unknown").toLowerCase();

        // Update status counters
        switch (status) {
            case "claimed" -> counters.claimedJobs++;
            case "submitted" -> counters.submittedJobs++;
            case "open", "relisted" -> counters.postedJobs++;
            case "approved" -> counters.approvedJobs++;
            case "rejected" -> counters.rejectedJobs++;
            case "expired" -> counters.expiredJobs++;
        }

        // Process timestamps for statistics and recent activities
        processTimestamps(item, status, claimedByDate, recentActivities, statsDays, recentDays);
    }

    /**
     * Process various timestamps for statistics and activities
     */
    private void processTimestamps(Map<String, AttributeValue> item, String status,
                                   Map<String, Integer> claimedByDate, List<Map<String, Object>> recentActivities,
                                   int statsDays, int recentDays) {
        // Track claimed jobs by date for statistics
        if ("claimed".equals(status)) {
            processTimestamp(item, "claimedAt", claimedByDate, recentActivities, "Claimed", statsDays, recentDays);
        }

        // Track submitted jobs for recent activities
        if ("submitted".equals(status)) {
            processTimestamp(item, "submittedAt", null, recentActivities, "Submitted", 0, recentDays);
        }

        // Track job creation for both statistics and activities
        processTimestamp(item, "createdAt", null, recentActivities, "Posted", 0, recentDays);

        // Track job approvals and rejections
        if ("approved".equals(status)) {
            processTimestamp(item, "updatedAt", null, recentActivities, "Approved", 0, recentDays);
        }
        if ("rejected".equals(status)) {
            processTimestamp(item, "updatedAt", null, recentActivities, "Rejected", 0, recentDays);
        }
    }

    /**
     * Process a specific timestamp field
     */
    private void processTimestamp(Map<String, AttributeValue> item, String fieldName,
                                  Map<String, Integer> dateStats, List<Map<String, Object>> activities,
                                  String activityType, int statsDays, int activityDays) {
        String timestampStr = getStringValue(item, fieldName);
        if (timestampStr != null) {
            try {
                Instant timestamp = Instant.parse(timestampStr);

                // Add to date statistics if within stats period
                if (dateStats != null && isWithinLastDays(timestamp, statsDays)) {
                    String dateKey = formatDate(timestamp);
                    dateStats.put(dateKey, dateStats.getOrDefault(dateKey, 0) + 1);
                }

                // Add to recent activities if within activity period
                if (activities != null && isWithinLastDays(timestamp, activityDays)) {
                    addRecentActivity(activities, item, activityType, timestamp);
                }
            } catch (Exception e) {
                // Log parsing errors silently
            }
        }
    }

    /**
     * Process and limit recent activities
     */
    private List<Map<String, Object>> processRecentActivities(List<Map<String, Object>> activities, int limit) {
        // Sort by timestamp (newest first)
        activities.sort((a, b) -> {
            Instant timeA = (Instant) a.get("timestamp");
            Instant timeB = (Instant) b.get("timestamp");
            return timeB.compareTo(timeA);
        });

        // Apply limit
        return activities.stream()
                .limit(limit)
                .map(this::formatRecentActivity)
                .toList();
    }

    /**
     * Create the final response with metadata
     */
    private Map<String, Object> createResponse(StatisticsCounters counters, List<Map<String, Object>> stats,
                                               List<Map<String, Object>> recent, int statsDays,
                                               int recentDays, int recentLimit) {
        Map<String, Object> response = new HashMap<>();

        // Basic counters
        response.put("totalJobs", counters.totalJobs);
        response.put("totalClaimedJobs", counters.claimedJobs);
        response.put("totalSubmittedJobs", counters.submittedJobs);
        response.put("totalPostedJobs", counters.postedJobs);
        response.put("totalApprovedJobs", counters.approvedJobs);
        response.put("totalRejectedJobs", counters.rejectedJobs);
        response.put("totalExpiredJobs", counters.expiredJobs);

        // Time-based data
        response.put("stats", stats);
        response.put("recent", recent);

        // Metadata about the query
        response.put("queryParameters", Map.of(
                "statsDays", statsDays,
                "recentDays", recentDays,
                "recentLimit", recentLimit,
                "generatedAt", Instant.now().toString()
        ));

        // Summary information
        response.put("summary", Map.of(
                "statsPeriod", statsDays + " days",
                "recentPeriod", recentDays + " days",
                "totalActivitiesFound", recent.size() > recentLimit ? recent.size() : recent.size()
        ));

        return response;
    }

    // Helper methods (keep these from your original code with minor adjustments)
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
        if (days <= 0) return false;
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

        activities.add(activity);
    }

    private Map<String, Object> formatRecentActivity(Map<String, Object> activity) {
        Instant timestamp = (Instant) activity.get("timestamp");
        String type = (String) activity.get("type");
        String jobName = (String) activity.get("jobName");

        String description = switch (type) {
            case "Claimed" -> String.format("claimed %s", jobName);
            case "Submitted" -> String.format("submitted work for %s", jobName);
            case "Posted" -> String.format("posted new job: %s", jobName);
            case "Approved" -> String.format("approved %s", jobName);
            case "Rejected" -> String.format("rejected %s", jobName);
            default -> String.format("%s: %s", type.toLowerCase(), jobName);
        };

        return Map.of(
                "dateTime", formatDateTime(timestamp),
                "type", type,
                "description", description
        );
    }

    private List<Map<String, Object>> prepareDateStats(Map<String, Integer> claimedByDate, int days) {
        List<LocalDate> dateRange = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            dateRange.add(today.minusDays(i));
        }

        return dateRange.stream()
                .map(date -> {
                    String dateKey = date.format(DATE_FORMAT);
                    int claimedCount = claimedByDate.getOrDefault(dateKey, 0);

                    return Map.<String, Object>of(
                            "date", dateKey,
                            "claimed", claimedCount
                    );
                })
                .toList();
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Helper class to track statistics counters
     */
    private static class StatisticsCounters {
        int totalJobs = 0;
        int claimedJobs = 0;
        int submittedJobs = 0;
        int postedJobs = 0;
        int approvedJobs = 0;
        int rejectedJobs = 0;
        int expiredJobs = 0;
    }
}