package com.freelance.jobs.owners;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelance.jobs.entity.JobEntity;
import com.freelance.jobs.mappers.JobEntityMapper;
import com.freelance.jobs.mappers.RequestMapper;
import com.freelance.jobs.model.EditJobRequest;
import com.freelance.jobs.shared.ResponseUtil;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Handler for PUT /job/owner/edit/{jobId}
 * Allows editing of job details for jobs in "open" status only
 */
public class EditJobHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String jobsTableName;

    public EditJobHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        this.jobsTableName = System.getenv("JOBS_TABLE_NAME");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String ownerId = RequestMapper.extractOwnerIdFromContext(input);
            String jobId = input.getPathParameters().get("jobId");
            
            if (ownerId == null) {
                return ResponseUtil.createErrorResponse(401, "Unauthorized: User ID not found");
            }
            
            if (jobId == null || jobId.trim().isEmpty()) {
                return ResponseUtil.createErrorResponse(400, "Job ID is required");
            }

            // Handle base64 encoded request body
            String requestBody = input.getBody();
            if (input.getIsBase64Encoded() != null && input.getIsBase64Encoded()) {
                requestBody = new String(Base64.getDecoder().decode(requestBody), StandardCharsets.UTF_8);
            }

            EditJobRequest request = objectMapper.readValue(requestBody, EditJobRequest.class);
            if (!request.isValid()) {
                return ResponseUtil.createErrorResponse(400, request.getValidationError());
            }

            context.getLogger().log("Editing job " + jobId + " for owner: " + ownerId);

            // Get current job and validate ownership
            JobEntity currentJob = getJobById(jobId);
            if (currentJob == null) {
                return ResponseUtil.createErrorResponse(404, "Job not found");
            }

            if (!currentJob.ownerId().equals(ownerId)) {
                return ResponseUtil.createErrorResponse(403, "Job does not belong to you");
            }

            // Only allow editing of "open" jobs
            if (!"open".equals(currentJob.status())) {
                return ResponseUtil.createErrorResponse(400, 
                    "Job can only be edited while in 'open' status. Current status: " + currentJob.status());
            }

            // Update job with new data
            JobEntity updatedJob = updateJob(currentJob, request);
            
            return ResponseUtil.createSuccessResponse(200, Map.of(
                "message", "Job updated successfully",
                "jobId", updatedJob.jobId(),
                "updatedAt", updatedJob.updatedAt()
            ));

        } catch (Exception e) {
            context.getLogger().log("Error editing job: " + e.getMessage());
            e.printStackTrace();
            return ResponseUtil.createErrorResponse(500, "Internal server error");
        }
    }

    private JobEntity getJobById(String jobId) {
        try {
            GetItemRequest getRequest = GetItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(getRequest);
            if (!response.hasItem()) {
                return null;
            }

            return JobEntityMapper.mapToJobEntity(response.item());
        } catch (Exception e) {
            throw new RuntimeException("Error fetching job: " + jobId, e);
        }
    }

    private JobEntity updateJob(JobEntity currentJob, EditJobRequest request) {
        try {
            Instant now = Instant.now();
            Instant newExpiryDate = now.plus(request.expirySeconds(), ChronoUnit.SECONDS);

            // Build update expression dynamically based on what fields are provided
            StringBuilder updateExpression = new StringBuilder("SET #updatedAt = :updatedAt");
            Map<String, String> attributeNames = Map.of(
                "#updatedAt", "updatedAt",
                "#name", "name",
                "#description", "description", 
                "#payAmount", "payAmount",
                "#timeToComplete", "timeToCompleteSeconds",
                "#expiryDate", "expiryDate",
                "#ttl", "ttl"
            );

            Map<String, AttributeValue> attributeValues = Map.of(
                ":updatedAt", AttributeValue.builder().s(now.toString()).build(),
                ":name", AttributeValue.builder().s(request.trimmedName()).build(),
                ":description", AttributeValue.builder().s(request.trimmedDescription()).build(),
                ":payAmount", AttributeValue.builder().n(request.payAmount().toString()).build(),
                ":timeToComplete", AttributeValue.builder().n(request.timeToCompleteSeconds().toString()).build(),
                ":expiryDate", AttributeValue.builder().s(newExpiryDate.toString()).build(),
                ":ttl", AttributeValue.builder().n(String.valueOf(newExpiryDate.getEpochSecond())).build(),
                ":ownerId", AttributeValue.builder().s(currentJob.ownerId()).build(),
                ":openStatus", AttributeValue.builder().s("open").build()
            );

            updateExpression.append(", #name = :name")
                           .append(", #description = :description")
                           .append(", #payAmount = :payAmount") 
                           .append(", #timeToComplete = :timeToComplete")
                           .append(", #expiryDate = :expiryDate")
                           .append(", #ttl = :ttl");

            UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                    .tableName(jobsTableName)
                    .key(Map.of("jobId", AttributeValue.builder().s(currentJob.jobId()).build()))
                    .updateExpression(updateExpression.toString())
                    .conditionExpression("ownerId = :ownerId AND #status = :openStatus")
                    .expressionAttributeNames(Map.of(
                        "#updatedAt", "updatedAt",
                        "#name", "name", 
                        "#description", "description",
                        "#payAmount", "payAmount",
                        "#timeToComplete", "timeToCompleteSeconds",
                        "#expiryDate", "expiryDate",
                        "#ttl", "ttl",
                        "#status", "status"
                    ))
                    .expressionAttributeValues(attributeValues)
                    .returnValues(ReturnValue.ALL_NEW)
                    .build();

            UpdateItemResponse response = dynamoDbClient.updateItem(updateRequest);
            return JobEntityMapper.mapToJobEntity(response.attributes());

        } catch (ConditionalCheckFailedException e) {
            throw new RuntimeException("Job status changed or job doesn't belong to owner", e);
        } catch (Exception e) {
            throw new RuntimeException("Error updating job: " + currentJob.jobId(), e);
        }
    }
}