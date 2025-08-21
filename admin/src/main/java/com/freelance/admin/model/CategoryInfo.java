package com.freelance.admin.model;

/**
 * Record representing category information needed for job creation
 */
public record CategoryInfo(
    String categoryId,
    String name,
    String snsTopicArn
) {
    
    /**
     * Check if category has a valid SNS topic
     */
    public boolean hasValidSnsTopicArn() {
        return snsTopicArn != null && !snsTopicArn.trim().isEmpty();
    }
}