package com.freelance.admin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RejectJobRequest {

    private final String reason;

    @JsonCreator
    public RejectJobRequest(@JsonProperty("reason") String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public boolean isValid() {
        if (reason == null || reason.trim().isEmpty()) {
            return false;
        }
        return reason.length() <= 1000;
    }

    public String getValidationError() {
        if (reason == null || reason.trim().isEmpty()) {
            return "Rejection reason cannot be empty.";
        }
        if (reason.length() > 1000) {
            return "Rejection reason cannot be longer than 1000 characters.";
        }
        return null;
    }

    public String trimmedReason() {
        return reason != null ? reason.trim() : null;
    }
}
