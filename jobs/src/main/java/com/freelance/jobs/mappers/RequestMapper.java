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
}