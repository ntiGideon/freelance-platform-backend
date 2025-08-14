package com.freelance.jobs.mappers;

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
        return extractUserIdFromRequestContext(input, "owner");
    }

    /**
     * Extracts seeker ID from the API Gateway request context
     * 
     * @param input API Gateway request event
     * @return Seeker ID
     */
    public static String extractSeekerIdFromContext(APIGatewayProxyRequestEvent input) {
        return extractUserIdFromRequestContext(input, "seeker");
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

    // ===== NEW METHODS FOR API GATEWAY REQUEST CONTEXT =====

    /**
     * Extracts user ID from API Gateway request context (Cognito authorizer claims)
     * This is the preferred method for getting user info from standard aws_proxy integration
     * 
     * @param input API Gateway request event
     * @param userType Type of user (for compatibility, not used in context extraction)
     * @return User ID from Cognito claims or null if not found
     */
    public static String extractUserIdFromRequestContext(APIGatewayProxyRequestEvent input, String userType) {
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = input.getRequestContext();
        if (requestContext != null) {
            APIGatewayProxyRequestEvent.RequestIdentity identity = requestContext.getIdentity();
            if (identity != null) {
                // Try to get from authorizer claims
                Map<String, Object> authorizer = requestContext.getAuthorizer();
                if (authorizer != null && authorizer.containsKey("claims")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> claims = (Map<String, Object>) authorizer.get("claims");
                    if (claims != null && claims.containsKey("sub")) {
                        return (String) claims.get("sub");
                    }
                }
            }
        }
        
        // Fallback to header-based extraction for backward compatibility
        return extractUserIdFromContext(input, userType);
    }

    /**
     * Extracts user email from API Gateway request context (Cognito authorizer claims)
     * 
     * @param input API Gateway request event
     * @return User email from Cognito claims or null if not found
     */
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
        
        // Fallback to header-based extraction for backward compatibility
        return extractUserEmailFromContext(input);
    }

    /**
     * Extracts user role from API Gateway request context (Cognito authorizer claims)
     * 
     * @param input API Gateway request event
     * @return User role from Cognito groups or null if not found
     */
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
                            return groupsList.get(0); // Return first group
                        }
                    } else if (groups instanceof String) {
                        return (String) groups;
                    }
                }
            }
        }
        
        // Fallback to header-based extraction for backward compatibility
        return extractUserRoleFromContext(input);
    }
}