package com.freelance.jobs.exceptions;

/**
 * Exception thrown when attempting to perform operations on a job that is not in submitted status
 */
public class JobNotSubmittedException extends Exception {
    public JobNotSubmittedException(String message) {
        super(message);
    }
}