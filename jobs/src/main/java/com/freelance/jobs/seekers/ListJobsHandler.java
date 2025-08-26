package com.freelance.jobs.seekers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Handler for GET /job/seeker/list
 * Returns jobs for seekers with comprehensive query parameter support
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
            String seekerId = RequestMapper.extractSeekerIdFromContext(input);
            context.getLogger().log("Getting jobs for seeker: " + seekerId);

            // Get query parameters
            String type = RequestMapper.getQueryParameter(input, "type", "available");
            String status = RequestMapper.getQueryParameter(input, "status");
            String categoryId = RequestMapper.getQueryParameter(input, "categoryId");
            String query = RequestMapper.getQueryParameter(input, "query");
            String sortBy = RequestMapper.getQueryParameter(input, "sortBy", "newest");
            String sortOrder = RequestMapper.getQueryParameter(input, "sortOrder", "desc");
            int limit = parseInt(RequestMapper.getQueryParameter(input, "limit", "20"));
            int offset = parseInt(RequestMapper.getQueryParameter(input, "offset", "0"));

            List<JobEntity> jobs = getJobsForSeeker(seekerId, type, status, context);

            // Apply additional filters
            jobs = applyFilters(jobs, categoryId, query);

            // Sort jobs based on parameters
            sortJobs(jobs, sortBy, sortOrder);

            // Apply pagination
            List<JobEntity> paginatedJobs = applyPagination(jobs, offset, limit);

            // Create response with metadata
            Map<String, Object> response = Map.of(
                    "jobs", paginatedJobs,
                    "count", paginatedJobs.size(),
                    "total", jobs.size(),
                    "offset", offset,
                    "limit", limit,
                    "hasMore", jobs.size() > (offset + limit),
                    "seekerId", seekerId,
                    "filters", Map.of("type", type, "status", status != null ? status : "all"),
                    "summary", createJobsSummary(jobs)
            );

            context.getLogger().log(String.format("Found %d jobs (showing %d-%d) for seeker %s", 
                jobs.size(), offset + 1, offset + paginatedJobs.size(), seekerId));

            return ResponseUtil.createSuccessResponse(200, response);

        } catch (Exception e) {
            context.getLogger().log("Error getting jobs: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private List<JobEntity> getJobsForSeeker(String seekerId, String type, String statusFilter, Context context) {
        try {
            List<JobEntity> jobs = new ArrayList<>();
            
            switch (type.toLowerCase()) {
                case "available":
                    jobs = getAvailableJobs(context);
                    break;
                case "claimed":
                    jobs = getClaimedJobs(seekerId, context);
                    break;
                case "completed":
                    jobs = getCompletedJobs(seekerId, context);
                    break;
                case "all":
                    jobs.addAll(getAvailableJobs(context));
                    jobs.addAll(getClaimedJobs(seekerId, context));
                    jobs.addAll(getCompletedJobs(seekerId, context));
                    break;
                default:
                    jobs = getAvailableJobs(context);
            }
            
            // Apply status filter if provided
            if (statusFilter != null && !statusFilter.isEmpty()) {
                List<String> targetStatuses = List.of(statusFilter.split(","));
                jobs = jobs.stream()
                        .filter(job -> targetStatuses.contains(job.status()))
                        .collect(Collectors.toList());
            }
            
            return jobs;
            
        } catch (Exception e) {
            context.getLogger().log("Error getting jobs for seeker: " + e.getMessage());
            throw new RuntimeException("Error fetching seeker jobs", e);
        }
    }
    
    private List<JobEntity> getAvailableJobs(Context context) {
        try {
            // Use GSI to query jobs by status = "open"
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(jobsTableName)
                    .indexName("StatusExpiryIndex")
                    .keyConditionExpression("#status = :status")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(":status", AttributeValue.builder().s("open").build()))
                    .build();

            QueryResponse response = dynamoDbClient.query(queryRequest);
            List<JobEntity> jobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                JobEntity job = JobEntityMapper.mapToJobEntity(item);
                jobs.add(job);
            }

            // Filter out expired jobs
            return filterExpiredJobs(jobs);

        } catch (Exception e) {
            context.getLogger().log("Error querying available jobs: " + e.getMessage());
            throw new RuntimeException("Error fetching available jobs", e);
        }
    }
    
    private List<JobEntity> getClaimedJobs(String seekerId, Context context) {
        try {
            // Use GSI to query jobs claimed by this seeker
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(jobsTableName)
                    .indexName("ClaimerJobsIndex")
                    .keyConditionExpression("#claimerId = :claimerId")
                    .expressionAttributeNames(Map.of("#claimerId", "claimerId"))
                    .expressionAttributeValues(Map.of(":claimerId", AttributeValue.builder().s(seekerId).build()))
                    .scanIndexForward(false)
                    .build();

            QueryResponse response = dynamoDbClient.query(queryRequest);
            List<JobEntity> jobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                JobEntity job = JobEntityMapper.mapToJobEntity(item);
                // Only include claimed and submitted jobs
                if ("claimed".equals(job.status()) || "submitted".equals(job.status())) {
                    jobs.add(job);
                }
            }

            return jobs;

        } catch (Exception e) {
            context.getLogger().log("Error querying claimed jobs: " + e.getMessage());
            throw new RuntimeException("Error fetching claimed jobs", e);
        }
    }
    
    private List<JobEntity> getCompletedJobs(String seekerId, Context context) {
        try {
            // Use GSI to query jobs completed by this seeker
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(jobsTableName)
                    .indexName("ClaimerJobsIndex")
                    .keyConditionExpression("#claimerId = :claimerId")
                    .expressionAttributeNames(Map.of("#claimerId", "claimerId"))
                    .expressionAttributeValues(Map.of(":claimerId", AttributeValue.builder().s(seekerId).build()))
                    .scanIndexForward(false)
                    .build();

            QueryResponse response = dynamoDbClient.query(queryRequest);
            List<JobEntity> jobs = new ArrayList<>();

            for (Map<String, AttributeValue> item : response.items()) {
                JobEntity job = JobEntityMapper.mapToJobEntity(item);
                // Only include approved jobs
                if ("approved".equals(job.status())) {
                    jobs.add(job);
                }
            }

            return jobs;

        } catch (Exception e) {
            context.getLogger().log("Error querying completed jobs: " + e.getMessage());
            throw new RuntimeException("Error fetching completed jobs", e);
        }
    }

    private List<JobEntity> filterExpiredJobs(List<JobEntity> jobs) {
        Instant now = Instant.now();
        return jobs.stream()
                .filter(job -> {
                    try {
                        Instant expiryDate = Instant.parse(job.expiryDate());
                        return expiryDate.isAfter(now);
                    } catch (Exception e) {
                        return false; // Filter out jobs with invalid expiry dates
                    }
                })
                .collect(Collectors.toList());
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
        long availableJobs = jobs.stream().filter(job -> "open".equals(job.status())).count();
        long claimedJobs = jobs.stream().filter(job -> "claimed".equals(job.status())).count();
        long submittedJobs = jobs.stream().filter(job -> "submitted".equals(job.status())).count();
        long completedJobs = jobs.stream().filter(job -> "approved".equals(job.status())).count();

        return Map.of(
                "total", (long) jobs.size(),
                "available", availableJobs,
                "claimed", claimedJobs,
                "submitted", submittedJobs,
                "completed", completedJobs
        );
    }

}