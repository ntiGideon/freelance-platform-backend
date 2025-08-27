package com.freelance.jobs.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.jobs.owners.*;
import com.freelance.jobs.seekers.*;
import com.freelance.jobs.overview.OverviewHandler;
import com.freelance.jobs.mappers.RequestMapper;
import java.util.Map;

/**
 * Main router that handles all job-related API Gateway requests Routes to appropriate handlers
 * based on path (/job/seeker/* or /job/owner/*)
 */
public class JobRouter
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
      String userId = RequestMapper.extractUserIdFromRequestContext(input, "user");
      String userEmail = RequestMapper.extractUserEmailFromRequestContext(input);
      String userRole = RequestMapper.extractUserRoleFromRequestContext(input);
      
      if (userId == null) {
        return createErrorResponse(401, "Unauthorized: User ID not found");
      }
      
      // Validate that user has USER role for job operations
      if (!RequestMapper.isValidUserRole(userRole)) {
        context.getLogger().log("Invalid user role: " + userRole);
        return createErrorResponse(403, "Forbidden: USER role required for job operations");
      }

      context.getLogger().log("User validation passed. Routing request...");

      // Route to appropriate handler based on path
      if (path.startsWith("/job/seeker")) {
        context.getLogger().log("Routing to seeker handler");
        return routeSeekerRequest(input, context, userId);
      } else if (path.startsWith("/job/owner")) {
        context.getLogger().log("Routing to owner handler");
        return routeOwnerRequest(input, context, userId);
      } else if (path.equals("/job/overview") && httpMethod.equals("GET")) {
        context.getLogger().log("Routing to overview handler");
        return new OverviewHandler().handleRequest(input, context);
      } else {
        context.getLogger().log("Path not found: " + path);
        return createErrorResponse(404, "Path not found: " + path);
      }

    } catch (Exception e) {
      context.getLogger().log("Error processing request: " + e.getMessage());
      e.printStackTrace();
      return createErrorResponse(500, "Internal server error");
    }
  }

  private APIGatewayProxyResponseEvent routeSeekerRequest(
      APIGatewayProxyRequestEvent input, Context context, String userId) {
    String path = input.getPath();
    String method = input.getHttpMethod();

    try {
      if (path.equals("/job/seeker/list") && method.equals("GET")) {
        return new com.freelance.jobs.seekers.ListJobsHandler().handleRequest(input, context);
      } else if (path.matches("/job/seeker/view/[^/]+") && method.equals("GET")) {
        return new com.freelance.jobs.seekers.ViewJobHandler().handleRequest(input, context);
      } else if (path.matches("/job/seeker/claim/[^/]+") && method.equals("POST")) {
        return new ClaimJobHandler().handleRequest(input, context);
      } else if (path.matches("/job/seeker/submit/[^/]+") && method.equals("POST")) {
        return new SubmitJobHandler().handleRequest(input, context);
      } else {
        return createErrorResponse(404, "Seeker endpoint not found: " + method + " " + path);
      }
    } catch (Exception e) {
      context.getLogger().log("Error in seeker route: " + e.getMessage());
      return createErrorResponse(500, "Error processing seeker request");
    }
  }

  private APIGatewayProxyResponseEvent routeOwnerRequest(
      APIGatewayProxyRequestEvent input, Context context, String userId) {
    String path = input.getPath();
    String method = input.getHttpMethod();

    context.getLogger().log("Owner route - path: " + path + ", method: " + method);

    try {
      if (path.equals("/job/owner/create") && method.equals("POST")) {
        context.getLogger().log("Calling CreateJobHandler...");
        APIGatewayProxyResponseEvent response = new CreateJobHandler().handleRequest(input, context);
        context.getLogger().log("CreateJobHandler completed");
        return response;
      } else if (path.equals("/job/owner/list") && method.equals("GET")) {
        return new com.freelance.jobs.owners.ListJobsHandler().handleRequest(input, context);
      } else if (path.matches("/job/owner/view/[^/]+") && method.equals("GET")) {
        return new com.freelance.jobs.owners.ViewJobHandler().handleRequest(input, context);
      } else if (path.matches("/job/owner/edit/[^/]+") && method.equals("PUT")) {
        return new com.freelance.jobs.owners.EditJobHandler().handleRequest(input, context);
      } else if (path.matches("/job/owner/approve/[^/]+") && method.equals("POST")) {
        return new ApproveJobHandler().handleRequest(input, context);
      } else if (path.matches("/job/owner/reject/[^/]+") && method.equals("POST")) {
        return new RejectJobHandler().handleRequest(input, context);
      } else if (path.matches("/job/owner/relist/[^/]+") && method.equals("POST")) {
        return new RelistJobHandler().handleRequest(input, context);
      } else {
        return createErrorResponse(404, "Owner endpoint not found: " + method + " " + path);
      }
    } catch (Exception e) {
      context.getLogger().log("Error in owner route: " + e.getMessage());
      return createErrorResponse(500, "Error processing owner request");
    }
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

