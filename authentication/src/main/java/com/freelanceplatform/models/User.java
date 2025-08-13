package com.freelanceplatform.models;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

@DynamoDbBean
public class User implements Serializable {
    @Serial
    private static final long serialVersionUID = 4517148380012406558L;
    
    private String cognitoId;
    private String userId;
    private String email;
    private String firstname;
    private String middlename;
    private String lastname;
    private String phonenumber;
    private List<String> jobCategoryIds;
    private String role;
    
    @DynamoDbPartitionKey
    public String getUserId() {
        return userId;
    }
    
    public void setCognitoId(String cognitoId) {
        this.cognitoId = cognitoId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    @DynamoDbSecondaryPartitionKey(indexNames = "EmailIndex")
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public void setRole(String role){
        this.role = role;
    }
    
    public void setFirstname(String firstname) {
        this.firstname = firstname;
    }
    
    public void setMiddlename(String middlename) {
        this.middlename = middlename;
    }
    
    public void setLastname(String lastname) {
        this.lastname = lastname;
    }
    
    public void setPhonenumber (String phonenumber) {
        this.phonenumber = phonenumber;
    }
    
    public void setJobCategoryIds (List<String> jobCategoryIds) {
        this.jobCategoryIds = jobCategoryIds;
    }
}