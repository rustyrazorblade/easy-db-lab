# Providers Package

This package contains low-level infrastructure providers for AWS, SSH, and Docker. AWS service classes have been moved to `services/aws/` — this package retains only SDK wrappers, data types, retry utilities, and credentials.

## Directory Structure

```
providers/
├── aws/                    # AWS SDK wrappers and data types
│   ├── AWSModule.kt        # Koin DI registration for all AWS services
│   ├── AWS.kt              # Low-level IAM, S3, STS operations
│   ├── EC2.kt              # Low-level EC2 operations
│   ├── RetryUtil.kt        # Centralized retry configuration
│   ├── AWSPolicy.kt        # Policy definitions and templates
│   ├── IamPolicy.kt        # IAM policy data model
│   ├── IamPolicySerializers.kt # IAM policy serialization
│   ├── AWSClientFactory.kt # Factory interface for AWS clients
│   ├── AWSCredentialsManager.kt # Credentials management
│   ├── VpcService.kt       # VPC service interface (contract)
│   ├── VpcInfrastructure.kt # VPC infrastructure data types
│   ├── InfrastructureConfig.kt # Infrastructure configuration
│   ├── TeardownTypes.kt    # Data classes: DiscoveredResources, TeardownResult, TeardownMode
│   ├── SecurityGroupService.kt  # Data classes: SecurityGroupDetails, WellKnownPorts
│   ├── AwsTypes.kt         # EC2 type aliases and data classes
│   ├── EMRTypes.kt         # EMR type aliases and data classes
│   ├── InstanceTypes.kt    # Instance type data classes
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

## What Belongs Here vs `services/aws/`

**Here (providers/aws/):** Low-level SDK wrappers, interfaces, data types, retry utilities, credentials, policy definitions. Things that directly wrap AWS SDK calls without business logic.

**In services/aws/:** Business-logic services that orchestrate AWS operations, implement service interfaces, and are injected into commands. See [`services/aws/CLAUDE.md`](../../services/aws/CLAUDE.md).

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

## AWSModule.kt Registration

`AWSModule.kt` remains here because it registers both low-level SDK clients and service-layer singletons via Koin. Services from `services/aws/` are imported and registered here alongside their SDK client dependencies.

## SSH Provider

- `SSHConnectionProvider` — manages connection pool, auto-reconnects, keepalive
- `RemoteOperationsService` — high-level SSH ops (execute, upload, download)
- Registered in `SSHModule.kt`: provider as **singleton**, remote ops as **factory**

### Credential Redaction Is A Boundary, Not A Call-Site Convention

Remote commands legitimately carry a URL with an embedded credential — a git clone of a private
fork is the common case — and the remote side echoes the command straight back on failure.
`ssh/SSHClient` therefore redacts at the single point every remote command passes through, covering
all four sinks at once: the debug log, the emitted `Event.Ssh.CommandOutput`, the returned
`Response`, and the thrown `RemoteCommandFailedException`. The helper is
`ssh/Redaction.kt`'s `redactUrlCredentials`.

Rules that follow from this:

- **Do not redact per call site.** A convention only has to be forgotten once to leak. If a new
  sink appears (a new exception type, a new event), redact it inside `SSHClient` too.
- **Do not lower `org.apache.sshd` below `INFO`** in `easydblab-logback.xml`. MINA logs every
  remote command verbatim at DEBUG, one layer *below* this boundary, straight into `debug.log` and
  its 30 days of archives. `LogbackConfigurationTest` locks this in.
- `executeRemotely(secret = true)` is stronger still: neither the command nor any of its output is
  repeated anywhere, because a failing secret command routinely echoes its own argument back.

## Docker Provider

- `DockerClientProvider` — lazy-initialized Docker client (expensive to create)
- Registered in `DockerModule.kt` as **singleton**
- `Docker` instances created as **factory** (stateful, tied to context)
