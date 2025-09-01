package com.freelance.jobs.overview;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for GET /jobs/overview
 * Returns user-specific job statistics and recent activity
 */
public class OverviewHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;
    private final ObjectMapper objectMapper;

    public OverviewHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String userId = RequestMapper.extractUserIdFromRequestContext(input, "user");
            
            if (userId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            context.getLogger().log("Getting overview for user: " + userId);

            // Get user's job statistics and recent activity
            OverviewData overview = getUserOverview(userId, context);

            return ResponseUtil.createSuccessResponse(200, overview);

        } catch (Exception e) {
            context.getLogger().log("Error getting user overview: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private OverviewData getUserOverview(String userId, Context context) {
        try {
            // Query jobs where user is either owner or claimer
            List<Map<String, AttributeValue>> ownerJobs = getUserJobsAsOwner(userId);
            List<Map<String, AttributeValue>> claimerJobs = getUserJobsAsClaimer(userId);

            // Calculate statistics
            int totalJobs = ownerJobs.size(); // Jobs user has posted/owns
            int totalClaimedJobs = (int) claimerJobs.stream()
                    .filter(job -> "claimed".equals(getStringValue(job, "status")))
                    .count();
            int totalSubmittedJobs = (int) claimerJobs.stream()
                    .filter(job -> "submitted".equals(getStringValue(job, "status")))
                    .count();
            int totalPostedJobs = totalJobs;

            // Generate daily stats for last 7 days
            List<DailyStats> stats = generateDailyStats(claimerJobs, 7);

            // Generate recent activity
            List<RecentActivity> recent = generateRecentActivity(ownerJobs, claimerJobs, 10);

            return new OverviewData(totalJobs, totalClaimedJobs, totalSubmittedJobs, totalPostedJobs, stats, recent);

        } catch (Exception e) {
            throw new RuntimeException("Error calculating user overview", e);
        }
    }

    private List<Map<String, AttributeValue>> getUserJobsAsOwner(String userId) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(jobsTableName)
                .filterExpression("ownerId = :userId")
                .expressionAttributeValues(Map.of(":userId", AttributeValue.builder().s(userId).build()))
                .build();

        ScanResponse response = dynamoDbClient.scan(scanRequest);
        return response.items();
    }

    private List<Map<String, AttributeValue>> getUserJobsAsClaimer(String userId) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(jobsTableName)
                .filterExpression("claimerId = :userId")
                .expressionAttributeValues(Map.of(":userId", AttributeValue.builder().s(userId).build()))
                .build();

        ScanResponse response = dynamoDbClient.scan(scanRequest);
        return response.items();
    }

    private List<DailyStats> generateDailyStats(List<Map<String, AttributeValue>> jobs, int days) {
        List<DailyStats> stats = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yy");
        
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            String dateStr = date.format(formatter);
            
            long claimedCount = jobs.stream()
                    .filter(job -> {
                        String claimedAt = getStringValue(job, "claimedAt");
                        if (claimedAt != null) {
                            try {
                                LocalDateTime claimedDateTime = LocalDateTime.parse(claimedAt);
                                return claimedDateTime.toLocalDate().equals(date);
                            } catch (Exception e) {
                                return false;
                            }
                        }
                        return false;
                    })
                    .count();
            
            stats.add(new DailyStats(dateStr, (int) claimedCount));
        }
        
        return stats;
    }

    private List<RecentActivity> generateRecentActivity(List<Map<String, AttributeValue>> ownerJobs, 
                                                       List<Map<String, AttributeValue>> claimerJobs, 
                                                       int limit) {
        List<RecentActivity> activities = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yy hh:mma");

        // Add claimed activities
        claimerJobs.stream()
                .filter(job -> getStringValue(job, "claimedAt") != null)
                .forEach(job -> {
                    try {
                        LocalDateTime claimedAt = LocalDateTime.parse(getStringValue(job, "claimedAt"));
                        String description = "claimed " + getStringValue(job, "name");
                        activities.add(new RecentActivity(
                                claimedAt.format(formatter),
                                "Claimed",
                                description
                        ));
                    } catch (Exception e) {
                        // Skip invalid dates
                    }
                });

        // Add submitted activities
        claimerJobs.stream()
                .filter(job -> "submitted".equals(getStringValue(job, "status")) && 
                              getStringValue(job, "submittedAt") != null)
                .forEach(job -> {
                    try {
                        LocalDateTime submittedAt = LocalDateTime.parse(getStringValue(job, "submittedAt"));
                        String description = "submitted " + getStringValue(job, "name");
                        activities.add(new RecentActivity(
                                submittedAt.format(formatter),
                                "Submitted",
                                description
                        ));
                    } catch (Exception e) {
                        // Skip invalid dates
                    }
                });

        // Add posted activities (from owned jobs)
        ownerJobs.stream()
                .filter(job -> getStringValue(job, "createdAt") != null)
                .forEach(job -> {
                    try {
                        LocalDateTime createdAt = LocalDateTime.parse(getStringValue(job, "createdAt"));
                        String description = "posted " + getStringValue(job, "name");
                        activities.add(new RecentActivity(
                                createdAt.format(formatter),
                                "Posted",
                                description
                        ));
                    } catch (Exception e) {
                        // Skip invalid dates
                    }
                });

        // Sort by date descending and limit
        return activities.stream()
                .sorted((a, b) -> {
                    try {
                        LocalDateTime dateA = LocalDateTime.parse(a.dateTime(), formatter);
                        LocalDateTime dateB = LocalDateTime.parse(b.dateTime(), formatter);
                        return dateB.compareTo(dateA);
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String getStringValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value != null && value.s() != null ? value.s() : null;
    }

    // Data classes
    public record OverviewData(
            int totalJobs,
            int totalClaimedJobs,
            int totalSubmittedJobs,
            int totalPostedJobs,
            List<DailyStats> stats,
            List<RecentActivity> recent
    ) {}

    public record DailyStats(String date, int claimed) {}

    public record RecentActivity(String dateTime, String type, String description) {}
}