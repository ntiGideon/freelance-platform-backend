package com.freelance.jobs.exceptions;

/**
 * Exception thrown when attempting to claim a job that is not available for claiming
 */
public class JobNotAvailableException extends Exception {
    public JobNotAvailableException(String message) {
        super(message);
    }
}