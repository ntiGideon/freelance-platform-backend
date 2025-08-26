package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.shared.ResponseUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Handler for GET /job/owner/list
 * Returns all jobs posted by the owner
 */
public class ListJobsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;

    public ListJobsHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String ownerId = RequestMapper.extractOwnerIdFromContext(input);
            
            if (ownerId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }

            context.getLogger().log("Getting job list for owner: " + ownerId);

            // Get query parameters
            String type = RequestMapper.getQueryParameter(input, "type", "all");
            String status = RequestMapper.getQueryParameter(input, "status");
            String categoryId = RequestMapper.getQueryParameter(input, "categoryId");
            String query = RequestMapper.getQueryParameter(input, "query");
            String sortBy = RequestMapper.getQueryParameter(input, "sortBy", "newest");
            String sortOrder = RequestMapper.getQueryParameter(input, "sortOrder", "desc");
            int limit = parseInt(RequestMapper.getQueryParameter(input, "limit", "20"));
            int offset = parseInt(RequestMapper.getQueryParameter(input, "offset", "0"));

            List<JobEntity> ownerJobs = getJobsForOwner(ownerId, type, status, context);

            // Apply additional filters
            ownerJobs = applyFilters(ownerJobs, categoryId, query);

            // Sort jobs based on parameters  
            sortJobs(ownerJobs, sortBy, sortOrder);

            // Apply pagination
            List<JobEntity> paginatedJobs = applyPagination(ownerJobs, offset, limit);

            // Create response with metadata
            Map<String, Object> response = Map.of(
                    "jobs", paginatedJobs,
                    "count", paginatedJobs.size(),
                    "total", ownerJobs.size(),
                    "offset", offset,
                    "limit", limit,
                    "hasMore", ownerJobs.size() > (offset + limit),
                    "ownerId", ownerId,
                    "filters", Map.of(
                        "type", type, 
                        "status", status != null ? status : "all",
                        "categoryId", categoryId != null ? categoryId : "all",
                        "query", query != null ? query : ""
                    ),
                    "summary", createJobsSummary(ownerJobs)
            );

            context.getLogger().log(String.format("Found %d jobs (showing %d-%d) for owner %s", 
                ownerJobs.size(), offset + 1, offset + paginatedJobs.size(), ownerId));

            return ResponseUtil.createSuccessResponse(200, response);

        } catch (Exception e) {
            context.getLogger().log("Error getting owner jobs: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private List<JobEntity> getJobsForOwner(String ownerId, String type, String statusFilter, Context context) {
        try {
            // Determine status filter based on type
            List<String> targetStatuses = getStatusesForType(type, statusFilter);
            
            // Query all jobs for owner first
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(jobsTableName)
                    .indexName("OwnerJobsIndex")
                    .keyConditionExpression("#ownerId = :ownerId")
                    .expressionAttributeNames(Map.of("#ownerId", "ownerId"))
                    .expressionAttributeValues(Map.of(":ownerId", AttributeValue.builder().s(ownerId).build()))
                    .scanIndexForward(false) // Sort by datePosted descending
                    .build();

            QueryResponse response = dynamoDbClient.query(queryRequest);
            List<JobEntity> jobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                JobEntity job = JobEntityMapper.mapToJobEntity(item);
                // Apply status filtering
                if (targetStatuses.isEmpty() || targetStatuses.contains(job.status())) {
                    jobs.add(job);
                }
            }

            return jobs;

        } catch (Exception e) {
            context.getLogger().log("Error querying owner jobs: " + e.getMessage());
            throw new RuntimeException("Error fetching owner jobs", e);
        }
    }
    
    private List<String> getStatusesForType(String type, String statusFilter) {
        if (statusFilter != null && !statusFilter.isEmpty()) {
            // If explicit status filter provided, use it
            return List.of(statusFilter.split(","));
        }
        
        // Map type to statuses
        return switch (type.toLowerCase()) {
            case "posted" -> List.of("open", "claimed", "submitted");
            case "completed" -> List.of("approved", "rejected");
            case "active" -> List.of("open", "claimed", "submitted");
            case "pending" -> List.of("submitted");
            default -> List.of(); // "all" - no filtering
        };
    }

    /**
     * Apply additional filters for search and category
     */
    private List<JobEntity> applyFilters(List<JobEntity> jobs, String categoryId, String query) {
        return jobs.stream()
                .filter(job -> categoryId == null || job.categoryId().equals(categoryId))
                .filter(job -> query == null || matchesSearchQuery(job, query))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Check if job matches search query (case-insensitive search in name and description)
     */
    private boolean matchesSearchQuery(JobEntity job, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        
        String searchTerm = query.toLowerCase().trim();
        String jobName = job.name().toLowerCase();
        String jobDescription = job.description().toLowerCase();
        
        return jobName.contains(searchTerm) || jobDescription.contains(searchTerm);
    }

    private void sortJobs(List<JobEntity> jobs, String sortBy, String sortOrder) {
        boolean ascending = "asc".equalsIgnoreCase(sortOrder);
        
        switch (sortBy.toLowerCase()) {
            case "newest":
            case "createdat":
            case "created":
                jobs.sort(ascending ? 
                    (a, b) -> a.createdAt().compareTo(b.createdAt()) :
                    (a, b) -> b.createdAt().compareTo(a.createdAt()));
                break;
            case "updatedat":
            case "updated":
                jobs.sort(ascending ? 
                    (a, b) -> a.updatedAt().compareTo(b.updatedAt()) :
                    (a, b) -> b.updatedAt().compareTo(a.updatedAt()));
                break;
            case "highest_paying":
            case "payamount":
            case "pay":
                jobs.sort(ascending ? 
                    (a, b) -> a.payAmount().compareTo(b.payAmount()) :
                    (a, b) -> b.payAmount().compareTo(a.payAmount()));
                break;
            case "latest_expiration":
            case "expirydate":
            case "expiry":
                jobs.sort(ascending ? 
                    (a, b) -> a.expiryDate().compareTo(b.expiryDate()) :
                    (a, b) -> b.expiryDate().compareTo(a.expiryDate()));
                break;
            case "status":
                jobs.sort(ascending ? 
                    (a, b) -> a.status().compareTo(b.status()) :
                    (a, b) -> b.status().compareTo(a.status()));
                break;
            default:
                // Default sort by createdAt descending (newest first)
                jobs.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        }
    }
    
    private List<JobEntity> applyPagination(List<JobEntity> jobs, int offset, int limit) {
        if (offset >= jobs.size()) {
            return new ArrayList<>();
        }
        
        int endIndex = Math.min(offset + limit, jobs.size());
        return jobs.subList(offset, endIndex);
    }
    
    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Long> createJobsSummary(List<JobEntity> jobs) {
        long openJobs = jobs.stream().filter(job -> "open".equals(job.status())).count();
        long claimedJobs = jobs.stream().filter(job -> "claimed".equals(job.status())).count();
        long submittedJobs = jobs.stream().filter(job -> "submitted".equals(job.status())).count();
        long completedJobs = jobs.stream().filter(job -> "approved_as_completed".equals(job.status())).count();

        return Map.of(
                "total", (long) jobs.size(),
                "open", openJobs,
                "claimed", claimedJobs,
                "submitted", submittedJobs,
                "completed", completedJobs
        );
    }

}