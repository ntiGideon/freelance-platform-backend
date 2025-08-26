package com.freelance.admin.mappers;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import java.util.Map;
import java.util.List;

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
            String userId = headers.get("x-user-id");
            if (userId != null && !userId.isEmpty()) {
                return userId;
            }
        }
        return null;
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

    public static boolean isValidUserRole(String userRole) {
        return "USER".equals(userRole) || "ADMIN".equals(userRole);
    }

    public static String extractUserIdFromRequestContext(APIGatewayProxyRequestEvent input, String userType) {
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = input.getRequestContext();
        if (requestContext != null) {
            Map<String, Object> authorizer = requestContext.getAuthorizer();
            if (authorizer != null && authorizer.containsKey("claims")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");
                if (claims != null && claims.containsKey("sub")) {
                    return (String) claims.get("sub");
                }
            }
        }
        return null;
    }

    public static String extractUserEmailFromRequestContext(APIGatewayProxyRequestEvent input) {
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = input.getRequestContext();
        if (requestContext != null) {
            Map<String, Object> authorizer = requestContext.getAuthorizer();
            if (authorizer != null && authorizer.containsKey("claims")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");
                if (claims != null && claims.containsKey("email")) {
                    return (String) claims.get("email");
                }
            }
        }
        return null;
    }

    public static String extractUserRoleFromRequestContext(APIGatewayProxyRequestEvent input) {
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = input.getRequestContext();
        if (requestContext != null) {
            Map<String, Object> authorizer = requestContext.getAuthorizer();
            if (authorizer != null && authorizer.containsKey("claims")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");
                if (claims != null && claims.containsKey("cognito:groups")) {
                    Object groups = claims.get("cognito:groups");
                    if (groups instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> groupsList = (List<String>) groups;
                        if (!groupsList.isEmpty()) {
                            return groupsList.get(0);
                        }
                    } else if (groups instanceof String) {
                        return (String) groups;
                    }
                }
            }
        }
        return null;
    }
}