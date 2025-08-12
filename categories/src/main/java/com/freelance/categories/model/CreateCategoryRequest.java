package com.freelance.categories.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateCategoryRequest(
    @JsonProperty("name") String name, @JsonProperty("description") String description) {
  public boolean isValid() {
    return name != null && !name.trim().isEmpty();
  }

  public String trimmedName() {
    return name != null ? name.trim() : null;
  }

  public String trimmedDescription() {
    return description != null ? description.trim() : null;
  }
}

