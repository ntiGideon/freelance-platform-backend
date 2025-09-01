package com.freelance.categories.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateCategoryResponse(
    @JsonProperty("categoryId") String categoryId,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("snsTopicArn") String snsTopicArn) {}

