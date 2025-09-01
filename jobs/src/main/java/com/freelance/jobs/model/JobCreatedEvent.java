package com.freelance.jobs.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Event record for EventBridge job.created events */
public record JobCreatedEvent(
    @JsonProperty("eventType") String eventType,
    @JsonProperty("jobId") String jobId,
    @JsonProperty("ownerId") String ownerId,
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("categoryName") String categoryName,
    @JsonProperty("snsTopicArn") String snsTopicArn,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("payAmount") BigDecimal payAmount,
    @JsonProperty("timeToCompleteSeconds") Long timeToCompleteSeconds,
    @JsonProperty("status") String status,
    @JsonProperty("expiryDate") String expiryDate,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("jobUrl") String jobUrl) {

  public static JobCreatedEvent create(
      String jobId,
      String ownerId,
      String categoryId,
      String categoryName,
      String snsTopicArn,
      String name,
      String description,
      BigDecimal payAmount,
      Long timeToCompleteSeconds,
      String status,
      String expiryDate,
      String createdAt,
      String jobUrl) {
    return new JobCreatedEvent(
        "job.created",
        jobId,
        ownerId,
        categoryId,
        categoryName,
        snsTopicArn,
        name,
        description,
        payAmount,
        timeToCompleteSeconds,
        status,
        expiryDate,
        createdAt,
        jobUrl);
  }
}

