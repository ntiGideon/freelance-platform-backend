package com.freelance.jobs.mappers;

import com.freelance.jobs.entity.JobEntity;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Utility class for mapping between DynamoDB items and JobEntity objects
 */
public class JobEntityMapper {

    private JobEntityMapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Maps a DynamoDB item to a JobEntity object
     * 
     * @param item DynamoDB item map
     * @return JobEntity object
     */
    public static JobEntity mapToJobEntity(Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        
        return JobEntity.builder()
                .jobId(getStringValue(item, "jobId"))
                .ownerId(getStringValue(item, "ownerId"))
                .categoryId(getStringValue(item, "categoryId"))
                .name(getStringValue(item, "name"))
                .description(getStringValue(item, "description"))
                .payAmount(new java.math.BigDecimal(getStringValue(item, "payAmount")))
                .timeToCompleteSeconds(Long.valueOf(getStringValue(item, "timeToCompleteSeconds")))
                .expiryDate(getStringValue(item, "expiryDate"))
                .status(getStringValue(item, "status"))
                .createdAt(getStringValue(item, "createdAt"))
                .updatedAt(getStringValue(item, "updatedAt"))
                .claimerId(getStringValue(item, "claimerId"))
                .claimedAt(getStringValue(item, "claimedAt"))
                .submissionDeadline(getStringValue(item, "submissionDeadline"))
                .submittedAt(getStringValue(item, "submittedAt"))
                .build();
    }

    /**
     * Extracts string value from DynamoDB AttributeValue, handling both string and number types
     * 
     * @param item DynamoDB item map
     * @param key The attribute key
     * @return String value or null if not found
     */
    public static String getStringValue(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        if (value != null && value.s() != null) {
            return value.s();
        } else if (value != null && value.n() != null) {
            return value.n();
        }
        return null;
    }
}