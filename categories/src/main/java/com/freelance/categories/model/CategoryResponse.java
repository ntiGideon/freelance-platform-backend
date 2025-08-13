package com.freelance.categories.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response record representing a category
 */
public record CategoryResponse(
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("snsTopicArn") String snsTopicArn,
    @JsonProperty("createdAt") String createdAt
) {}