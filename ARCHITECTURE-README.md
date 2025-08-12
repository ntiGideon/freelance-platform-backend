# Freelance Platform - Architecture & Onboarding Guide

## Table of Contents
- [System Overview](#system-overview)
- [Architecture Diagram](#architecture-diagram)
- [Data Flow](#data-flow)
- [API Specifications](#api-specifications)
- [Database Schema](#database-schema)
- [Event-Driven Architecture](#event-driven-architecture)
- [Error Handling & Failure Scenarios](#error-handling--failure-scenarios)
- [Deployment Guide](#deployment-guide)
- [Monitoring & Observability](#monitoring--observability)

## System Overview

The Freelance Platform is a **serverless microservices architecture** built on AWS, designed to connect job owners with freelance workers. The system handles the complete job lifecycle from creation to completion, including automated expiration and timeout management.

### Key Features
- **Unified API Gateway**: Single entry point for all services
- **Event-Driven Architecture**: Decoupled components using EventBridge and SNS
- **Real-time Notifications**: Email notifications via SES for all job events
- **Automated Job Management**: Expiration and timeout handling with scheduled scanners
- **Category Management**: Dynamic SNS topic creation for job subscriptions
- **Audit Trail**: Complete job lifecycle tracking

### Core Services
1. **Categories Service**: Manages job categories and SNS topic creation
2. **Jobs Service**: Handles complete job lifecycle management
3. **Notification Service**: Centralized email notification system
4. **Scheduler Services**: Automated job expiration and timeout processing

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           CLIENT APPLICATIONS                                    │
│                      (Web, Mobile, API Consumers)                               │
└─────────────────────────────┬───────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    AWS API GATEWAY (Unified)                                    │
│                  https://api.freelanceplatform.com                             │
│                                                                                 │
│  Routes:                                                                        │
│  • POST /categories          • GET /categories                                 │
│  • POST /job/owner/create    • GET /job/owner/list                            │
│  • POST /job/seeker/claim    • GET /job/seeker/list                           │
└─────────────┬─────────────────────────────┬─────────────────────────────────────┘
              │                             │
              ▼                             ▼
┌─────────────────────────────┐   ┌─────────────────────────────────────────────┐
│     CATEGORIES SERVICE      │   │              JOBS SERVICE                   │
│                             │   │                                             │
│ • CreateCategoryHandler     │   │ • JobRouter (Main Router)                  │
│ • GetCategoriesHandler      │   │ • CreateJobHandler                         │
│ • CategoryStreamProcessor   │   │ • ClaimJobHandler                          │
└─────────┬───────────────────┘   │ • SubmitJobHandler                         │
          │                       │ • ApproveJobHandler                        │
          ▼                       │ • RejectJobHandler                         │
┌─────────────────────────────┐   │ • ViewJobHandler                           │
│     CategoriesTable         │   │ • ListJobsHandler                          │
│   (DynamoDB)                │   └─────────┬───────────────────────────────────┘
│                             │             │
│ PK: categoryId              │             ▼
│ GSI: NameIndex              │   ┌─────────────────────────────────────────────┐
│                             │   │           JobsTable (DynamoDB)              │
│ • categoryId                │   │                                             │
│ • name                      │   │ PK: jobId                                   │
│ • description               │   │ GSI: CategoryStatusIndex                    │
│ • snsTopicArn              │   │ GSI: OwnerJobsIndex                        │
│ • createdAt                 │   │ GSI: StatusExpiryIndex                     │
│                             │   │ GSI: ClaimerJobsIndex                      │
│ DynamoDB Streams ──────────┐│   │                                             │
└─────────────────────────────┘│   │ • jobId, ownerId, categoryId               │
                               │   │ • name, description, payAmount             │
                               │   │ • status, expiryDate, claimedAt            │
                               │   │ • timeToCompleteSeconds, ttl               │
                               │   └─────────────────────────────────────────────┘
                               │
                               ▼                             │
┌─────────────────────────────────────────────────────────┐ │
│            EVENTBRIDGE CUSTOM BUS                       │ │
│         (freelance-jobs-events)                         │ │
│                                                         │ │
│ Event Types:                                           │ │
│ • job.created    • job.claimed    • job.submitted     │ │
│ • job.approved   • job.rejected   • job.expired       │ │
│ • job.timedout                                        │ │
└─────────────┬───────────────────────────────────────────┘ │
              │                                             │
              ▼                                             │
┌─────────────────────────────────────────────────────────┐ │
│              EVENT PROCESSORS                           │ │
│                                                         │ │
│ • JobCreatedProcessor        • JobClaimedProcessor     │ │
│ • JobSubmittedProcessor      • JobApprovedProcessor    │ │
│ • JobRejectedProcessor                                 │ │
└─────────────┬───────────────────────────────────────────┘ │
              │                                             │
              ▼                                             │
┌─────────────────────────────────────────────────────────┐ │
│              SNS NOTIFICATION TOPIC                     │ │
│            (job-notifications)                          │ │
└─────────────┬───────────────────────────────────────────┘ │
              │                                             │
              ▼                                             │
┌─────────────────────────────────────────────────────────┐ │
│           NOTIFICATION HANDLER                          │ │
│                                                         │ │
│ • Processes all job events                             │ │
│ • Sends targeted email notifications                   │ │
│ • Fetches user info from UsersTable                   │ │
│ • Sends via Amazon SES                                 │ │
└─────────────────────────────────────────────────────────┘ │
                                                            │
┌─────────────────────────────────────────────────────────┐ │
│              SCHEDULER SERVICES                         │ │
│                                                         │ │
│ JobExpiryScanner (every 5 min)  JobTimeoutScanner     │ │
│         │                           (every 2 min)      │ │
│         ▼                               │               │ │
│ JobExpiryQueue ──► JobExpiryProcessor   │               │ │
│                           │             ▼               │ │
│                           │    JobTimeoutQueue ──► JobTimeoutProcessor
│                           │                         │   │ │
│                           └─────────────────────────┼───┘ │
│                                                     │     │
│                           Publishes to SNS ────────┘     │
└─────────────────────────────────────────────────────────┘ │
                                                            │
┌─────────────────────────────────────────────────────────┐ │
│                  DATA STORES                            │ │
│                                                         │ │
│ • UsersTable (External)     • CategoriesTable          │◄┘
│ • JobsTable                 • ClaimsTable               │
│                                                         │
│ All tables have DynamoDB Streams enabled               │
│ All tables have encryption and point-in-time recovery  │
└─────────────────────────────────────────────────────────┘
```

---

## Data Flow

### 1. Category Creation Flow
```
Client Request → API Gateway → CreateCategoryHandler → CategoriesTable
                                      ↓
CategoriesTable DynamoDB Stream → CategoryStreamProcessor → Creates SNS Topic
                                      ↓
                        Updates category with SNS Topic ARN
```

### 2. Job Creation Flow
```
Client Request → API Gateway → JobRouter → CreateJobHandler
                                  ↓
            Validates Category → Saves to JobsTable → Publishes job.created event
                                  ↓
            EventBridge → JobCreatedProcessor → Publishes to Category SNS Topic
```

### 3. Job Claim Flow
```
Client Request → API Gateway → JobRouter → ClaimJobHandler
                                  ↓
        Validates Job State → Updates JobsTable → Creates ClaimsTable entry
                                  ↓
                    Publishes job.claimed event → Notification Flow
```

### 4. Job Completion Flow
```
Freelancer Submission → SubmitJobHandler → Updates Job Status
                            ↓
              Publishes job.submitted event → Notifies Job Owner
                            ↓
Owner Review → ApproveJobHandler/RejectJobHandler → Final Status Update
                            ↓
              Publishes job.approved/job.rejected → Notifies Freelancer
```

### 5. Automated Job Management Flow
```
CloudWatch Event (Schedule) → JobExpiryScanner/JobTimeoutScanner
                                        ↓
            Scans DynamoDB for expired/timed-out jobs → Queues in SQS
                                        ↓
            SQS triggers Processor → Updates job status → Publishes events
                                        ↓
                              Notification flow continues
```

---

## API Specifications

### Base URL
```
https://{api-gateway-id}.execute-api.{region}.amazonaws.com/Prod
```

### Required Headers
```
Content-Type: application/json
X-User-ID: {user-id}                    # Required for all job operations
X-User-Email: {user-email}              # Optional, for enhanced tracking
X-User-Type: {owner|seeker}             # Optional, for role-based features
```

---

### Categories API

#### Create Category
```http
POST /categories
Content-Type: application/json

{
  "name": "Web Development",
  "description": "Jobs related to web development and programming"
}
```

**Response (201 Created):**
```json
{
  "categoryId": "web-development",
  "name": "Web Development", 
  "description": "Jobs related to web development and programming",
  "snsTopicMessage": "SNS topic will be created automatically"
}
```

**Error Responses:**
- `400 Bad Request`: Category name is required
- `409 Conflict`: Category already exists
- `500 Internal Server Error`: Database or processing error

#### List Categories
```http
GET /categories
```

**Response (200 OK):**
```json
{
  "categories": [
    {
      "categoryId": "web-development",
      "name": "Web Development",
      "description": "Jobs related to web development and programming",
      "snsTopicArn": "arn:aws:sns:region:account:web-development-topic",
      "createdAt": "2024-01-15T10:30:00Z"
    }
  ]
}
```

---

### Jobs API - Owner Endpoints

#### Create Job
```http
POST /job/owner/create
Content-Type: application/json
X-User-ID: owner-123

{
  "name": "Build REST API for E-commerce",
  "description": "Need a scalable REST API built with Node.js and MongoDB for an e-commerce platform",
  "categoryId": "web-development",
  "payAmount": 500.00,
  "timeToCompleteSeconds": 604800,     // 7 days
  "expirySeconds": 2592000             // 30 days from now
}
```

**Response (201 Created):**
```json
{
  "jobId": "job-123e4567-e89b-12d3-a456-426614174000",
  "ownerId": "owner-123",
  "categoryId": "web-development", 
  "name": "Build REST API for E-commerce",
  "description": "Need a scalable REST API built with Node.js and MongoDB for an e-commerce platform",
  "payAmount": 500.00,
  "timeToCompleteSeconds": 604800,
  "status": "open",
  "expiryDate": "2024-02-15T10:30:00Z",
  "createdAt": "2024-01-15T10:30:00Z",
  "snsTopicArn": "arn:aws:sns:region:account:web-development-topic"
}
```

#### List Owner Jobs
```http
GET /job/owner/list?status=open&limit=20&lastKey={pagination-key}
X-User-ID: owner-123
```

**Query Parameters:**
- `status` (optional): Filter by job status (`open`, `claimed`, `submitted`, `approved`, `rejected`, `expired`)
- `limit` (optional): Number of jobs to return (default: 50, max: 100)
- `lastKey` (optional): Pagination key for next page

#### View Job (Owner)
```http
GET /job/owner/view/{jobId}
X-User-ID: owner-123
```

#### Approve Job Submission
```http
POST /job/owner/approve/{jobId}
X-User-ID: owner-123

{
  "approvalNotes": "Great work! Exactly what we needed."
}
```

#### Reject Job Submission
```http
POST /job/owner/reject/{jobId}
X-User-ID: owner-123

{
  "rejectionReason": "Please add error handling for edge cases and update documentation."
}
```

#### Relist Expired Job
```http
POST /job/owner/relist/{jobId}
X-User-ID: owner-123

{
  "newExpirySeconds": 2592000,        // 30 days from now
  "updatedDescription": "Updated requirements based on feedback"  // optional
}
```

---

### Jobs API - Seeker Endpoints

#### List Available Jobs
```http
GET /job/seeker/list?categoryId=web-development&status=open&limit=20
X-User-ID: seeker-456
```

**Query Parameters:**
- `categoryId` (optional): Filter by category
- `status` (optional): Filter by status (typically `open` for seekers)
- `minPay` (optional): Minimum payment amount
- `maxPay` (optional): Maximum payment amount
- `limit` (optional): Number of jobs to return

#### View Job Details
```http
GET /job/seeker/view/{jobId}
X-User-ID: seeker-456
```

#### Claim Job
```http
POST /job/seeker/claim/{jobId}
X-User-ID: seeker-456

{
  "claimerNotes": "I have 5 years experience with Node.js and MongoDB. I can complete this within the timeframe."
}
```

**Response (200 OK):**
```json
{
  "jobId": "job-123e4567-e89b-12d3-a456-426614174000",
  "claimId": "claim-789",
  "claimerId": "seeker-456",
  "claimedAt": "2024-01-16T09:15:00Z",
  "submissionDeadline": "2024-01-23T09:15:00Z",
  "status": "claimed"
}
```

#### Submit Job Work
```http
POST /job/seeker/submit/{jobId}
X-User-ID: seeker-456

{
  "submissionNotes": "API completed with all requested features. Includes authentication, CRUD operations, and documentation.",
  "deliverableUrl": "https://github.com/username/ecommerce-api",
  "additionalFiles": ["documentation.pdf", "deployment-guide.md"]
}
```

---

## Database Schema

### CategoriesTable
```json
{
  "TableName": "{Environment}-CategoriesTable",
  "KeySchema": {
    "PartitionKey": "categoryId"
  },
  "Attributes": {
    "categoryId": "String",           // PK: "web-development"
    "name": "String",                 // "Web Development"
    "description": "String",          // Optional description
    "snsTopicArn": "String",         // Auto-created SNS topic ARN
    "createdAt": "String"            // ISO 8601 timestamp
  },
  "GlobalSecondaryIndexes": [
    {
      "IndexName": "NameIndex",
      "PartitionKey": "name"         // For duplicate name checking
    }
  ],
  "StreamSpecification": {
    "StreamViewType": "NEW_AND_OLD_IMAGES"
  }
}
```

### JobsTable  
```json
{
  "TableName": "{Environment}-JobsTable",
  "KeySchema": {
    "PartitionKey": "jobId"
  },
  "Attributes": {
    "jobId": "String",               // PK: UUID
    "ownerId": "String",             // Job owner user ID
    "categoryId": "String",          // Reference to CategoriesTable
    "name": "String",                // Job title
    "description": "String",         // Job description
    "payAmount": "Number",           // Payment amount (BigDecimal)
    "timeToCompleteSeconds": "Number", // Time allowed to complete
    "status": "String",              // open|claimed|submitted|approved|rejected|expired
    "expiryDate": "String",          // ISO 8601 timestamp
    "createdAt": "String",           // ISO 8601 timestamp
    "updatedAt": "String",           // ISO 8601 timestamp
    "claimerId": "String",           // Optional: who claimed the job
    "claimedAt": "String",           // Optional: when job was claimed
    "submissionDeadline": "String",  // Optional: when work must be submitted
    "submittedAt": "String",         // Optional: when work was submitted
    "ttl": "Number"                  // TTL for automatic cleanup
  },
  "GlobalSecondaryIndexes": [
    {
      "IndexName": "CategoryStatusIndex",
      "PartitionKey": "categoryId",
      "SortKey": "status"
    },
    {
      "IndexName": "OwnerJobsIndex", 
      "PartitionKey": "ownerId",
      "SortKey": "datePosted"
    },
    {
      "IndexName": "StatusExpiryIndex",
      "PartitionKey": "status",
      "SortKey": "expiryDate"
    },
    {
      "IndexName": "ClaimerJobsIndex",
      "PartitionKey": "claimerId", 
      "SortKey": "claimedAt"
    }
  ]
}
```

### ClaimsTable
```json
{
  "TableName": "{Environment}-ClaimsTable", 
  "KeySchema": {
    "PartitionKey": "claimId"
  },
  "Attributes": {
    "claimId": "String",             // PK: UUID
    "jobId": "String",               // Reference to JobsTable
    "claimerId": "String",           // User ID of claimer
    "status": "String",              // active|submitted|approved|rejected|timedout
    "claimedAt": "String",           // ISO 8601 timestamp
    "submissionDeadline": "String",  // ISO 8601 timestamp
    "submittedAt": "String",         // Optional: when work submitted
    "claimerNotes": "String",        // Optional: notes from claimer
    "submissionNotes": "String",     // Optional: notes with submission
    "deliverableUrl": "String",      // Optional: URL to deliverables
    "additionalFiles": "List",       // Optional: list of additional files
    "ttl": "Number"                  // TTL for cleanup
  },
  "GlobalSecondaryIndexes": [
    {
      "IndexName": "JobClaimsIndex",
      "PartitionKey": "jobId",
      "SortKey": "status"
    },
    {
      "IndexName": "ClaimerClaimsIndex",
      "PartitionKey": "claimerId",
      "SortKey": "claimedAt"
    },
    {
      "IndexName": "StatusDeadlineIndex",
      "PartitionKey": "status",
      "SortKey": "submissionDeadline"  // For timeout scanning
    }
  ]
}
```

### UsersTable (External)
```json
{
  "TableName": "{Environment}-UsersTable",
  "Note": "This table is external to this stack - managed separately",
  "KeySchema": {
    "PartitionKey": "userId"
  },
  "Attributes": {
    "userId": "String",              // PK: User identifier
    "email": "String",               // Required for notifications
    "name": "String",                // Display name
    "userType": "String",            // owner|seeker|both
    "createdAt": "String"
  }
}
```

---

## Event-Driven Architecture

### EventBridge Custom Bus: `freelance-jobs-events`

All job lifecycle events flow through a central EventBridge bus for loose coupling and scalability.

### Event Types and Schemas

#### 1. job.created
```json
{
  "version": "0",
  "source": "jobs-service",
  "detail-type": "job.created", 
  "detail": {
    "jobId": "job-123",
    "ownerId": "owner-123",
    "categoryId": "web-development",
    "categoryName": "Web Development",
    "snsTopicArn": "arn:aws:sns:...",
    "jobName": "Build REST API for E-commerce",
    "description": "...",
    "payAmount": 500.00,
    "timeToCompleteSeconds": 604800,
    "status": "open",
    "expiryDate": "2024-02-15T10:30:00Z",
    "createdAt": "2024-01-15T10:30:00Z",
    "jobUrl": "https://api.../job/owner/view/job-123"
  }
}
```

#### 2. job.claimed
```json
{
  "version": "0",
  "source": "jobs-service", 
  "detail-type": "job.claimed",
  "detail": {
    "jobId": "job-123",
    "ownerId": "owner-123", 
    "claimerId": "seeker-456",
    "jobName": "Build REST API for E-commerce",
    "payAmount": 500.00,
    "claimedAt": "2024-01-16T09:15:00Z",
    "submissionDeadline": "2024-01-23T09:15:00Z"
  }
}
```

#### 3. job.submitted
```json
{
  "version": "0",
  "source": "jobs-service",
  "detail-type": "job.submitted", 
  "detail": {
    "jobId": "job-123",
    "ownerId": "owner-123",
    "claimerId": "seeker-456", 
    "jobName": "Build REST API for E-commerce",
    "payAmount": 500.00,
    "submittedAt": "2024-01-22T14:30:00Z",
    "submissionNotes": "API completed with all features...",
    "deliverableUrl": "https://github.com/username/ecommerce-api"
  }
}
```

#### 4. job.approved
```json
{
  "version": "0",
  "source": "jobs-service",
  "detail-type": "job.approved",
  "detail": {
    "jobId": "job-123", 
    "ownerId": "owner-123",
    "claimerId": "seeker-456",
    "jobName": "Build REST API for E-commerce", 
    "payAmount": 500.00,
    "approvedAt": "2024-01-23T16:45:00Z",
    "approvalNotes": "Great work! Exactly what we needed."
  }
}
```

#### 5. job.rejected
```json
{
  "version": "0",
  "source": "jobs-service",
  "detail-type": "job.rejected",
  "detail": {
    "jobId": "job-123",
    "ownerId": "owner-123", 
    "claimerId": "seeker-456",
    "jobName": "Build REST API for E-commerce",
    "payAmount": 500.00,
    "rejectedAt": "2024-01-23T16:45:00Z",
    "rejectionReason": "Please add error handling..."
  }
}
```

#### 6. job.expired
```json
{
  "version": "0",
  "source": "jobs-service",
  "detail-type": "job.expired",
  "detail": {
    "jobId": "job-123",
    "ownerId": "owner-123",
    "jobName": "Build REST API for E-commerce",
    "payAmount": 500.00,
    "originalStatus": "open",
    "expiryDate": "2024-02-15T10:30:00Z",
    "expiredAt": "2024-02-15T10:35:00Z"
  }
}
```

#### 7. job.timedout
```json
{
  "version": "0",
  "source": "jobs-service", 
  "detail-type": "job.timedout",
  "detail": {
    "jobId": "job-123",
    "ownerId": "owner-123",
    "claimerId": "seeker-456",
    "jobName": "Build REST API for E-commerce",
    "payAmount": 500.00,
    "timeToCompleteSeconds": 604800,
    "claimedAt": "2024-01-16T09:15:00Z",
    "submissionDeadline": "2024-01-23T09:15:00Z", 
    "timedOutAt": "2024-01-23T09:20:00Z"
  }
}
```

### Event Processing Flow

1. **EventBridge Rules** trigger specific processors based on event patterns
2. **Event Processors** transform events and forward to SNS topics
3. **SNS Topics** distribute notifications to subscribers
4. **Notification Handler** processes all notifications and sends emails

---

## Error Handling & Failure Scenarios

### API Error Response Format
All API errors follow a consistent structure:
```json
{
  "error": "Descriptive error message",
  "errorCode": "SPECIFIC_ERROR_CODE",  // Optional
  "timestamp": "2024-01-15T10:30:00Z", // Optional
  "requestId": "abc-123-def"           // Optional
}
```

### HTTP Status Codes
- `200 OK`: Successful operation
- `201 Created`: Resource created successfully
- `400 Bad Request`: Invalid request data
- `401 Unauthorized`: Missing or invalid user authentication
- `403 Forbidden`: User not authorized for this operation
- `404 Not Found`: Resource not found
- `409 Conflict`: Resource already exists or conflict
- `500 Internal Server Error`: System error

### Specific Error Scenarios

#### Categories Service Errors
1. **Duplicate Category Name**
   - Status: `409 Conflict`
   - Error: "Category already exists"
   - Cause: Category name already exists (checked via NameIndex)

2. **Invalid Category Data**
   - Status: `400 Bad Request`
   - Error: "Category name is required"
   - Cause: Missing or empty category name

#### Jobs Service Errors
1. **User Authentication Errors**
   - Status: `401 Unauthorized`
   - Error: "Unauthorized: User ID not found"
   - Cause: Missing `X-User-ID` header

2. **Job Not Found**
   - Status: `404 Not Found`
   - Error: "Job not found"
   - Cause: Invalid jobId or job doesn't exist

3. **Job State Violations**
   - Status: `409 Conflict`
   - Examples:
     - "Job is not available for claiming"
     - "Job has not been submitted yet"
     - "Job is not in submitted status"

4. **Category Validation**
   - Status: `404 Not Found`
   - Error: "Category not found: {categoryId}"
   - Cause: Referenced category doesn't exist

5. **Ownership Violations**
   - Status: `403 Forbidden`
   - Error: "User is not the owner of this job"
   - Cause: User trying to access job they don't own

6. **Deadline Violations**
   - Status: `409 Conflict`
   - Error: "Submission deadline has passed"
   - Cause: Trying to submit work after deadline

### Failure Recovery Mechanisms

#### 1. Database Failures
- **DynamoDB Point-in-Time Recovery**: Enabled on all tables
- **Retry Logic**: Built into AWS SDK with exponential backoff
- **Circuit Breaker**: Lambda timeouts prevent cascading failures

#### 2. Event Processing Failures
- **EventBridge Retry**: Automatic retries with exponential backoff
- **Dead Letter Queues**: Failed events routed to DLQ for investigation
- **SNS Retry Policy**: Configurable retry attempts for notifications

#### 3. Notification Failures
- **SES Bounce Handling**: Invalid emails logged but don't fail the process
- **Graceful Degradation**: Notification failures don't affect core job processing
- **Audit Trail**: All notification attempts logged for troubleshooting

#### 4. Scheduled Task Failures
- **SQS Dead Letter Queues**: Failed expiry/timeout processing captured
- **CloudWatch Alarms**: Monitor failure rates and trigger alerts
- **Manual Intervention**: Failed items can be reprocessed manually

#### 5. API Gateway Failures
- **CORS Handling**: Proper CORS configuration for web clients
- **Rate Limiting**: Protection against abuse
- **Request Validation**: Basic validation at gateway level

### Monitoring and Alerting

#### CloudWatch Metrics
- Lambda function duration, errors, and invocations
- DynamoDB throttles, consumed capacity, and errors
- API Gateway 4XX and 5XX error rates
- SQS queue depth and message age

#### CloudWatch Alarms
- High error rates (>5% over 5 minutes)
- Long-running Lambda functions (>30 seconds)
- DynamoDB throttling events
- SQS queue depth exceeding thresholds

#### X-Ray Tracing
- End-to-end request tracing through the system
- Performance bottleneck identification
- Error correlation across services

---

## Deployment Guide

### Prerequisites
- AWS CLI configured with appropriate permissions
- SAM CLI installed
- Java 17 JDK
- Maven 3.8+

### Environment Setup
1. **Create Parameter Store Values**:
   ```bash
   aws ssm put-parameter --name "/freelance/dev/users-table-name" --value "dev-UsersTable" --type "String"
   aws ssm put-parameter --name "/freelance/prod/users-table-name" --value "prod-UsersTable" --type "String"
   ```

2. **SES Configuration**:
   ```bash
   # Verify sending email address
   aws ses verify-email-identity --email-address noreply@freelanceplatform.com
   
   # Move out of SES sandbox for production
   aws ses put-account-sending-enabled --enabled
   ```

### Build and Deploy
1. **Build the application**:
   ```bash
   sam build
   ```

2. **Deploy to development**:
   ```bash
   sam deploy --parameter-overrides Environment=dev UsersTableName=dev-UsersTable
   ```

3. **Deploy to production**:
   ```bash
   sam deploy --parameter-overrides Environment=prod UsersTableName=prod-UsersTable
   ```

### Post-Deployment Verification
1. **Test Category Creation**:
   ```bash
   curl -X POST https://your-api-url/categories \
     -H "Content-Type: application/json" \
     -d '{"name": "Test Category", "description": "Test"}'
   ```

2. **Verify EventBridge Rules**:
   ```bash
   aws events list-rules --event-bus-name dev-freelance-jobs-events
   ```

3. **Check Lambda Functions**:
   ```bash
   aws lambda list-functions --query 'Functions[?starts_with(FunctionName, `dev-`)]'
   ```

---

## Monitoring & Observability

### Key Metrics to Monitor

#### Business Metrics
- Jobs created per day/hour
- Job completion rate (approved/total jobs)
- Average time to job completion
- Category distribution of jobs
- User engagement (jobs per user)

#### Technical Metrics
- API Gateway response times and error rates
- Lambda function duration and error rates
- DynamoDB consumed capacity and throttles
- EventBridge rule execution success rates
- SQS queue depths and message processing times
- SES sending success rates

### Dashboard Setup
Create CloudWatch dashboards with:
- API Gateway metrics by endpoint
- Lambda function performance by service
- DynamoDB performance by table
- Event processing pipeline health
- Notification delivery success rates

### Log Analysis
Key log patterns to monitor:
```
[ERROR] Job creation failed: Category not found
[WARN] Notification delivery failed: Invalid email
[INFO] Job {jobId} expired and marked as expired
[INFO] Successfully processed {count} timeout jobs
```

### Alerting Strategy
1. **Critical Alerts** (immediate response):
   - API Gateway 5XX errors >1%
   - Lambda function failures >5%
   - DynamoDB throttling events

2. **Warning Alerts** (investigation needed):
   - High API response times >2s
   - SQS queue depth >50 messages
   - Notification failures >10%

3. **Info Alerts** (monitoring):
   - Daily job creation trends
   - User activity patterns
   - System resource utilization

---

This architecture provides a robust, scalable, and maintainable platform for freelance job management with comprehensive error handling, monitoring, and automated lifecycle management.