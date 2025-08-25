package com.freelance.jobs.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Request record for editing an existing job
 * Only allows editing of basic job details - category cannot be changed
 */
public record EditJobRequest(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("payAmount") BigDecimal payAmount,
    @JsonProperty("timeToCompleteSeconds") Long timeToCompleteSeconds,
    @JsonProperty("expirySeconds") Long expirySeconds
) {
    
    /**
     * Validation methods
     */
    public boolean isValid() {
        return name != null && !name.trim().isEmpty() &&
               description != null && !description.trim().isEmpty() &&
               payAmount != null && payAmount.compareTo(BigDecimal.ZERO) > 0 &&
               timeToCompleteSeconds != null && timeToCompleteSeconds > 0 &&
               expirySeconds != null && expirySeconds > 0;
    }
    
    public String trimmedName() {
        return name != null ? name.trim() : null;
    }
    
    public String trimmedDescription() {
        return description != null ? description.trim() : null;
    }
    
    /**
     * Get validation error message
     */
    public String getValidationError() {
        if (name == null || name.trim().isEmpty()) {
            return "Job name is required";
        }
        if (description == null || description.trim().isEmpty()) {
            return "Job description is required";
        }
        if (payAmount == null || payAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return "Pay amount must be greater than 0";
        }
        if (timeToCompleteSeconds == null || timeToCompleteSeconds <= 0) {
            return "Time to complete must be greater than 0 seconds";
        }
        if (expirySeconds == null || expirySeconds <= 0) {
            return "Expiry time must be greater than 0 seconds";
        }
        return null; // No validation errors
    }
}