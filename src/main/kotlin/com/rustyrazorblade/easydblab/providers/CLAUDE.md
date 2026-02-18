# Providers Package

This package contains infrastructure providers for AWS, SSH, and Docker.

## Directory Structure

```
providers/
├── aws/                    # AWS service providers
│   ├── AWSModule.kt        # Koin DI registration for all AWS services
│   ├── AWS.kt              # Low-level IAM, S3, STS operations
│   ├── RetryUtil.kt        # Centralized retry configuration
│   ├── AMIService.kt       # AMI lifecycle: CRUD, pruning, validation (implements AMIValidator)
│   ├── AMIResolver.kt      # AMI resolution by architecture pattern
│   ├── EC2InstanceService.kt  # Instance lifecycle
│   ├── EC2VpcService.kt    # VPC management + security group describe (implements VpcService)
│   ├── AwsInfrastructureService.kt  # VPC infrastructure creation and teardown orchestration
│   ├── S3ObjectStore.kt    # S3 operations (implements ObjectStore)
│   ├── EMRService.kt       # EMR cluster management + teardown (find, terminate, wait)
│   ├── EMRSparkService.kt  # Spark job execution (implements SparkService)
│   ├── OpenSearchService.kt # OpenSearch domains
│   ├── SQSService.kt       # SQS queue management
│   ├── TeardownTypes.kt    # Data classes: DiscoveredResources, TeardownResult, TeardownMode
│   ├── SecurityGroupService.kt  # Data classes: SecurityGroupDetails, WellKnownPorts
│   └── model/              # Data models (AMI, etc.)
├── docker/                 # Docker client providers
│   ├── DockerModule.kt
│   ├── DockerClientProvider.kt
│   └── DefaultDockerClientProvider.kt
└── ssh/                    # SSH connection providers
    ├── SSHModule.kt
    ├── SSHConnectionProvider.kt
    ├── DefaultSSHConnectionProvider.kt
    ├── RemoteOperationsService.kt
    └── DefaultRemoteOperationsService.kt
```

## AWS Service Pattern

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
- Retry logic via `RetryUtil` (never custom loops)
- Fail fast — let exceptions propagate
- Use `Result<T>` for expected failures (SQS, ObjectStore)
- Idempotent where possible (find-or-create pattern)

## Service Consolidation Design

Services are organized by resource, not by operation type:

- **AMIService** — all AMI operations (list, deregister, prune, validate) in one class
- **EMRService** — all EMR operations (create, terminate, find, wait) in one class
- **AwsInfrastructureService** — all VPC infrastructure (create and teardown) in one class
- **EC2VpcService** — all VPC resource operations including security group describe

This avoids the anti-pattern of splitting CRUD operations across multiple thin classes.

## RetryUtil Factory Methods

**Location:** `providers/aws/RetryUtil.kt`

Always use factory methods instead of creating manual retry configurations.

| Method | Attempts | Backoff | Use Case |
|--------|----------|---------|----------|
| `createIAMRetryConfig()` | 5 | Exponential 1s→16s | IAM operations, handles 404 eventual consistency |
| `createEC2InstanceRetryConfig<T>()` | 5 | Exponential 1s→16s | EC2 instance ops, handles "does not exist" |
| `createAwsRetryConfig<T>()` | 3 | Exponential 1s→4s | S3, EC2, EMR (standard AWS) |
| `createDockerRetryConfig<T>()` | 3 | Exponential 1s→4s | Container start/stop/remove |
| `createNetworkRetryConfig<T>()` | 3 | Exponential 1s→4s | Generic network ops |
| `createSshConnectionRetryConfig()` | 30 | Fixed 10s | SSH boot-up (~5 min total) |
| `createS3LogRetrievalRetryConfig<T>()` | 10 | Fixed 3s | S3 log retrieval (eventual consistency) |
| `createVpcTeardownRetryConfig<T>()` | 5 | Exponential 5s→40s | VPC teardown DependencyViolation |

### Convenience Wrappers

```kotlin
// Short-hand for common patterns
val result = RetryUtil.withAwsRetry("describe-cluster") { emrClient.describeCluster(request) }
val result = RetryUtil.withEc2InstanceRetry("describe") { ec2Client.describeInstances(request) }
RetryUtil.withVpcTeardownRetry("delete-sg") { ec2Client.deleteSecurityGroup(request) }
```

### Full Pattern

```kotlin
// Supplier (returns value)
val retryConfig = RetryUtil.createAwsRetryConfig<List<AMI>>()
val retry = Retry.of("ec2-list-amis", retryConfig)
val result = Retry.decorateSupplier(retry) {
    ec2Client.describeImages(request).images()
}.get()

// Runnable (void)
val retryConfig = RetryUtil.createAwsRetryConfig<Unit>()
val retry = Retry.of("s3-upload", retryConfig)
Retry.decorateRunnable(retry) {
    s3Client.putObject(request, localFile.toPath())
}.run()
```

## AWSModule.kt Registration

Services are registered as Koin singletons with telemetry-enabled client override configs:

```kotlin
val awsModule = module {
    // AWS SDK clients (singletons)
    single { IamClient.builder().region(get()).credentialsProvider(get()).build() }
    single { Ec2Client.builder().region(get()).credentialsProvider(get()).build() }
    // ... S3Client, StsClient, EmrClient, OpenSearchClient, SqsClient

    // Services (singletons)
    single { AWS(get<IamClient>(), get<S3Client>(), get<StsClient>()) }
    single { AMIService(get<Ec2Client>(), get<OutputHandler>(), get<AWS>()) }
    single<AMIValidator> { get<AMIService>() }  // Binds to same instance
    single { EC2InstanceService(get<Ec2Client>(), get<OutputHandler>()) }
    single<VpcService> { EC2VpcService(get<Ec2Client>(), get<OutputHandler>()) }
    single { AwsInfrastructureService(get<VpcService>(), get<EMRService>(), get<OpenSearchService>(), get<OutputHandler>()) }
    single<ObjectStore> { S3ObjectStore(get<S3Client>(), get<OutputHandler>()) }
    // ...
}
```

## SSH Provider

- `SSHConnectionProvider` — manages connection pool, auto-reconnects, keepalive
- `RemoteOperationsService` — high-level SSH ops (execute, upload, download)
- Registered in `SSHModule.kt`: provider as **singleton**, remote ops as **factory**

## Docker Provider

- `DockerClientProvider` — lazy-initialized Docker client (expensive to create)
- Registered in `DockerModule.kt` as **singleton**
- `Docker` instances created as **factory** (stateful, tied to context)
