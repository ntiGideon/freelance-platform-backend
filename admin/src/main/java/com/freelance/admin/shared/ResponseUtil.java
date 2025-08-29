package com.freelance.admin.shared;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class ResponseUtil {

  private ResponseUtil() {}

  private static final ObjectMapper objectMapper = new ObjectMapper();

  public static APIGatewayProxyResponseEvent createSuccessResponse(int statusCode, Object body) {
    try {
      String responseBody = objectMapper.writeValueAsString(body);

      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(getCorsHeaders())
          .withBody(responseBody);

    } catch (Exception e) {
      return createErrorResponse(500, "Error serializing response: " + e.getMessage());
    }
  }

  public static APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(getCorsHeaders())
        .withBody("{\"error\":\"" + message + "\"}");
  }

  private static Map<String, String> getCorsHeaders() {
    return Map.of(
        "Content-Type", "application/json",
        "Access-Control-Allow-Origin", "https://staging-test.d2j19f2rl4mf4c.amplifyapp.com",
        "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH",
        "Access-Control-Allow-Headers",
            "Content-Type,Authorization,X-User-ID,X-User-Email,X-User-Role,Accept,X-Requested-With");
  }
}

