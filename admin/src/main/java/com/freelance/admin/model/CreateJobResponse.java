package com.freelance.admin.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Response record for job creation
 */
public record CreateJobResponse(
    @JsonProperty("jobId") String jobId,
    @JsonProperty("ownerId") String ownerId,
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("payAmount") BigDecimal payAmount,
    @JsonProperty("timeToCompleteSeconds") Long timeToCompleteSeconds,
    @JsonProperty("status") String status,
    @JsonProperty("expiryDate") String expiryDate,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("categoryTopicArn") String categoryTopicArn
) {}