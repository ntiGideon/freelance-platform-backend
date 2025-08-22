package com.freelance.admin.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.auth.AdminAuthUtils;
import com.freelance.admin.handlers.*;


import java.util.Map;

/**
 * Main router that handles all job-related API Gateway requests Routes to appropriate handlers
 * based on path (/job/seeker/* or /job/owner/*)
 */
public class JobAdminRouter
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent input, Context context) {
    try {
      String path = input.getPath();
      String httpMethod = input.getHttpMethod();

      context.getLogger().log("Processing request: " + httpMethod + " " + path);

      String userId = extractUserId(input);

      if (userId == null) {
        return createErrorResponse(401, "Unauthorized: User ID not found");
      }

      if(!AdminAuthUtils.isAdminUser(userId)){
        return createErrorResponse(403, "Forbidden: User does not belongs to admin group!");
      }

      // Route to appropriate handler based on path
      if (path.startsWith("/admin")) {
        return routeAdminRequest(input, context, userId);
      } else {
        return createErrorResponse(404, "Path not found: " + path);
      }

    } catch (Exception e) {
      context.getLogger().log("Error processing request: " + e.getMessage());
      e.printStackTrace();
      return createErrorResponse(500, "Internal server error");
    }
  }



  private APIGatewayProxyResponseEvent routeAdminRequest(
          APIGatewayProxyRequestEvent input, Context context, String userId) {
    String path = input.getPath();
    String method = input.getHttpMethod();

    try {
      // Admin job creation
      if (path.equals("/admin") && method.equals("POST")) {
        return new CreateJobHandler().handleRequest(input, context);

        // Admin job listing and statistics
      } else if (path.equals("/admin") && method.equals("GET")) {
        return new ListAllJobsHandler().handleRequest(input, context);
      } else if (path.equals("/admin/statistics") && method.equals("GET")) {
        return new JobStatisticsHandler().handleRequest(input, context);

        // Admin individual job operations
      } else if (path.matches("/admin/[^/]+") && method.equals("GET")) {
        return new ViewJobHandler().handleRequest(input, context);
      } else if (path.matches("/admin/[^/]+/approve") && method.equals("POST")) {
        return new ApproveJobHandler().handleRequest(input, context);
      } else if (path.matches("/admin/[^/]+/reject") && method.equals("POST")) {
        return new RejectJobHandler().handleRequest(input, context);
      } else if (path.matches("/admin/[^/]+/relist") && method.equals("POST")) {
        return new RelistJobHandler().handleRequest(input, context);

      } else {
        return createErrorResponse(404, "Admin endpoint not found: " + method + " " + path);
      }
    } catch (Exception e) {
      context.getLogger().log("Error in admin route: " + e.getMessage());
      return createErrorResponse(500, "Error processing admin request");
    }
  }

  private String extractUserId(APIGatewayProxyRequestEvent input) {
    Map<String, String> headers = input.getHeaders();
    if (headers != null) {
      String userId = headers.get("x-user-id");
      if (userId != null && !userId.isEmpty()) {
        return userId;
      }
    }
    return null;
  }

  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(
            Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "*"))
        .withBody("{\"error\":\"" + message + "\"}");
  }
}

