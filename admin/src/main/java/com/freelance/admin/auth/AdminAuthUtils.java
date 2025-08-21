package com.freelance.admin.auth;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListGroupsForUserResponse;

public class AdminAuthUtils {
    private static final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.create();
    private static final String USER_POOL_ID = System.getenv("USER_POOL_ID");
    private static final String ADMIN_GROUP_NAME = "ADMIN";

    public static boolean isAdminUser(String userId) {
        if (userId == null || USER_POOL_ID == null) {
            return false;
        }

        try {
            AdminListGroupsForUserRequest request = AdminListGroupsForUserRequest.builder()
                    .username(userId)
                    .userPoolId(USER_POOL_ID)
                    .build();

            AdminListGroupsForUserResponse response = cognitoClient.adminListGroupsForUser(request);
            return response.groups().stream()
                    .anyMatch(group -> ADMIN_GROUP_NAME.equals(group.groupName()));
        } catch (Exception e) {
            return false;
        }
    }
}