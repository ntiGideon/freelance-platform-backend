package com.freelance.jobs.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Event record for EventBridge job.claimed events
 */
public record JobClaimedEvent(
    @JsonProperty("eventType") String eventType,
    @JsonProperty("jobId") String jobId,
    @JsonProperty("ownerId") String ownerId,
    @JsonProperty("claimerId") String claimerId,
    @JsonProperty("claimerEmail") String claimerEmail,  // From Cognito headers
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("jobName") String jobName,
    @JsonProperty("payAmount") BigDecimal payAmount,
    @JsonProperty("claimedAt") String claimedAt,
    @JsonProperty("submissionDeadline") String submissionDeadline) {

  public JobClaimedEvent(String jobId, String ownerId, String claimerId, String claimerEmail,
                        String categoryId, String jobName, BigDecimal payAmount, String claimedAt, String submissionDeadline) {
    this("job.claimed", jobId, ownerId, claimerId, claimerEmail, categoryId, jobName, payAmount, claimedAt, submissionDeadline);
  }
}