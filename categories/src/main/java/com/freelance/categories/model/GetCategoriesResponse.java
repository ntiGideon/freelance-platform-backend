package com.freelance.categories.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record GetCategoriesResponse(
    @JsonProperty("categories") List<CategoryResponse> categories,
    @JsonProperty("count") int count) {}

