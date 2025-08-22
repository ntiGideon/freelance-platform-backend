package com.freelance.admin.exceptions;

/**
 * Exception thrown when attempting to claim a job that has already been claimed by another user
 */
public class JobAlreadyClaimedException extends Exception {
    public JobAlreadyClaimedException(String message) {
        super(message);
    }
}