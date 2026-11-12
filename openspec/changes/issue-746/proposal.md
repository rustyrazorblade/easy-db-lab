## Why

Part of the multi-cloud epic (#1062).  AWS SDK types (`software.amazon.awssdk.*`) are imported in 11 main files outside the AWS adapter packages.  That coupling blocks GCP (#702) and Azure (#40).  This is slice 1: confine the SDK to `providers/aws` and `services/aws`, enforce it with a test, and break the two AWS-boundary package cycles from #745.

It also fixes a user-facing bug.  `Main.kt`'s friendly AWS error handlers are dead code, because `CommandExecutor.executeWithLifecycle` catches every exception first.  A user with expired credentials sees a raw `StsException: The security token included in the request is expired` cause chain.

## What Changes

- **Boundary rule.**  New unit-tier ArchUnit tests over main classes only, with no allowlist:
  - no class outside `providers.aws..` and `services.aws..` depends on `software.amazon.awssdk..`;
  - the same applies to SDK types that appear only in a `catch` clause (a custom condition: the stock rule misses them);
  - `providers..` does not depend on `services..`;
  - `configuration..` does not depend on `providers..`.
- **Provider error handler.**
  - New `kernel/ProviderErrorHandler`, which returns an `ErrorDescription` (provider, kind, detail, remedy) or null.
  - `AwsErrorHandler` in `providers/aws` classifies by SDK class and error code: credentials or permission-denied.
  - A shared `CommandFailureReporter` walks the cause chain across every registered handler and emits the new `Event.Command.ProviderError`, or today's `Event.Command.ExecutionError` when no handler matches.
  - It is called from `executeWithLifecycle`, from a new outer catch in `executeTopLevel`, and from Picocli's `executionExceptionHandler`.
  - `Main.kt` loses every dead catch and handler.
  - `AWSResourceSetupService.emitCredentialError` uses the handler.
  - Sites that already print a detailed message rethrow as `CommandFailedException(message, cause)`, so the user sees one error block, not two.
- **Leak sites routed.**
  - `SparkJobState` enum, mapped inside `EMRSparkService`.
  - `OpenSearchService.describeDomainOrNull`.
  - `AWSClientFactory` takes a `String` region and adds `requireValidRegion`.
  - `AwsKeyPairService` in `services/aws` takes the key-pair code out of `User`.
  - `CompactorTaskDefinition` and its `tempo-backend.yaml` move to `services/aws`.
  - `EcrPullSecretService` moves to `services/aws` behind a new `ImagePullSecretService` interface.
  - `Packer` gets `AWSCredentialsManager` from Koin as a factory.
  - `providers/ssm` moves to `providers/aws/ssm`.
- **Koin split.**  `AWSModule` becomes `providers/aws/AwsClientsModule` (SDK clients, credentials, the error handler, SSM) and `services/aws/AwsServicesModule` (bindings for service classes).
- **Docs.**  The boundary rule, the error handler, and the rule that error translation wraps a retried call and never runs inside it.  The `Up.kt:225` retry hazard is recorded.  Docs updated: `providers/CLAUDE.md`, `services/aws/CLAUDE.md`, `events/CLAUDE.md`, and the root `CLAUDE.md` (boundary rule; the kernel rule extended to providers).

## Capabilities

### New Capabilities

- `provider-boundary`: cloud SDK types stay inside the provider adapter packages, and the AWS-boundary package cycles stay broken, enforced by tests.
- `provider-errors`: a failed command whose cause is a recognised provider credentials or permission error reports a typed description with a remedy.

### Modified Capabilities

- `spark-emr`: `spark status` reports a project-owned job state; an unknown EMR state shows as `UNKNOWN`.
- `opensearch`: `opensearch status` for a missing domain keeps today's not-found output.
- `setup`: profile setup's region validation and key-pair creation behave as today; credential and permission failures are classified the same way as command failures.

## Impact

- About 30 main files move or change, plus their tests.  `providers/ssm` (6 files) and `CompactorTaskDefinition` change package.
- User-visible changes:
  - expired or missing credentials, and 403s, get a message with a remedy;
  - an STS 403 caused by bad credentials is now reported as a credentials error, not a permissions error;
  - failures before the lifecycle starts no longer print a raw stack trace;
  - `spark status` shows `UNKNOWN` instead of `"null"` for a state this SDK version does not know.
- No retry predicate changes, and no adapter-level exception translation.

## Out of scope

- Gradle module extraction (follow-up: "Extract the AWS adapter into a :provider-aws Gradle subproject").
- New coarse ports (`ComputeProvider` and similar) and model renames.
- The 43 files that use AWS adapter types indirectly, including the AWS-only commands.
- Reshaping `ClusterState.InfrastructureState` or `ClusterS3Path`.
- The remaining cycles in #745.
- A `CloudProviderException` hierarchy.
- Any GCP or Azure code.
- Rejecting unknown regions up front (owner kept today's behaviour).
- A neutral credentials interface for `Packer` (deferred under D2 until a second implementation exists).
