package com.freelance.jobs.entity;

import java.math.BigDecimal;

/** Entity record representing a job in DynamoDB */
public record JobEntity(
    String jobId,
    String ownerId,
    String categoryId,
    String name,
    String description,
    BigDecimal payAmount,
    Long timeToCompleteSeconds,
    String expiryDate,
    String status,
    String createdAt,
    String updatedAt,
    String claimerId,
    String claimedAt,
    String submissionDeadline,
    String submittedAt) {

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String jobId;
    private String ownerId;
    private String categoryId;
    private String name;
    private String description;
    private BigDecimal payAmount;
    private Long timeToCompleteSeconds;
    private String expiryDate;
    private String status;
    private String createdAt;
    private String updatedAt;
    private String claimerId;
    private String claimedAt;
    private String submissionDeadline;
    private String submittedAt;

    public Builder jobId(String jobId) {
      this.jobId = jobId;
      return this;
    }

    public Builder ownerId(String ownerId) {
      this.ownerId = ownerId;
      return this;
    }

    public Builder categoryId(String categoryId) {
      this.categoryId = categoryId;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder payAmount(BigDecimal payAmount) {
      this.payAmount = payAmount;
      return this;
    }

    public Builder timeToCompleteSeconds(Long timeToCompleteSeconds) {
      this.timeToCompleteSeconds = timeToCompleteSeconds;
      return this;
    }

    public Builder expiryDate(String expiryDate) {
      this.expiryDate = expiryDate;
      return this;
    }

    public Builder status(String status) {
      this.status = status;
      return this;
    }

    public Builder createdAt(String createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder updatedAt(String updatedAt) {
      this.updatedAt = updatedAt;
      return this;
    }

    public Builder claimerId(String claimerId) {
      this.claimerId = claimerId;
      return this;
    }

    public Builder claimedAt(String claimedAt) {
      this.claimedAt = claimedAt;
      return this;
    }

    public Builder submissionDeadline(String submissionDeadline) {
      this.submissionDeadline = submissionDeadline;
      return this;
    }

    public Builder submittedAt(String submittedAt) {
      this.submittedAt = submittedAt;
      return this;
    }

    public JobEntity build() {
      return new JobEntity(
          jobId,
          ownerId,
          categoryId,
          name,
          description,
          payAmount,
          timeToCompleteSeconds,
          expiryDate,
          status,
          createdAt,
          updatedAt,
          claimerId,
          claimedAt,
          submissionDeadline,
          submittedAt);
    }
  }
}

