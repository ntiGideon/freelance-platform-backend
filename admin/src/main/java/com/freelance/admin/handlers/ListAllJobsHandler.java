package com.freelance.admin.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.entity.JobEntity;
import com.freelance.admin.mappers.JobEntityMapper;
import com.freelance.admin.mappers.RequestMapper;
import com.freelance.admin.shared.ResponseUtil;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for GET /admin/jobs
 * Returns all jobs in the system (Admin only)
 */
public class ListAllJobsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final String jobsTableName;

    public ListAllJobsHandler() {
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

            context.getLogger().log("Admin listing all jobs in system");

            // Get query parameters (same as owner handler)
            String type = RequestMapper.getQueryParameter(input, "type", "all");
            String status = RequestMapper.getQueryParameter(input, "status");
            String categoryId = RequestMapper.getQueryParameter(input, "categoryId");
            String query = RequestMapper.getQueryParameter(input, "query");
            String sortBy = RequestMapper.getQueryParameter(input, "sortBy", "newest");
            String sortOrder = RequestMapper.getQueryParameter(input, "sortOrder", "desc");
            int limit = parseInt(RequestMapper.getQueryParameter(input, "limit", "20"));
            int offset = parseInt(RequestMapper.getQueryParameter(input, "offset", "0"));

            // Get all jobs from the system
            List<JobEntity> allJobs = getAllJobsFromSystem(context);

            // Apply filters based on query parameters
            allJobs = applyFilters(allJobs, type, status, categoryId, query);

            // Sort jobs based on parameters
            sortJobs(allJobs, sortBy, sortOrder);

            // Apply pagination
            List<JobEntity> paginatedJobs = applyPagination(allJobs, offset, limit);

            // Create response with metadata (same structure as owner handler)
            Map<String, Object> response = Map.of(
                    "jobs", paginatedJobs,
                    "count", paginatedJobs.size(),
                    "total", allJobs.size(),
                    "offset", offset,
                    "limit", limit,
                    "hasMore", allJobs.size() > (offset + limit),
                    "filters", Map.of(
                            "type", type,
                            "status", status != null ? status : "all",
                            "categoryId", categoryId != null ? categoryId : "all",
                            "query", query != null ? query : ""
                    ),
                    "summary", createJobsSummary(allJobs)
            );

            context.getLogger().log(String.format("Admin found %d total jobs (showing %d-%d)",
                    allJobs.size(), offset + 1, offset + paginatedJobs.size()));

            return ResponseUtil.createSuccessResponse(200, response);

        } catch (Exception e) {
            context.getLogger().log("Error listing all jobs: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Error listing jobs: " + e.getMessage());
        }
    }

    /**
     * Scan the entire jobs table to get all jobs in the system
     */
    private List<JobEntity> getAllJobsFromSystem(Context context) {
        List<JobEntity> allJobs = new ArrayList<>();
        Map<String, AttributeValue> lastEvaluatedKey = null;

        try {
            do {
                ScanRequest scanRequest = ScanRequest.builder()
                        .tableName(jobsTableName)
                        .exclusiveStartKey(lastEvaluatedKey)
                        .build();

                ScanResponse response = dynamoDbClient.scan(scanRequest);

                for (Map<String, AttributeValue> item : response.items()) {
                    allJobs.add(JobEntityMapper.mapToJobEntity(item));
                }

                lastEvaluatedKey = response.lastEvaluatedKey();

            } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());

            context.getLogger().log("Scanned " + allJobs.size() + " jobs from system");
            return allJobs;

        } catch (Exception e) {
            context.getLogger().log("Error scanning jobs table: " + e.getMessage());
            throw new RuntimeException("Error fetching all jobs from system", e);
        }
    }

    /**
     * Apply all filters based on query parameters
     */
    private List<JobEntity> applyFilters(List<JobEntity> jobs, String type, String statusFilter,
                                         String categoryId, String query) {
        return jobs.stream()
                .filter(job -> filterByType(job, type))
                .filter(job -> filterByStatus(job, statusFilter))
                .filter(job -> filterByCategory(job, categoryId))
                .filter(job -> filterBySearchQuery(job, query))
                .collect(Collectors.toList());
    }

    private boolean filterByType(JobEntity job, String type) {
        if (type == null || "all".equalsIgnoreCase(type)) {
            return true;
        }

        return switch (type.toLowerCase()) {
            case "posted" -> Arrays.asList("open", "claimed", "submitted").contains(job.status());
            case "completed" -> Arrays.asList("approved", "rejected").contains(job.status());
            case "active" -> Arrays.asList("open", "claimed", "submitted").contains(job.status());
            case "pending" -> "submitted".equals(job.status());
            default -> true;
        };
    }

    private boolean filterByStatus(JobEntity job, String statusFilter) {
        if (statusFilter == null || statusFilter.isEmpty()) {
            return true;
        }

        // Support comma-separated statuses
        String[] statuses = statusFilter.split(",");
        return Arrays.stream(statuses)
                .anyMatch(status -> status.trim().equalsIgnoreCase(job.status()));
    }

    private boolean filterByCategory(JobEntity job, String categoryId) {
        return categoryId == null || categoryId.isEmpty() || job.categoryId().equals(categoryId);
    }

    private boolean filterBySearchQuery(JobEntity job, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }

        String searchTerm = query.toLowerCase().trim();
        String jobName = job.name() != null ? job.name().toLowerCase() : "";
        String jobDescription = job.description() != null ? job.description().toLowerCase() : "";

        return jobName.contains(searchTerm) || jobDescription.contains(searchTerm);
    }

    /**
     * Sort jobs based on sort parameters
     */
    private void sortJobs(List<JobEntity> jobs, String sortBy, String sortOrder) {
        boolean ascending = "asc".equalsIgnoreCase(sortOrder);

        jobs.sort((a, b) -> {
            int comparison = 0;

            switch (sortBy.toLowerCase()) {
                case "newest":
                case "createdat":
                case "created":
                    comparison = a.createdAt().compareTo(b.createdAt());
                    break;
                case "updatedat":
                case "updated":
                    comparison = a.updatedAt().compareTo(b.updatedAt());
                    break;
                case "highest_paying":
                case "payamount":
                case "pay":
                    comparison = a.payAmount().compareTo(b.payAmount());
                    break;
                case "latest_expiration":
                case "expirydate":
                case "expiry":
                    comparison = a.expiryDate().compareTo(b.expiryDate());
                    break;
                case "status":
                    comparison = a.status().compareTo(b.status());
                    break;
                case "name":
                case "title":
                    comparison = a.name().compareTo(b.name());
                    break;
                default:
                    // Default sort by createdAt
                    comparison = a.createdAt().compareTo(b.createdAt());
            }

            return ascending ? comparison : -comparison;
        });
    }

    /**
     * Apply pagination to the results
     */
    private List<JobEntity> applyPagination(List<JobEntity> jobs, int offset, int limit) {
        if (offset >= jobs.size()) {
            return new ArrayList<>();
        }

        int endIndex = Math.min(offset + limit, jobs.size());
        return jobs.subList(offset, endIndex);
    }

    /**
     * Create summary statistics for all jobs
     */
    private Map<String, Long> createJobsSummary(List<JobEntity> jobs) {
        return Map.of(
                "total", (long) jobs.size(),
                "open", jobs.stream().filter(job -> "open".equals(job.status())).count(),
                "claimed", jobs.stream().filter(job -> "claimed".equals(job.status())).count(),
                "submitted", jobs.stream().filter(job -> "submitted".equals(job.status())).count(),
                "approved", jobs.stream().filter(job -> "approved".equals(job.status())).count(),
                "rejected", jobs.stream().filter(job -> "rejected".equals(job.status())).count(),
                "expired", jobs.stream().filter(job -> "expired".equals(job.status())).count()
        );
    }

    private int parseInt(String value, String defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            try {
                return Integer.parseInt(defaultValue);
            } catch (NumberFormatException ex) {
                return 20;
            }
        }
    }

    private int parseInt(String value) {
        return parseInt(value, "20");
    }
}