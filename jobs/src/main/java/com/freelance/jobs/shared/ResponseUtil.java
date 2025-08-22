package com.freelance.jobs.shared;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** Utility class for creating standardized API Gateway responses across all handlers */
public class ResponseUtil {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private static final Map<String, String> DEFAULT_HEADERS =
      Map.of(
          "Content-Type", "application/json",
          "Access-Control-Allow-Origin", "http://localhost:4200",
          "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH",
          "Access-Control-Allow-Headers", "Content-Type,Authorization,X-User-ID,X-User-Email,X-User-Role,Accept,X-Requested-With");

  private ResponseUtil() {}

  /**
   * Creates a success response with the given status code and body
   *
   * @param statusCode HTTP status code
   * @param body Response body object
   * @return APIGatewayProxyResponseEvent
   */
  public static APIGatewayProxyResponseEvent createSuccessResponse(int statusCode, Object body) {
    try {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(DEFAULT_HEADERS)
          .withBody(objectMapper.writeValueAsString(body));
    } catch (Exception e) {
      throw new RuntimeException("Error creating success response", e);
    }
  }

  /**
   * Creates an error response with the given status code and message
   *
   * @param statusCode HTTP status code
   * @param message Error message
   * @return APIGatewayProxyResponseEvent
   */
  public static APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    try {
      Map<String, String> errorBody = Map.of("error", message);
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(DEFAULT_HEADERS)
          .withBody(objectMapper.writeValueAsString(errorBody));
    } catch (Exception e) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody("{\"error\":\"Internal server error\"}");
    }
  }

  /**
   * Creates an error response with the given status code, message, and additional details
   *
   * @param statusCode HTTP status code
   * @param message Error message
   * @param details Additional error details
   * @return APIGatewayProxyResponseEvent
   */
  public static APIGatewayProxyResponseEvent createErrorResponse(
      int statusCode, String message, String details) {
    try {
      Map<String, String> errorBody =
          Map.of("error", message, "details", details != null ? details : "");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(DEFAULT_HEADERS)
          .withBody(objectMapper.writeValueAsString(errorBody));
    } catch (Exception e) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody("{\"error\":\"Internal server error\"}");
    }
  }
}

