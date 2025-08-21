package com.freelance.admin.events;

import java.math.BigDecimal;

/**
 * Event published when a job times out (time_to_complete expires while claimed)
 * Used for notification workflows and status reversion
 */
public record JobTimedOutEvent(
        String eventType,
        String jobId,
        String ownerId,
        String claimerId,
        String jobName,
        String description,
        BigDecimal payAmount,
        String claimedAt,
        String submissionDeadline,
        String timedOutAt,
        Long timeToCompleteSeconds,
        String categoryId
) {
    public JobTimedOutEvent {
        if (eventType == null) {
            eventType = "job.timedout";
        }
    }
    
    public static JobTimedOutEvent create(
            String jobId,
            String ownerId,
            String claimerId,
            String jobName,
            String description,
            BigDecimal payAmount,
            String claimedAt,
            String submissionDeadline,
            String timedOutAt,
            Long timeToCompleteSeconds,
            String categoryId) {
        return new JobTimedOutEvent(
                "job.timedout",
                jobId,
                ownerId,
                claimerId,
                jobName,
                description,
                payAmount,
                claimedAt,
                submissionDeadline,
                timedOutAt,
                timeToCompleteSeconds,
                categoryId
        );
    }
}