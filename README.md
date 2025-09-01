# 🚀 Freelance Job/Task Platform

A comprehensive serverless freelance platform built with AWS SAM, featuring job posting, claiming, payment processing, and real-time notifications. Built using event-driven architecture with full disaster recovery capabilities.

## 📋 Table of Contents

- [Architecture Overview](#architecture-overview)
- [Application Flow](#application-flow)
- [User Workflows](#user-workflows)
- [Technical Stack](#technical-stack)
- [Getting Started](#getting-started)
- [API Endpoints](#api-endpoints)
- [Event-Driven Architecture](#event-driven-architecture)
- [Disaster Recovery](#disaster-recovery)
- [Deployment](#deployment)
- [Environment Configuration](#environment-configuration)

---

## 🏗️ Architecture Overview

The platform is built using **serverless microservices architecture** with the following key components:

### Core Services
- **Authentication Service**: AWS Cognito with user pools and groups
- **Job Management Service**: CRUD operations for jobs with status management
- **Payment Processing Service**: Automated payment workflows
- **Notification Service**: SNS-based email and category notifications
- **Admin Service**: Administrative functions and statistics
- **Scheduler Services**: Job expiry and timeout management

### AWS Services Used
- **API Gateway**: REST API with CORS and Cognito authorization
- **Lambda Functions**: Java 17 serverless functions
- **DynamoDB**: NoSQL database with backup strategies
- **EventBridge**: Event-driven communication
- **SNS**: Notification system with topic filtering
- **SQS**: Queue-based processing (FIFO)
- **Step Functions**: Workflow orchestration
- **AWS Amplify**: Frontend hosting with CI/CD
- **AWS Backup**: Automated disaster recovery

---

## 🔄 Application Flow

### 1. User Registration & Onboarding
```
User Signs Up → Cognito Verification → Step Function Triggered
    ↓
Generate User ID → Save User Data → Create Payment Account → Subscribe to SNS Topics
    ↓
User Receives Welcome Email & Account Details
```

### 2. Job Creation Workflow
```
Job Owner Creates Job → Validation → Save to DynamoDB
    ↓
EventBridge Event Published → Category-Based SNS Notification
    ↓
Email Sent to Subscribed Job Seekers
```

### 3. Job Claiming & Processing
```
Job Seeker Claims Job → Status: "claimed" → Timer Starts
    ↓
Job Seeker Submits Work → Status: "submitted" → Owner Notification
    ↓
Owner Reviews → Approve/Reject Decision
```

### 4. Payment Processing
```
Job Approved → Payment Event Triggered → Payment Record Created
    ↓
Account Balance Updated → Payment Confirmation Email
    ↓
Payment Trail Stored for Audit
```

### 5. Job Expiry & Timeout Management
```
Scheduled Scanner (EventBridge) → Identifies Expired/Timed-out Jobs
    ↓
SQS Queue Processing → Status Updates → Notifications
    ↓
Jobs Return to "open" Status (Available for Claiming)
```

---

## 👥 User Workflows

### **Job Seekers (USER Role)**
1. **Browse Available Jobs**
   - GET `/job/seeker/list?type=available`
   - Filter by category, payment amount, deadline
   
2. **Claim a Job**
   - POST `/job/seeker/claim/{jobId}`
   - Only one person can claim a job at a time
   - Countdown timer starts for completion
   
3. **Submit Completed Work**
   - POST `/job/seeker/submit/{jobId}`
   - Upload work details and files
   - Job status changes to "submitted"
   
4. **View Job History**
   - GET `/job/seeker/list?type=completed`
   - Track earnings and payment history

### **Job Owners (USER Role)**
1. **Post New Jobs**
   - POST `/job/owner/create`
   - Set payment amount, deadline, category
   - Automatic notification to relevant seekers
   
2. **Manage Posted Jobs**
   - GET `/job/owner/list?type=posted`
   - View applications and submissions
   
3. **Review & Approve Work**
   - GET `/job/owner/view/{jobId}`
   - POST `/job/owner/approve/{jobId}` - Triggers payment
   - POST `/job/owner/reject/{jobId}` - Job returns to open
   
4. **Relist Expired Jobs**
   - POST `/job/owner/relist/{jobId}`

### **Platform Administrators (ADMIN Role)**
1. **System Overview**
   - GET `/admin/statistics` - Platform metrics
   - Monitor job completion rates, user activity
   
2. **Job Management**
   - Full CRUD operations on all jobs
   - Override job statuses when needed
   - Handle disputes and issues

---

## 🛠️ Technical Stack

### **Backend**
- **Runtime**: Java 17
- **Framework**: AWS SAM (Serverless Application Model)
- **Database**: Amazon DynamoDB with GSI
- **Event Processing**: EventBridge + SNS + SQS
- **Authentication**: AWS Cognito User Pools
- **API**: Amazon API Gateway with CORS

### **Frontend**
- **Framework**: Angular 17
- **Hosting**: AWS Amplify with GitHub integration
- **Build**: Automated CI/CD with standard Angular build
- **Authentication**: Cognito SDK integration

### **Infrastructure as Code**
- **Template**: AWS SAM YAML template
- **CI/CD**: GitHub Actions (dev & prod pipelines)
- **Secrets**: AWS Secrets Manager
- **Monitoring**: CloudWatch integration

---

## 🚀 Getting Started

### Prerequisites
- Java 17+ installed
- AWS CLI configured with appropriate permissions
- AWS SAM CLI installed
- Node.js 18+ (for frontend)

### 1. Clone Repository
```bash
git clone https://github.com/elitekaycy/job-hub-freelance.git
cd aws-microservice-may-cohort-group1
```

### 2. Configure Environment
```bash
# Copy sample configuration
cp samconfig.toml.example samconfig.toml

# Update with your AWS profile and region
```

### 3. Build & Deploy
```bash
# Build the application
sam build

# Deploy to staging
sam deploy --config-env staging-test

# Deploy to production
sam deploy --config-env prod
```

### 4. Access URLs
After deployment, you'll receive:
- **API Gateway URL**: For backend API access
- **Amplify App URL**: For frontend application
- **Cognito Hosted UI**: For authentication testing

---

## 📡 API Endpoints

### Authentication Endpoints
- **POST** `/auth/signup` - User registration
- **POST** `/auth/login` - User login
- **POST** `/auth/forgot-password` - Password reset

### Public Endpoints
- **GET** `/categories` - List all job categories (no auth required)

### Job Seeker Endpoints
- **GET** `/job/seeker/list` - List available/claimed/completed jobs
- **GET** `/job/seeker/view/{jobId}` - View job details
- **POST** `/job/seeker/claim/{jobId}` - Claim a job
- **POST** `/job/seeker/submit/{jobId}` - Submit completed work

### Job Owner Endpoints
- **POST** `/job/owner/create` - Create new job
- **GET** `/job/owner/list` - List posted jobs
- **GET** `/job/owner/view/{jobId}` - View job details and submissions
- **POST** `/job/owner/approve/{jobId}` - Approve completed work
- **POST** `/job/owner/reject/{jobId}` - Reject completed work
- **POST** `/job/owner/relist/{jobId}` - Relist expired job

### Admin Endpoints (ADMIN role required)
- **GET** `/admin/statistics` - Platform statistics
- **GET** `/admin/jobs` - All jobs with admin controls
- **PUT** `/admin/job/{jobId}` - Admin job modifications

---

## ⚡ Event-Driven Architecture

### Event Flow
```
User Action → Lambda Function → EventBridge Event → Multiple Subscribers
```

### Key Events
1. **JobCreatedEvent** → Category notification to job seekers
2. **JobClaimedEvent** → Notification to job owner
3. **JobSubmittedEvent** → Review notification to job owner
4. **JobApprovedEvent** → Payment processing workflow
5. **JobExpiredEvent** → Cleanup and notifications
6. **PaymentCompletedEvent** → Confirmation emails

### Event Subscribers
- **SNS Topics**: Email notifications with category filtering
- **Lambda Processors**: Status updates and business logic
- **SQS Queues**: Delayed processing for timeouts and expiry
- **Step Functions**: Complex workflows (user onboarding, payments)

---

## 🔐 Disaster Recovery

### DynamoDB Backup Strategy
- **Native AWS Backup**: Automated daily backups with 35-day retention
- **Cross-region replication**: For critical tables
- **Point-in-time recovery**: Enabled for all tables

### Cognito Backup System
- **Custom Lambda Function**: Periodic backup of user pool data
- **Primary Table**: `{env}-CognitoBackupUserPool`
- **Secondary Table**: `{env}-CognitoBackupUserPool-Secondary`
- **Schedule**: Runs every 4 hours via EventBridge

### Backup Verification
- Automated backup health checks
- Recovery procedure documentation
- Regular restore testing schedule

---

## 🚀 Deployment

### Environment-Specific Deployment

#### Development Environment
```bash
sam deploy \
  --stack-name freelance-platform-dev \
  --parameter-overrides Environment=dev \
  --profile your-dev-profile
```

#### Production Environment
```bash
sam deploy \
  --stack-name freelance-platform-prod \
  --parameter-overrides Environment=prod \
  --profile your-prod-profile
```

### CI/CD Pipeline
The platform includes GitHub Actions workflows:

- **Development**: Triggers on push to `staging-test` branch
- **Production**: Triggers on push to `main` branch
- **Manual**: Workflow dispatch with parameter overrides

---

## ⚙️ Environment Configuration

### Required Parameters
- `Environment`: dev | prod | staging-test
- `AmplifyUrl`: Frontend URL for CORS configuration
- `GitHubToken`: For Amplify GitHub integration

### Frontend Configuration
The Angular frontend is configured to build without environment variable injection. Configuration is handled directly in the Angular application code.

### AWS Secrets
- `github-access-token`: GitHub personal access token
- Database connection strings (if external DBs used)

---

## 📊 Monitoring & Observability

### CloudWatch Integration
- **Lambda Function Logs**: Centralized logging
- **API Gateway Metrics**: Request/response monitoring
- **DynamoDB Metrics**: Read/write capacity tracking
- **Custom Metrics**: Business KPIs and error rates

### Alarms & Notifications
- High error rate alerts
- DynamoDB throttling notifications
- Lambda timeout warnings
- Cost optimization alerts

---

## 🧪 Testing

### Local Development
```bash
# Start local API
sam local start-api

# Test specific function
sam local invoke "CreateJobFunction" -e events/create-job.json
```

### Integration Testing
```bash
# Run all tests
mvn test

# Test specific module
cd jobs && mvn test
```

---

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 📞 Support

For support and questions:
- Create an issue in the GitHub repository
- Contact the development team
- Check the documentation in the `docs/` folder

---

## 🙏 Acknowledgments

- AWS SAM team for excellent serverless framework
- Angular team for the robust frontend framework  
- All contributors who helped build this platform

---

*Built with ❤️ using AWS Serverless Technologies*