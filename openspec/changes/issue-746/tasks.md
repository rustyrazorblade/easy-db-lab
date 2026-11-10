## 0. Baseline

- [x] 0.1 Run one command against expired credentials on `main` and save the console output (the "before" capture).

## 1. Error path

- [ ] 1.1 `kernel/ProviderErrorHandler.kt`: `ProviderErrorHandler`, `ProviderErrorKind`, `ErrorDescription`. Extend the kernel rule in the root `CLAUDE.md` to cover providers.
- [x] 1.2 `Constants.AWS.ErrorCodes` (credential and permission code sets); policy-name constants on `AWSPolicy.UserIAM`.
- [x] 1.3 Test first: write the `AwsErrorHandler` classification tests, built from real SDK exceptions (STS, S3, EC2, IAM, ECS, EMR, OpenSearch), covering the 403-credential codes, the 400 `AccessDeniedException`, `SdkClientException` from a real `ProfileCredentialsProvider` over an empty profile file, a network `SdkClientException` that maps to null, and the exception-class-to-policy table. Then implement `providers/aws/AwsErrorHandler`.
- [x] 1.4 `Event.Command.ProviderError` (`@Serializable`, `@SerialName`, `isError()`, display string). Update `events/CLAUDE.md`.
- [x] 1.5 Test first: write the `CommandFailureReporter` tests (first match wins, wrapped `CompletionException`/`SetupProfileException`, a cause loop terminates, no match emits `ExecutionError`, `CommandFailedException` is reported quietly). Then implement it, with its Koin binding using `getAll()`.
- [x] 1.6 Wire the reporter into `executeWithLifecycle`, a new outer catch in `executeTopLevel`, and the Picocli `executionExceptionHandler`. Add a test where a failure in top-level dispatch produces one event and no stack trace.
- [ ] 1.7 `CommandFailedException` gains `cause`. `emitCredentialError`, the key-pair permission path and `CompactorService.withEcsAccess` keep their events, then rethrow it (D4). `emitCredentialError` branches on the handler's kind.
- [x] 1.8 Delete every catch and handler in `Main.kt`.

## 2. Koin split and SSM move

- [x] 2.1 Split `AWSModule` into `providers/aws/AwsClientsModule` and `services/aws/AwsServicesModule`; `di/KoinModules` lists both.
- [x] 2.2 Move `providers/ssm` to `providers/aws/ssm`, fold `ssmModule` into `AwsClientsModule`, and update imports in main and tests.
- [x] 2.3 Koin resolution test: every client, service and handler resolves, and the reporter resolves with no profile.

## 3. Moves

- [x] 3.1 Move `CompactorTaskDefinition` and `tempo-backend.yaml` to `services/aws`. `CompactorTaskDefinitionTest` passes with package changes only.
- [x] 3.2 Move `EcrPullSecretService` to `services/aws` behind `services/ImagePullSecretService`. Its binding moves to `AwsServicesModule`; update `SidecarService`, `StressJobService` and the test modules.
- [x] 3.3 `Packer` injects `AWSCredentialsManager`, bound as a `factory`.

## 4. Profile

- [x] 4.1 Test first: `AwsKeyPairService.generate` (key saved, permission error emitted then rethrown). Implement it in `services/aws` and remove the EC2 code and `getRequiredIAMPolicies` from `User`. `ShowIamPolicies` and `SetupProfile` call `AWSPolicy.UserIAM.loadAll`.
- [x] 4.2 `AWSClientFactory` takes a `String` region, and adds `requireValidRegion` at today's call site. `SetupProfile` signatures use `String`. A test checks that a blank region is rejected as today.

## 5. Neutral values

- [x] 5.1 Test first: an exhaustive mapping test over every `StepState` value, plus `null`. Then add `SparkJobState` and the mapping in `EMRSparkService`; `SparkService` results and `SparkStatus` use it. Update the Spark command tests.
- [x] 5.2 `OpenSearchService.describeDomainOrNull`, catching outside `withAwsRetry`. `OpenSearchStatus` uses it; its test keeps today's not-found output.

## 6. Rule and docs

- [ ] 6.1 `architecture/PackageBoundaryTest`: R0 sanity, R1a, R1b (the custom catch-clause condition), R2, R3, all with zero violations. Prove R1a and R1b each fail on a deliberate violation, then revert it.
- [ ] 6.2 Docs:
  - `providers/CLAUDE.md`: package tree, the boundary rule, the error handler, "translation wraps a retried call, never runs inside it", the `Up.kt:225` hazard, and no SDK-touching `inline` functions.
  - `services/aws/CLAUDE.md`: new classes and `tempo-backend.yaml`.
  - `events/CLAUDE.md`.
  - Root `CLAUDE.md`: the boundary rule, the kernel rule, and the `AwsClientsModule`/`AwsServicesModule` names in the DI section.
  - `configuration/CLAUDE.md`, if it mentions the compactor.
- [ ] 6.3 Run `./gradlew check` on JDK 21 in a subagent, with no new `@Suppress`, no threshold change and no baseline entry.
- [ ] 6.4 Smoke check: `bin/easy-db-lab --help`, then one AWS command with expired credentials compared against the 0.1 capture.
