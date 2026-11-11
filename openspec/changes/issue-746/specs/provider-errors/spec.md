## ADDED Requirements

### Requirement: Provider failures are reported with a remedy

When a command fails, the system SHALL ask every registered provider error handler to describe the failure, walking the exception's cause chain from the outermost exception inward. A handler SHALL claim an exception by its type alone, not by the active target, so that the classification works before any cluster state exists. On the first match the system SHALL emit `Event.Command.ProviderError` with the provider, the kind (`credentials`, `permission-denied` or `unavailable`), the provider's detail message and a remedy. When no handler claims the exception, the system SHALL emit `Event.Command.ExecutionError` exactly as today. In both cases the exit code SHALL be non-zero.

#### Scenario: Expired credentials

- **GIVEN** a command whose AWS call fails with an `StsException` with error code `ExpiredToken`
- **WHEN** the command fails
- **THEN** the user sees a credentials message with a remedy that says to run `easy-db-lab profile setup` and, conditionally ("If you use SSO with the AWS CLI"), `aws sso login` and `aws sts get-caller-identity` (it names no profile, because the handler has no profile, and it does not assume the AWS CLI is installed), not a raw cause chain
- **AND** the exit code is non-zero

#### Scenario: Bad credentials that arrive as a 403

- **GIVEN** an AWS call that fails with status 403 and an error code that means invalid credentials, such as `InvalidClientTokenId` or `SignatureDoesNotMatch`
- **WHEN** the command fails
- **THEN** the failure is reported as a credentials error, not as permission-denied

#### Scenario: Permission denied from each AWS service

- **GIVEN** an AWS call that fails with a permission error (`AccessDenied`, `AccessDeniedException`, `UnauthorizedOperation`, or another 403) from STS, S3, EC2, IAM, ECS, EMR or OpenSearch
- **WHEN** the command fails
- **THEN** the user sees a permission-denied message
- **AND** the message names the lab IAM policy that grants that service, and the command that prints it
- **AND** the message does not assert that the cause is a missing user permission, and notes that a bucket policy, SCP or KMS key policy can also deny the request

#### Scenario: STS denial

- **GIVEN** an STS call (`GetCallerIdentity`) that fails with a permission error
- **WHEN** the command fails
- **THEN** the message names no lab IAM policy, because no lab policy grants STS, and points at an organisation-level control (SCP, permissions boundary or session policy)

#### Scenario: No credentials found

- **GIVEN** the SDK raises an `SdkClientException`, with no status code, because it found no credentials or the SSO session expired
- **WHEN** the command fails
- **THEN** the user sees a credentials message

#### Scenario: Docker is not running

- **GIVEN** a command that fails because the Docker daemon cannot be reached (a refused or broken socket, or a missing unix socket file), possibly inside the project's `DockerException`
- **WHEN** the command fails
- **THEN** `Event.Command.ProviderError` is emitted with kind `unavailable`, provider Docker, and a remedy to check that Docker is running
- **AND** the exit code is non-zero

#### Scenario: Docker answers with an error

- **GIVEN** a command that fails with a Docker API answer such as `NotFoundException` or `ConflictException`
- **WHEN** the command fails
- **THEN** `Event.Command.ExecutionError` is emitted with Docker's own message and no "check that Docker is running" remedy

#### Scenario: Network failure is not misclassified

- **GIVEN** an `SdkClientException` caused by a network error
- **WHEN** the command fails
- **THEN** `Event.Command.ExecutionError` is emitted, as today

#### Scenario: Exception owned by no provider

- **GIVEN** a command that fails with an exception that is not an AWS SDK exception
- **WHEN** the command fails
- **THEN** `Event.Command.ExecutionError` is emitted exactly as today

#### Scenario: Wrapped provider exception

- **GIVEN** an AWS SDK exception wrapped in a `CompletionException` or a `SetupProfileException`
- **WHEN** the command fails
- **THEN** the handler finds it through the cause chain and the provider message is shown

#### Scenario: Cause loop terminates

- **GIVEN** an exception whose cause chain contains a loop
- **WHEN** the command fails
- **THEN** reporting terminates and emits exactly one event

#### Scenario: Failure before the command lifecycle starts

- **GIVEN** a failure raised during top-level dispatch, before or after the command's lifecycle (for example while restoring state, or while closing resources)
- **WHEN** the failure propagates
- **THEN** it is reported through the same path, with no raw stack trace on the console

#### Scenario: Already-explained failures print one error block

- **GIVEN** a site that already emits a detailed failure event, such as the profile credential check, the key-pair permission path, or the compactor's ECS access check
- **WHEN** that site fails
- **THEN** the user sees that site's detailed message once
- **AND** no second provider or execution-error block follows it

### Requirement: The entry point holds no provider error handling

The application entry point SHALL start dependency injection and dispatch the command line only. It SHALL import no AWS SDK type and SHALL have no catch for provider exceptions.

#### Scenario: Entry point is provider-free

- **WHEN** the entry point source is inspected
- **THEN** it imports no AWS SDK type and catches no SDK exception
