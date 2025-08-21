package com.freelance.admin.events;

import java.math.BigDecimal;

/**
 * Event published when a job expires (reaches its expiryDate)
 * Used for notification workflows via SQS + SNS
 */
public record JobExpiredEvent(
        String eventType,
        String jobId,
        String ownerId,
        String jobName,
        String description,
        BigDecimal payAmount,
        String expiryDate,
        String expiredAt,
        String categoryId,
        String originalStatus // Status before expiring (open, claimed, submitted)
) {
    public JobExpiredEvent {
        if (eventType == null) {
            eventType = "job.expired";
        }
    }
    
    public static JobExpiredEvent create(
            String jobId,
            String ownerId,
            String jobName,
            String description,
            BigDecimal payAmount,
            String expiryDate,
            String expiredAt,
            String categoryId,
            String originalStatus) {
        return new JobExpiredEvent(
                "job.expired",
                jobId,
                ownerId,
                jobName,
                description,
                payAmount,
                expiryDate,
                expiredAt,
                categoryId,
                originalStatus
        );
    }
}