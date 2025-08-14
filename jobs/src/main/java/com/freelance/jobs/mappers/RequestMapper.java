package com.freelance.jobs.mappers;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import java.util.Map;
import java.util.List;

public class RequestMapper {

    private RequestMapper() {}

    public static String extractJobIdFromPath(String path) {
        if (path != null && !path.isEmpty()) {
            String[] segments = path.split("/");
            if (segments.length > 0) {
                return segments[segments.length - 1];
            }
        }
        return null;
    }

    public static boolean isValidUserRole(String userRole) {
        return "USER".equals(userRole);
    }

    public static String extractOwnerIdFromContext(APIGatewayProxyRequestEvent input) {
        return extractUserIdFromRequestContext(input, "owner");
    }

    public static String extractSeekerIdFromContext(APIGatewayProxyRequestEvent input) {
        return extractUserIdFromRequestContext(input, "seeker");
    }

    public static String getQueryParameter(APIGatewayProxyRequestEvent input, String paramName, String defaultValue) {
        Map<String, String> queryParams = input.getQueryStringParameters();
        if (queryParams != null && queryParams.containsKey(paramName)) {
            return queryParams.get(paramName);
        }
        return defaultValue;
    }

    public static String getQueryParameter(APIGatewayProxyRequestEvent input, String paramName) {
        return getQueryParameter(input, paramName, null);
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