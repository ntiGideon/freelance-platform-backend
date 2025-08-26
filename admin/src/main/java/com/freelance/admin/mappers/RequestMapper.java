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

        if (parts.length >= 5 && "job".equals(parts[2]) && "delete".equals(parts[4])) {
            return parts[3];
        }

        else if (parts.length >= 4 && "job".equals(parts[2])) {
            return parts[3];
        }
        return null;
    }

    /**
     * Extracts job ID from path for various endpoints
     */
    public static String extractJobIdFromPath(String path, String basePath) {
        if (path == null || basePath == null) {
            return null;
        }

        if (path.startsWith(basePath)) {
            String remainingPath = path.substring(basePath.length());
            if (remainingPath.startsWith("/")) {
                remainingPath = remainingPath.substring(1);
            }

            // Remove any trailing slashes or additional path segments
            String[] parts = remainingPath.split("/");
            if (parts.length > 0) {
                return parts[0];
            }
        }
        return null;
    }




    /**
     * Extracts user ID from the API Gateway request context headers
     *
     * @param input    API Gateway request event
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
     * @param input        API Gateway request event
     * @param paramName    Parameter name
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
     * @param input     API Gateway request event
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
            if (authorizer != null) {

                // Check multiple possible locations for the user ID
                String userId = extractUserIdFromAuthorizer(authorizer);
                if (userId != null) {
                    return userId;
                }
            }
        }
        return null;
    }

    private static String extractUserIdFromAuthorizer(Map<String, Object> authorizer) {
        // Try different possible locations for the user ID

        // 1. Check if claims are directly in authorizer
        if (authorizer.containsKey("claims")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");
                if (claims != null && claims.containsKey("sub")) {
                    return (String) claims.get("sub");
                }
            } catch (Exception e) {
                System.out.println("Error parsing claims: " + e.getMessage());
            }
        }

        // 2. Check if sub is directly in authorizer
        if (authorizer.containsKey("sub")) {
            return (String) authorizer.get("sub");
        }

        // 3. Check for Cognito username
        if (authorizer.containsKey("cognito:username")) {
            return (String) authorizer.get("cognito:username");
        }

        // 4. Check for username
        if (authorizer.containsKey("username")) {
            return (String) authorizer.get("username");
        }

        // 5. Check for user id in other common locations
        if (authorizer.containsKey("principalId")) {
            return (String) authorizer.get("principalId");
        }

        if (authorizer.containsKey("user_id")) {
            return (String) authorizer.get("user_id");
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
            if (authorizer != null) {
                // Check for Cognito User Pool claims (they come through differently)
                if (authorizer.containsKey("claims")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");
                    return extractRoleFromClaims(claims);
                }
                // For Lambda authorizers or other setups
                else if (authorizer.containsKey("cognito:groups")) {
                    Object groups = authorizer.get("cognito:groups");
                    return extractRoleFromGroups(groups);
                }
            }
        }
        return null;
    }

    private static String extractRoleFromClaims(Map<String, Object> claims) {
        if (claims.containsKey("cognito:groups")) {
            Object groups = claims.get("cognito:groups");
            return extractRoleFromGroups(groups);
        }
        return null;
    }

    private static String extractRoleFromGroups(Object groups) {
        if (groups instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> groupsList = (List<String>) groups;
            if (groupsList.contains("ADMIN")) {
                return "ADMIN";
            } else if (!groupsList.isEmpty()) {
                return groupsList.get(0); // Return first group as role
            }
        } else if (groups instanceof String) {
            String groupsString = (String) groups;
            if (groupsString.contains("ADMIN")) {
                return "ADMIN";
            }
            // Handle comma-separated groups
            String[] groupArray = groupsString.split(",");
            if (groupArray.length > 0) {
                return groupArray[0].trim();
            }
        }
        return null;
    }
}