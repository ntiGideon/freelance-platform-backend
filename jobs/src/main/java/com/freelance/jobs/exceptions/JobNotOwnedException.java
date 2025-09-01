package com.freelance.jobs.exceptions;

/**
 * Exception thrown when attempting to perform operations on a job that is not owned by the current user
 */
public class JobNotOwnedException extends Exception {
    public JobNotOwnedException(String message) {
        super(message);
    }
}