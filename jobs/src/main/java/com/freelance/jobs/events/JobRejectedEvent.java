package com.freelance.jobs.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Event record for EventBridge job.rejected events
 */
public record JobRejectedEvent(
    @JsonProperty("eventType") String eventType,
    @JsonProperty("jobId") String jobId,
    @JsonProperty("ownerId") String ownerId,
    @JsonProperty("claimerId") String claimerId,
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("jobName") String jobName,
    @JsonProperty("payAmount") BigDecimal payAmount,
    @JsonProperty("rejectedAt") String rejectedAt,
    @JsonProperty("rejectionReason") String rejectionReason) {

  public JobRejectedEvent(String jobId, String ownerId, String claimerId, String categoryId, 
                         String jobName, BigDecimal payAmount, String rejectedAt, String rejectionReason) {
    this("job.rejected", jobId, ownerId, claimerId, categoryId, jobName, payAmount, rejectedAt, rejectionReason);
  }
}