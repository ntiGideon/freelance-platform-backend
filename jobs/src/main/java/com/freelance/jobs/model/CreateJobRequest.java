package com.freelance.jobs.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Request record for creating a new job
 */
public record CreateJobRequest(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("categoryId") String categoryId,
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
               categoryId != null && !categoryId.trim().isEmpty() &&
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
    
    public String trimmedCategoryId() {
        return categoryId != null ? categoryId.trim() : null;
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
        if (categoryId == null || categoryId.trim().isEmpty()) {
            return "Category ID is required";
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