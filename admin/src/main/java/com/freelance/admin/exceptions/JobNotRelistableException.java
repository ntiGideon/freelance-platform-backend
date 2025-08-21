package com.freelance.admin.exceptions;

/**
 * Exception thrown when attempting to relist a job that cannot be relisted
 */
public class JobNotRelistableException extends Exception {
    public JobNotRelistableException(String message) {
        super(message);
    }
}