## Context

Measured on `main` at `83e8fa51`.  The architect verified all 11 leak sites and both cycles in source and in bytecode (an ArchUnit probe against the compiled classes).  The bytecode found two leaks that no import shows:
- `ServicesModuleKt` depends on `EcrClient` through the reified `singleOf(::EcrPullSecretService)`.
- `SetupProfile` depends on `Ec2Client` through an inferred return type.

Paths below are under `src/main/kotlin/com/rustyrazorblade/easydblab/`.

## Goals / Non-Goals

**Goals:**
- Zero SDK dependencies outside `providers.aws..` and `services.aws..`, enforced by a unit test with no allowlist.
- Break the two cycles `providers` <-> `services` and `configuration` <-> `providers`.
- A user whose AWS call fails for credentials or permissions sees a typed message with a remedy.

**Non-Goals:** see the proposal's out-of-scope list.  In particular, nothing translates SDK exceptions at adapter boundaries, so no retry predicate changes.

## Decisions (owner-approved 2026-11-06)

### D1. `AWSModule` is split in two

- `providers/aws/AwsClientsModule.kt` holds:
  - `Region` and `AwsCredentialsProvider`;
  - the 7 SDK clients, plus `AWS`, `RegionalClients` and `InstanceRolePolicies`;
  - `AWSClientFactory`, and the `AWSCredentialsManager` factory;
  - `AwsErrorHandler`, and the bindings folded in from `ssmModule`.

  Its only edges are `configuration`, `events` and `Context`.
- `services/aws/AwsServicesModule.kt` holds every binding of a `services.aws` type, plus `ObjectStore`, `SparkService`, `ImagePullSecretService` and `AwsKeyPairService`.
- `di/KoinModules` lists both.

*Rejected:*
- `di/` (the issue's plan): `AWSModule` imports 13 SDK types, so it would need an allowlist, which contradicts epic decision D1.
- All of it in `services/aws`: SDK client construction would sit in the services layer.

### D2. The error contract lives in `kernel/`

```kotlin
fun interface ProviderErrorHandler { fun describe(error: Throwable): ErrorDescription? }
@Serializable enum class ProviderErrorKind { @SerialName("credentials") CREDENTIALS, @SerialName("permission-denied") PERMISSION_DENIED, @SerialName("unavailable") UNAVAILABLE }
data class ErrorDescription(val provider: String, val kind: ProviderErrorKind, val detail: String, val remedy: String)
```

- `kernel` depends on nothing in the project, so no cycle is possible.
- The kernel rule in the root `CLAUDE.md` grows to "types shared by commands, services and providers".
- Only the kinds that have a remedy are defined: the two the acceptance criteria need, plus `UNAVAILABLE` (the provider or its local daemon cannot be reached). `UNAVAILABLE` keeps the "check that Docker is running" hint the deleted `Main` catch gave. Docker is the first non-cloud provider: `DockerErrorHandler` in `providers/docker` takes no dependencies and claims only an unreachable daemon: a connection failure (`ConnectException` or `SocketException`, which is what docker-java's httpclient5 transport throws for a missing unix socket or a refused `DOCKER_HOST`) below a Docker exception in the cause chain, or one whose message names `docker.sock` or the configured `DOCKER_HOST`. A plain `IOException` that names the socket (a file copy or a bind mount) is not claimed. The detail is the message of the exception that matched, not of its outer wrapper. docker-java's API answers (`NotFoundException`, `ConflictException`, `NotModifiedException`) and the project's `DockerException` wrapper are not claimed, so their own message shows. `IllegalArgumentException`, `IllegalStateException` and `RemoteException` need no handler: `ExecutionError` already prints the class name and message.

*Rejected:*
- `providers/errors`: `providers/` later splits into one subproject per cloud, which leaves no place for a neutral contract.
- `services/`: it would recreate the `providers` -> `services` cycle.

### D3. One event: `Event.Command.ProviderError(provider, kind, detail, remedy)`

- `isError() = true`.  It lives in the `Command` domain next to `ExecutionError`.
- `toDisplayString()` prints a headline from kind and provider, then the detail, the remedy and the "check logs/info.log" line.
- An exception no handler claims still emits `ExecutionError`, unchanged.

*Rejected:* two events (`CredentialsRejected`, `PermissionDenied`).  Every new kind would need a new event type.

### D4. No duplicate error blocks

Three sites already print a detailed message:
- `AWSResourceSetupService.emitCredentialError`
- the key-pair permission path
- `CompactorService.withEcsAccess`

Each keeps its event, then rethrows as `CommandFailedException(message, cause)`.  `CommandFailedException` gains a `cause` parameter.  `CommandFailureReporter` treats it as already reported: the cause chain stops there.

*Rejected:*
- Removing their events: this loses the IAM policy dump.
- Accepting two blocks.

### D5. `AWSPolicy` stays in `providers/aws`

Once `User.kt` loses `getRequiredIAMPolicies` and its key-pair code, `configuration` imports nothing from `providers`.  `AWSPolicy`'s edges to `configuration` point in the allowed direction.

### D6. `Packer` gets credentials from Koin

- `Packer` injects `AWSCredentialsManager`, bound as a `factory`.
- It must not be a `single`: its `credentialsFile` is lazy, so a single would keep stale SSO credentials across builds in REPL or Server mode.
- No new type.  A neutral interface waits for a second implementation (the GCP base image), per epic decision D2.  It would have to live in `kernel`, because `containers` already imports the adapters.

### D7. Region validation is unchanged

- `AWSClientFactory` takes `region: String` and calls `Region.of` internally.
- `AWSClientFactory.requireValidRegion(region)` runs at today's spot, so the same input fails the same way.
- `Region.of` only rejects a blank string.  Typo detection was explicitly declined.

### D8. `SparkJobState`

- `enum class SparkJobState { PENDING, CANCEL_PENDING, RUNNING, COMPLETED, CANCELLED, FAILED, INTERRUPTED, UNKNOWN }` in `services/`.
- A private exhaustive `when` in `EMRSparkService` maps `StepState` onto it.  `UNKNOWN_TO_SDK_VERSION` and `null` map to `UNKNOWN`.
- `TERMINAL_JOB_STATES` and `SparkStatus`'s failed-step branch use the enum.

## Error path

**The reporter.**  `services/CommandFailureReporter(eventBus, handlers: List<ProviderErrorHandler>)` has one method, `report(error)`.
- It logs the error.
- It walks the cause chain from the outermost exception inward, using an identity set so a cause loop terminates.  At each level it asks every handler in turn.
- It emits `ProviderError` on the first match and `ExecutionError` otherwise.
- Every wrapper is walked through `.cause`; no wrapper type is special-cased.  Suppressed exceptions are not walked.  A `CommandFailedException` anywhere in the chain means the failure is already reported.  A handler that throws counts as not claiming, except `InterruptedException`, which restores the interrupt flag and propagates.

**Wiring.**
- Koin: `single { CommandFailureReporter(get(), getAll()) }`.  Each handler binds `bind ProviderErrorHandler::class`.
- Handlers take no dependencies (no `User`, no clients), so the reporter builds before a profile exists (`init`, `setup-profile`).
- `DefaultCommandExecutor` injects the reporter `by inject()`.  A ninth constructor parameter would cross detekt's `constructorThreshold`.

**Call sites:**
1. `executeWithLifecycle`'s catch.
2. A new outer `try/catch` in `executeTopLevel`.  An exception thrown by Picocli's custom `IExecutionStrategy` goes to `handleUnhandled` (a raw colored stack trace) and never reaches `executionExceptionHandler`.  That covers `handleVpcReconstructionFromEnv()` and `resourceManager.closeAll()`.
3. `CommandLineParser`'s `executionExceptionHandler`, for commands that are not `PicoCommand`.

## AWS classification

The error code is checked first: STS, IAM and S3 return 403 for bad credentials.  The code sets live in `Constants.AWS.ErrorCodes`.

| Order | Match | Kind |
|---|---|---|
| 1 | `AwsServiceException` with code in {`ExpiredToken`, `ExpiredTokenException`, `RequestExpired`, `AuthFailure`, `InvalidClientTokenId`, `UnrecognizedClientException`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `InvalidSignatureException`, `InvalidToken`, `TokenRefreshRequired`, `MissingAuthenticationToken`, `IncompleteSignature`} | CREDENTIALS |
| 2 | `AwsServiceException` with code in {`AccessDenied`, `AccessDeniedException`, `UnauthorizedOperation`} | PERMISSION_DENIED |
| 3 | `AwsServiceException` with `statusCode() == 403`, except `RequestTimeTooSkewed` | PERMISSION_DENIED |
| 4 | `sso.auth.ExpiredTokenException`, or an `SdkClientException` raised by an SDK credentials provider (the first stack frame past the SDK exception builder is in `software.amazon.awssdk.auth.credentials`) whose message starts with "Unable to load credentials" or "Profile file contained no credentials for profile". A wrapped S3 or network error that quotes those words is not raised there and is not matched | CREDENTIALS |
| else | anything else, including network `SdkClientException`s | null |

**Remedies:**
- **CREDENTIALS:** "Run: easy-db-lab profile setup. If you use SSO with the AWS CLI, log in again with: aws sso login (add --profile and your profile name if it is not the default), and check with: aws sts get-caller-identity". `profile setup` is the unconditional path because a Homebrew install does not guarantee the AWS CLI; the handler takes no dependencies, so it cannot name the profile.
- **PERMISSION_DENIED:** "If this is missing permissions on your AWS user, attach `<policy>` (print it with `easy-db-lab show-iam-policies <policy>`). For S3: "A bucket policy, SCP or KMS key policy can also deny the request."; for any other service: "An SCP or permissions boundary can also deny the request." A 403 does not prove the cause, so the remedy does not assert it.

The policy comes from the exception class.  The five policy names become constants on `AWSPolicy.UserIAM`.

| Exception class | Policy |
|---|---|
| `Ec2Exception`, `EcrException`, `SsmException` | EasyDBLabEC2 |
| `IamException`, `S3Exception` | EasyDBLabIAM |
| `EmrException` | EasyDBLabEMR |
| `OpenSearchException` | EasyDBLabOpenSearch |
| `EcsException`, `CloudWatchLogsException` | EasyDBLabCompactor |
| `StsException` | none: the only STS call is `GetCallerIdentity`, which needs no IAM permission, so the remedy points at an SCP, permissions boundary or session policy |
| other | all policies |

## Leak-site routing

| Site | Routing |
|---|---|
| `Main.kt` | Delete every catch and handler (`:40-160`). `main` starts Koin, runs the Redis check, and calls `parser.eval`. |
| `OpenSearchStatus` | `OpenSearchService.describeDomainOrNull(name)` catches `ResourceNotFoundException` *outside* `withAwsRetry` and returns null. `describeDomain` stays for its other callers. |
| `SparkService`/`SparkStatus` | `SparkJobState` (D8) |
| `SetupProfile` | `String` region and `requireValidRegion` (D7); `AwsKeyPairService` |
| `User.kt` | `services/aws/AwsKeyPairService(clientFactory, eventBus).generate(user, profileDir)` holds today's code. `getRequiredIAMPolicies` is removed; `ShowIamPolicies` and `SetupProfile` call `AWSPolicy.UserIAM.loadAll` directly. |
| `CompactorTaskDefinition` | Pure move to `services/aws`, **together with `tempo-backend.yaml`**: it is loaded relative to the class. |
| `Packer` | D6 |
| SSM | Move `providers/ssm` to `providers/aws/ssm` (6 files); fold `ssmModule` into `AwsClientsModule`. |
| `EcrPullSecretService` | Moves to `services/aws` and implements `services/ImagePullSecretService.ensureFor(controlHost, image, namespace)`. The binding moves to `AwsServicesModule`. |

## Boundary tests

New test `architecture/PackageBoundaryTest.kt` in the unit tier.  Classes are imported once with `ImportOption.DoNotIncludeTests()`.

| Rule | What it checks |
|---|---|
| R0 (sanity) | The import holds `MainKt` and does not hold `BaseKoinTest`. |
| R1a | No class outside the adapters depends on `software.amazon.awssdk..`. |
| R1b | Custom `ArchCondition<JavaCodeUnit>`: no code unit outside the adapters catches a `software.amazon.awssdk..` throwable. The message names the method, the SDK type and the location. |
| R2 | `providers..` does not depend on `services..`. |
| R3 | `configuration..` does not depend on `providers..`. |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| The Koin split and the `getAll` binding fail only at runtime. | A Koin resolution test covers both modules and the reporter's handler list. Smoke check: `bin/easy-db-lab --help` plus one AWS command. |
| A handler that needed `User` would crash the error path itself. | No-dependency rule, plus a test that resolves the reporter with no profile. |
| `tempo-backend.yaml` left behind fails at runtime. | The existing `CompactorTaskDefinitionTest` loads the resource and pins the registered request byte-for-byte. |
| `Up.kt:225` retries on `SdkException` through `createVpcAutoCidrRetryConfig`, from outside the adapter. If an adapter ever translated exceptions, this retry would silently stop. | No translation in this slice. Record it in `providers/CLAUDE.md` as a blocker for adapter-level translation until the ClusterNetwork issue moves the retry. |
| An SDK upgrade rewords a credential message (row 4). | Tests build row-4 exceptions from real SDK providers over an empty profile file, so a rewording fails the test. |
| Moved classes break test mocks. | Listed per site in the tasks. |
| Inline functions in the adapters would copy SDK references into callers. | None exist today; documented in `providers/CLAUDE.md`. |

## Migration

None: clusters are ephemeral.  Capture the expired-credentials output on `main` before stage 1, then compare it after.
