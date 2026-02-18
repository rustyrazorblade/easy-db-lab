# AWS Services Package

This package contains AWS service classes that implement business logic on top of the low-level SDK wrappers in `providers/aws/`.

## Directory Structure

```
services/aws/
├── CLAUDE.md                  # This file
├── AwsS3BucketService.kt     # S3 bucket administration (lifecycle, metrics, policies)
├── AWSResourceSetupService.kt # IAM resource setup (roles, instance profiles)
├── AwsInfrastructureService.kt # VPC infrastructure creation and teardown orchestration
├── EC2VpcService.kt           # VPC resource management (implements VpcService)
├── EC2InstanceService.kt      # Instance lifecycle management
├── AMIService.kt              # AMI lifecycle: CRUD, pruning, validation (implements AMIValidator)
├── AMIValidationService.kt    # AMI validation types and exceptions
├── AMIResolver.kt             # AMI resolution interface and implementations
├── EMRService.kt              # EMR cluster management (find, terminate, wait)
├── EMRSparkService.kt         # Spark job execution (implements SparkService)
├── OpenSearchService.kt       # OpenSearch domain management
├── SQSService.kt              # SQS queue management
├── S3ObjectStore.kt           # S3 object operations (implements ObjectStore)
└── InstanceSpecFactory.kt     # Instance spec creation
```

## What Belongs Here vs `providers/aws/`

**Here (services/aws/):** Business-logic services that:
- Are injected into commands via Koin
- Orchestrate multiple AWS operations
- Implement service interfaces (`VpcService`, `ObjectStore`, `SparkService`, `AMIValidator`)
- Contain domain-specific logic beyond simple SDK calls

**In providers/aws/:** Low-level infrastructure:
- `AWS.kt`, `EC2.kt` — direct SDK wrappers
- Data types, type aliases, enums
- `RetryUtil` — retry configuration
- Interfaces (`VpcService`, `AWSClientFactory`)
- Policy definitions
- Credentials management

## Service Pattern

All AWS services follow this constructor pattern:

```kotlin
class ServiceName(
    private val awsSdkClient: AwsSdkClient,  // AWS SDK client (from Koin)
    private val outputHandler: OutputHandler, // User-facing output (from Koin)
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }
}
```

Key conventions:
- User feedback via `outputHandler.handleMessage()`
- Internal logging via `KotlinLogging`
- Retry logic via `RetryUtil` from `providers.aws` (never custom loops)
- Fail fast — let exceptions propagate
- Use `Result<T>` for expected failures (SQS, ObjectStore)
- Idempotent where possible (find-or-create pattern)

## Service Consolidation Design

Services are organized by resource, not by operation type:

- **AMIService** — all AMI operations (list, deregister, prune, validate) in one class
- **EMRService** — all EMR operations (create, terminate, find, wait) in one class
- **AwsInfrastructureService** — all VPC infrastructure (create and teardown) in one class
- **EC2VpcService** — all VPC resource operations including security group describe

## Koin Registration

All services in this package are registered in `providers/aws/AWSModule.kt` as Koin singletons, alongside the SDK clients they depend on.
