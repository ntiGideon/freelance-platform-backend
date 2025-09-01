package com.freelance.payment.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Event record for payment.completed events
 */
public record PaymentCompletedEvent(
        @JsonProperty("eventType") String eventType,
        @JsonProperty("paymentId") String paymentId,
        @JsonProperty("jobId") String jobId,
        @JsonProperty("jobName") String jobName,
        @JsonProperty("userId") String userId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("paymentDate") String paymentDate,
        @JsonProperty("ownerId") String ownerId) {

    public PaymentCompletedEvent(
            String paymentId,
            String jobId,
            String jobName,
            String userId,
            BigDecimal amount,
            String paymentDate,
            String ownerId) {
        this("payment.completed", paymentId, jobId, jobName, userId, amount, paymentDate, ownerId);
    }
}