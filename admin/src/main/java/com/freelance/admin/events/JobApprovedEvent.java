package com.freelance.admin.events;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Event record for EventBridge job.approved events */
public record JobApprovedEvent(
    @JsonProperty("eventType") String eventType,
    @JsonProperty("jobId") String jobId,
    @JsonProperty("ownerId") String ownerId,
    @JsonProperty("claimerId") String claimerId,
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("jobName") String jobName,
    @JsonProperty("payAmount") BigDecimal payAmount,
    @JsonProperty("approvedAt") String approvedAt) {

  public JobApprovedEvent(
      String jobId,
      String ownerId,
      String claimerId,
      String categoryId,
      String jobName,
      BigDecimal payAmount,
      String approvedAt) {
    this("job.approved", jobId, ownerId, claimerId, categoryId, jobName, payAmount, approvedAt);
  }
}

