package com.freelance.categories.entity;

public record CategoryEntity(
    String categoryId, String name, String description, String snsTopicArn, String createdAt) {
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String categoryId;
    private String name;
    private String description;
    private String snsTopicArn;
    private String createdAt;

    public Builder categoryId(String categoryId) {
      this.categoryId = categoryId;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder snsTopicArn(String snsTopicArn) {
      this.snsTopicArn = snsTopicArn;
      return this;
    }

    public Builder createdAt(String createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public CategoryEntity build() {
      return new CategoryEntity(categoryId, name, description, snsTopicArn, createdAt);
    }
  }
}

