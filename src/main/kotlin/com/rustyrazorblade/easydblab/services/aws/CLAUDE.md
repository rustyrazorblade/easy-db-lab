# AWS Services Package

This package contains AWS service classes that implement business logic on top of the low-level SDK wrappers in `providers/aws/`.

## Directory Structure

```
services/aws/
├── CLAUDE.md                  # This file
├── AwsS3BucketService.kt     # S3 bucket administration (creation, request metrics, policies, tags)
├── AWSResourceSetupService.kt # IAM resource setup (roles, instance profiles)
├── AwsInfrastructureService.kt # VPC infrastructure creation and teardown orchestration
├── EC2VpcService.kt           # VPC resource management (implements VpcService; delegates SecurityGroupOperations to EC2SecurityGroupService)
├── EC2SecurityGroupService.kt # Security groups: find/create, authorize, exact revoke, revoke-all, describe, delete (implements SecurityGroupOperations)
├── Ec2TagSpecification.kt     # The tag specification both EC2 services put on what they create
├── SshIngressService.kt       # SSH ingress for ingress allow / revoke: IngressResult, AllowOutcome, RevokeOutcome
├── EC2InstanceService.kt      # Instance lifecycle: launches the missing ordinals (`InstanceCreationConfig.indexes`), per-subnet batched RunInstances (runs of ordinals `subnets` apart, chunks of 100, client token, throttle retry, ordinal launch tags), reports each batch before naming it, Name tags, waits in batches of 100 (the running wait fails at once on an instance EC2 reports shutting-down, terminated, stopping or stopped, naming its ID, alias and state, each instance once; a re-run of `up` launches a replacement under the same name), paginated discovery that names an untagged instance from its ordinal tags
├── InstanceSpecFactory.kt     # Instance specs; `missingOrdinals` is the one source of the ordinals to launch, for the launch and the capacity plan
├── RunInstancesRequests.kt    # The launch plan (`LaunchBatch` per RunInstances call) and the request, shared by the launch and its dry run (which asks about the first batch); `OrdinalRun` (first ordinal, stride, count) and its launch tags
├── AccountCapacity.kt         # The account facts the pre-launch checks read (port), LaunchDryRun, InstanceFamily
├── Ec2AccountCapacity.kt      # AccountCapacity from EC2 and Service Quotas; the dry run is on the EC2 throttle retry
├── LaunchCapacityPreflight.kt # The pre-launch capacity checks and the launch dry run, for the new instances only
├── AMIService.kt              # AMI lifecycle: CRUD, pruning, validation (implements AMIValidator)
├── AMIValidationService.kt    # AMI validation types and exceptions
├── AMIResolver.kt             # AMI resolution interface and implementations
├── EMRService.kt              # EMR cluster management (find, terminate, wait)
├── EMRSparkService.kt         # Spark job execution (implements SparkService)
├── OpenSearchService.kt       # OpenSearch domain management
├── S3ObjectStore.kt           # S3 object operations (implements ObjectStore)
├── CompactorLeaseReader.kt    # Reads the account compactor's lease for `observability compactor status`, in the bucket's region
└── BucketRegion.kt            # A bucket's region from GetBucketLocation; the compactor and the Grafana documents proxy sign for it
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
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }
}
```

Key conventions:
- Internal logging via `KotlinLogging`
- User-facing output via `eventBus.emit()` — see root `CLAUDE.md` for output conventions
- Retry logic via `RetryUtil` from `providers.aws` (never custom loops)
- Fail fast — let exceptions propagate
- Use `Result<T>` for expected failures (ObjectStore)
- Idempotent where possible (find-or-create pattern)

## Service Consolidation Design

Services are organized by resource, not by operation type:

- **AMIService** — all AMI operations (list, deregister, prune, validate) in one class
- **EMRService** — all EMR operations (create, terminate, find, wait) in one class
- **AwsInfrastructureService** — all VPC infrastructure (create and teardown) in one class
- **EC2VpcService** — all VPC resource operations; it delegates the security group half of `VpcService` to **EC2SecurityGroupService** by interface delegation, so callers inject one `VpcService`

## Security Group Rules

- `authorizeSecurityGroupIngress` returns `IngressAuthorization` (`ADDED` / `ALREADY_PRESENT`) and `revokeSecurityGroupIngress` returns `IngressRevocation` (`REMOVED` / `NOT_FOUND`); both describe the group first, and the revoke removes only the one exact permission. Both throw `SecurityGroupNotFoundException` for `InvalidGroup.NotFound`. Every describe runs under `withAwsRetry`.
- The provider emits no event for a rule. `AwsInfrastructureService` emits `Infra.SecurityGroupRuleConfigured` on `ADDED` (cluster and Packer paths, one `authorizeIngress` helper), and the `ingress` commands emit their own `Event.Ingress` events, so each rule is reported once.
- `SshIngressService` owns the SSH binding (tcp, 22-22). Mock only `VpcService` in its tests; the rule round trips run against LocalStack in `EC2SecurityGroupServiceIntegrationTest`.

## Koin Registration

All services in this package are registered in `providers/aws/AWSModule.kt` as Koin singletons, alongside the SDK clients they depend on.

## Testing Rule: LocalStack Integration Tests Are Mandatory

**Any code that makes S3 calls MUST have comprehensive integration tests using LocalStack.** This includes services that use `ObjectStore`, `S3ObjectStore`, or `S3Client` directly.

Do not rely solely on unit tests with mocked `ObjectStore` — mocks cannot catch S3 API behavior differences (e.g., how `ListObjectsV2` handles prefixes, delimiters, and common prefixes vs. object contents).

Integration tests live in the slow tier under `src/integrationTest/kotlin/.../services/aws/` (LocalStack needs Docker, so these belong in the `integrationTest` source set, not `src/test/`) and follow the pattern in `S3ObjectStoreIntegrationTest`:
- Use `@Testcontainers` + `LocalStackContainer` with `Service.S3`
- Create buckets in `@BeforeAll`
- Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` to share the container across tests
- Test the full behavior end-to-end: upload data, call the service method, assert on results

Services that call S3 indirectly (via `ObjectStore`) also need integration tests — see `ClusterBackupServiceS3IntegrationTest` as an example of testing a higher-level service against LocalStack.

## Account compactor

The compactor runs in a pod on every cluster (see `configuration/CLAUDE.md`), not in an AWS service. This package holds only `CompactorLeaseReader`: it reads `leases/compactor.json` with a client for the account bucket's region (`BucketRegion`) and returns `CompactorLease.None`, `Held(record, age)`, or `Unreadable(content)` when the body is not a valid lease. It never writes the lease. It reads through the `:compactor-lease` module's `S3ConditionalObjects`, the only conditional-write port; `S3ObjectStore` has no conditional writes.

## S3 data handling

- **Nothing sets a lifecycle, expiry or retention rule.** `down` only disables the data bucket's request metrics (`teardownDataBucket`); with `--all` it deletes a data bucket only when it is empty (`deleteEmptyBucket`, a `Result` carrying S3's error), and emits `Event.S3.DataBucketKept(bucket, reason)` for one it leaves in place. The IAM policy grants no lifecycle actions.
- **Observability data lives in the account bucket** under the tool-named roots `mimir/`, `loki/`, `tempo/`, `pyroscope/` and `grafana/annotations/<tenant>/`, and the test documents under `reports/<tenant>/<name>-<id>/` (see `configuration/ObservabilityStore`). The instance role's delete deny (`AWSPolicy.Inline.S3AccessWildcard`) covers `grafana/` and `reports/`; the compacted roots are left out because the lease-holding compactor runs with the instance role, and the bucket policy (`S3BucketPolicy`) denies deletes under them to the EMR roles only; it denies no `PutObject`, and `report upload` runs with the operator's credentials. The per-cluster data bucket holds database data (ClickHouse S3 storage, Cassandra artifacts, Spark); the observability stack writes nothing to it.
