# Freelance Job/Task Platform

A serverless freelance platform built with AWS SAM and Java 17.

## Prerequisites

- Java 17
- AWS SAM CLI
- AWS CLI configured

## Development

```bash
sam build
sam local start-api
```

## Deployment

```bash
# Deploy to development
sam deploy --config-env dev

# Deploy to production  
sam deploy --config-env prod
```

## Base Template

This repository contains a minimal SAM template with:
- API Gateway with CORS enabled
- Java 17 runtime configured
- Environment parameters (dev/prod)

Teams can add their own AWS resources as needed for their tickets.