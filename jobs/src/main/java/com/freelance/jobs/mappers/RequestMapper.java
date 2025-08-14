package com.freelance.jobs.mappers;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import java.util.Map;

/**
 * Utility class for extracting common parameters from API Gateway request events
 */
public class RequestMapper {

    private RequestMapper() {
        // Utility class - prevent instantiation
    }

    /**
     * Extracts job ID from the API Gateway path
     * Expected path format: /.../.../.../{jobId}
     * 
     * @param path The request path
     * @return Job ID or null if not found
     */
    public static String extractJobIdFromPath(String path) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length >= 5) {
            return parts[4];
        }
        return null;
    }

    /**
     * Extracts user ID from the API Gateway request context headers
     * 
     * @param input API Gateway request event
     * @param userType Type of user ("owner" or "seeker") - for documentation only
     * @return User ID or null if not found
     */
    public static String extractUserIdFromContext(APIGatewayProxyRequestEvent input, String userType) {
        Map<String, String> headers = input.getHeaders();
        if (headers != null) {
            // Try both case variations as headers can be normalized differently
            String userId = headers.get("X-User-ID");
            if (userId == null || userId.isEmpty()) {
                userId = headers.get("x-user-id");
            }
            if (userId != null && !userId.isEmpty()) {
                return userId;
            }
        }
        return null;
    }

    /**
     * Extracts user email from the API Gateway request context headers
     * Set by Cognito authorizer mapping template
     * 
     * @param input API Gateway request event
     * @return User email or null if not found
     */
    public static String extractUserEmailFromContext(APIGatewayProxyRequestEvent input) {
        Map<String, String> headers = input.getHeaders();
        if (headers != null) {
            // Try both case variations as headers can be normalized differently
            String userEmail = headers.get("X-User-Email");
            if (userEmail == null || userEmail.isEmpty()) {
                userEmail = headers.get("x-user-email");
            }
            return userEmail; // Can be null, email might not always be available
        }
        return null;
    }

    /**
     * Extracts user role from the API Gateway request context headers
     * Set by Cognito authorizer mapping template
     * 
     * @param input API Gateway request event
     * @return User role or null if not found
     */
    public static String extractUserRoleFromContext(APIGatewayProxyRequestEvent input) {
        Map<String, String> headers = input.getHeaders();
        if (headers != null) {
            // Try both case variations as headers can be normalized differently
            String userRole = headers.get("X-User-Role");
            if (userRole == null || userRole.isEmpty()) {
                userRole = headers.get("x-user-role");
            }
            return userRole;
        }
        return null;
    }

    /**
     * Validates that the user has the required role for job operations
     * 
     * @param userRole The user's role from Cognito groups
     * @return true if user has USER role, false otherwise
     */
    public static boolean isValidUserRole(String userRole) {
        // Only allow USER role for job operations
        // ADMIN users should not access job endpoints directly
        return "USER".equals(userRole);
    }

    /**
     * Extracts owner ID from the API Gateway request context
     * 
     * @param input API Gateway request event
     * @return Owner ID
     */
    public static String extractOwnerIdFromContext(APIGatewayProxyRequestEvent input) {
        return extractUserIdFromContext(input, "owner");
    }

    /**
     * Extracts seeker ID from the API Gateway request context
     * 
     * @param input API Gateway request event
     * @return Seeker ID
     */
    public static String extractSeekerIdFromContext(APIGatewayProxyRequestEvent input) {
        return extractUserIdFromContext(input, "seeker");
    }

    /**
     * Extracts query parameter from API Gateway request event
     * 
     * @param input API Gateway request event
     * @param paramName Parameter name
     * @param defaultValue Default value if parameter not found
     * @return Parameter value or default
     */
    public static String getQueryParameter(APIGatewayProxyRequestEvent input, String paramName, String defaultValue) {
        Map<String, String> queryParams = input.getQueryStringParameters();
        if (queryParams != null && queryParams.containsKey(paramName)) {
            return queryParams.get(paramName);
        }
        return defaultValue;
    }

    /**
     * Extracts query parameter from API Gateway request event
     * 
     * @param input API Gateway request event
     * @param paramName Parameter name
     * @return Parameter value or null
     */
    public static String getQueryParameter(APIGatewayProxyRequestEvent input, String paramName) {
        return getQueryParameter(input, paramName, null);
    }
}