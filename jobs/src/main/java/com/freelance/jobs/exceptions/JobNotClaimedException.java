package com.freelance.jobs.exceptions;

/**
 * Exception thrown when attempting to perform operations on a job that is not claimed by the current user
 */
public class JobNotClaimedException extends Exception {
    public JobNotClaimedException(String message) {
        super(message);
    }
}