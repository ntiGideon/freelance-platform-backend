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
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Handler for GET /job/seeker/view/{jobId}
 * Returns full details of a specific job if it's available for claiming
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
            if (jobId == null) {
                return ResponseUtil.createErrorResponse(400, "Job ID not found in path");
            }

            context.getLogger().log("Getting job details for jobId: " + jobId);

            JobEntity job = getJobById(jobId, context);
            if (job == null) {
                return ResponseUtil.createErrorResponse(404, "Job not found");
            }

            // Check if job is available for seekers to view
            if (!isJobAvailableForSeekers(job)) {
                return ResponseUtil.createErrorResponse(403, "Job is not available for viewing");
            }

            return ResponseUtil.createSuccessResponse(200, job);

        } catch (Exception e) {
            context.getLogger().log("Error getting job details: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
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

    private boolean isJobAvailableForSeekers(JobEntity job) {
        // Job must be "open" status and not expired
        if (!"open".equals(job.status())) {
            return false;
        }

        try {
            Instant expiryDate = Instant.parse(job.expiryDate());
            return expiryDate.isAfter(Instant.now());
        } catch (Exception e) {
            return false;
        }
    }


}