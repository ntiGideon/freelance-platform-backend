package com.freelanceplatform.models;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
public class JobCategory {
    
    private String categoryId;
    private String name;
    
    @DynamoDbPartitionKey
    public String getCategoryId () {
        return categoryId;
    }
    
    public void setCategoryId (String categoryId) {
        this.categoryId = categoryId;
    }
    
    public String getName () {
        return name;
    }
    
    public void setName (String name) {
        this.name = name;
    }
}