package com.freelance.admin.exceptions;

/**
 * Exception thrown when attempting to submit a job after the submission deadline has passed
 */
public class SubmissionDeadlineExceededException extends Exception {
    public SubmissionDeadlineExceededException(String message) {
        super(message);
    }
}