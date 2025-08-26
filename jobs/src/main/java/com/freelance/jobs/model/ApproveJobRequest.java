package com.freelance.jobs.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request model for job approval with optional message
 */
public record ApproveJobRequest(
    @JsonProperty("message")
    String message
) {
    
    /**
     * Validates the request data
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        // Message is optional, but if provided, should not exceed reasonable length
        return message == null || message.length() <= 1000;
    }
    
    /**
     * Gets trimmed message or null if empty/whitespace
     * @return trimmed message or null
     */
    public String trimmedMessage() {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        return message.trim();
    }
}