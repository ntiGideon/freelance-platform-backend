package com.freelance.admin.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.admin.auth.AdminAuthUtils;
import com.freelance.admin.handlers.*;
import com.freelance.admin.mappers.RequestMapper;


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

      // Handle OPTIONS preflight requests for CORS
      if ("OPTIONS".equals(httpMethod)) {
        context.getLogger().log("Handling OPTIONS preflight request");
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withHeaders(Map.of(
                "Access-Control-Allow-Origin", "http://localhost:4200",
                "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH",
                "Access-Control-Allow-Headers", "Content-Type,Authorization,X-User-ID,X-User-Email,X-User-Role,Accept,X-Requested-With",
                "Access-Control-Max-Age", "86400"
            ))
            .withBody("");
      }

      // Extract user info from API Gateway request context (Cognito authorizer claims)
      String userId = RequestMapper.extractUserIdFromRequestContext(input, "admin");
      String userEmail = RequestMapper.extractUserEmailFromRequestContext(input);
      String userRole = RequestMapper.extractUserRoleFromRequestContext(input);

      if (userId == null) {
        return createErrorResponse(401, "Unauthorized: User ID not found");
      }

      // Validate that user has ADMIN role for admin operations
      if (!"ADMIN".equals(userRole)) {
        context.getLogger().log("Invalid user role for admin operations: " + userRole);
        return createErrorResponse(403, "Forbidden: ADMIN role required for admin operations");
      }

      context.getLogger().log("Admin user validation passed. Routing request...");

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
      return createErrorResponse(500, e.getMessage());
    }
  }



  private APIGatewayProxyResponseEvent routeAdminRequest(
          APIGatewayProxyRequestEvent input, Context context, String userId) {
    String path = input.getPath();
    String method = input.getHttpMethod();

    try {
      // Admin job creation
      if (path.equals("/admin/job/create") && method.equals("POST")) {
        return new CreateJobHandler().handleRequest(input, context);

        // Admin job listing and statistics
      } else if (path.equals("/admin/job") && method.equals("GET")) {
        return new ListAllJobsHandler().handleRequest(input, context);
      } else if (path.equals("/admin/job/statistics") && method.equals("GET")) {
        return new JobStatisticsHandler().handleRequest(input, context);

        // Admin individual job operations
      } else if (path.matches("/admin/job/[^/]+") && method.equals("GET")) {
        return new ViewJobHandler().handleRequest(input, context);
      } else if (path.matches("/admin/job/[^/]+/approve") && method.equals("POST")) {
        return new ApproveJobHandler().handleRequest(input, context);
      } else if (path.matches("/admin/job/[^/]+/reject") && method.equals("POST")) {
        return new RejectJobHandler().handleRequest(input, context);
      } else if (path.matches("/admin/job/[^/]+/relist") && method.equals("POST")) {
        return new RelistJobHandler().handleRequest(input, context);

      } else {
        return createErrorResponse(404, "Admin endpoint not found: " + method + " " + path);
      }
    } catch (Exception e) {
      context.getLogger().log("Error in admin route: " + e.getMessage());
      return createErrorResponse(500, e.getMessage());
    }
  }


  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(
            Map.of(
                "Content-Type", "application/json",
                "Access-Control-Allow-Origin", "http://localhost:4200",
                "Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH",
                "Access-Control-Allow-Headers", "Content-Type,Authorization,X-User-ID,X-User-Email,X-User-Role,Accept,X-Requested-With"))
        .withBody("{\"error\":\"" + message + "\"}");
  }
}

