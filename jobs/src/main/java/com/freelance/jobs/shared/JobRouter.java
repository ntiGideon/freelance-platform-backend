package com.freelance.jobs.shared;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.freelance.jobs.owners.*;
import com.freelance.jobs.seekers.*;
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

      // Extract user ID from headers or context
      String userId = extractUserId(input);
      if (userId == null) {
        return createErrorResponse(401, "Unauthorized: User ID not found");
      }

      // Route to appropriate handler based on path
      if (path.startsWith("/job/seeker")) {
        return routeSeekerRequest(input, context, userId);
      } else if (path.startsWith("/job/owner")) {
        return routeOwnerRequest(input, context, userId);
      } else {
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

    try {
      if (path.equals("/job/owner/create") && method.equals("POST")) {
        return new CreateJobHandler().handleRequest(input, context);
      } else if (path.equals("/job/owner/list") && method.equals("GET")) {
        return new com.freelance.jobs.owners.ListJobsHandler().handleRequest(input, context);
      } else if (path.matches("/job/owner/view/[^/]+") && method.equals("GET")) {
        return new com.freelance.jobs.owners.ViewJobHandler().handleRequest(input, context);
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

