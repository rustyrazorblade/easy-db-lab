## MODIFIED Requirements

### Requirement: Provider failures are reported with a remedy

When a command fails, the system SHALL ask every registered provider error handler to describe the failure, walking the exception's cause chain from the outermost exception inward. A handler SHALL claim an exception by its type alone, not by the active target, so that the classification works before any cluster state exists. On the first match the system SHALL emit `Event.Command.ProviderError` with the provider, the kind (`credentials`, `permission-denied`, `rejected` or `unavailable`), the provider's detail message, a remedy, and, when the handler knows them, two optional structured fields: `request` (verb, resource, namespace, name, principal) and `policy` (name, level, violations). A failure that no handler claims, or a handler that sets neither optional field, SHALL serialize without them. When no handler claims the exception, the system SHALL emit `Event.Command.ExecutionError` whose message is the exception's cause chain, outermost first, each entry written `<exception simple class name>: <message>` and the entries joined by a newline and `  caused by: `. In both cases the exit code SHALL be non-zero.


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
- **AND** the message does not assert that the cause is a missing user permission, and notes what else can also deny the request: a bucket policy, SCP or KMS key policy for S3, an SCP or permissions boundary for any other service

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
- **THEN** `Event.Command.ExecutionError` is emitted with the cause chain, starting `SdkClientException: <message>`, and no credentials message

#### Scenario: Exception owned by no provider

- **GIVEN** a command that fails with an exception that is not an AWS SDK exception
- **WHEN** the command fails
- **THEN** `Event.Command.ExecutionError` is emitted with the cause chain, one `<class>: <message>` entry per cause, each inner one on a new line after `  caused by: `

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


## ADDED Requirements

### Requirement: Kubernetes API failures are described with the request and the reason

The Kubernetes adapter SHALL register a provider error handler that claims a Fabric8 `KubernetesClientException` by its HTTP status code and the API server's status message alone, with no collaborators, so that it works before a profile, a workspace or cluster state exists. It SHALL claim only the failures below and SHALL leave every other `KubernetesClientException` (a 404, 409 or 422, a transport failure with code 0, a dry-run-not-supported 400) to `Event.Command.ExecutionError`:

- A 403 whose message is an RBAC denial (`User "<principal>" cannot <verb> resource "<resource>" ...`) SHALL be kind `permission-denied`, with `request` holding the RBAC verb, the resource (with its subresource, for example `pods/exec`), the namespace (absent at the cluster scope), the object name when the server gave one, and the principal. The remedy SHALL say that easy-db-lab needs cluster-admin on the cluster and name the principal to grant it to.
- A 401, or a 403 for the principal `system:anonymous`, SHALL be kind `credentials`. The remedy SHALL say to re-authenticate the kubeconfig user and that an exec auth plugin may have returned no credential.
- A Pod Security admission rejection (`violates PodSecurity "<level>:<version>": ...`) SHALL be kind `rejected`, with `policy` holding the name `PodSecurity`, the level and version, and one entry per violation (a comma inside a violation's parentheses does not split it), and `request` holding the verb and the rejected object.
- An admission webhook denial (`admission webhook "<name>" denied the request: ...`) or a ValidatingAdmissionPolicy denial, with status 400, 403 or 422, SHALL be kind `rejected`, with `policy` naming the webhook or policy and the server's reason as its one violation.
- When an RBAC or admission message does not match the expected form, `request` SHALL fall back to the resource, namespace and name of the request URI and the HTTP method of the failure message.

The detail SHALL be the API server's status message, never the request URI's query or any credential. The console SHALL show no stack trace; the throwable goes to the log only.

#### Scenario: RBAC denial in a namespace

- **GIVEN** a command whose Fabric8 call fails with 403 and the message `pods is forbidden: User "alice" cannot list resource "pods" in API group "" in the namespace "lab"`
- **WHEN** the command fails
- **THEN** `Event.Command.ProviderError` is emitted with provider Kubernetes, kind `permission-denied`, and `request` verb `list`, resource `pods`, namespace `lab`, principal `alice`
- **AND** the console output names the verb, the resource, the namespace and the user, and shows no stack trace
- **AND** the exit code is non-zero

#### Scenario: RBAC denial at the cluster scope and on a subresource

- **GIVEN** a 403 denying `create` on `pods/exec`, or a 403 denying `list` on `nodes` "at the cluster scope"
- **WHEN** the command fails
- **THEN** `request` names the subresource `pods/exec`, or names no namespace for the cluster-scoped denial

#### Scenario: Anonymous 403 and 401 are credential failures

- **GIVEN** a 403 for the principal `system:anonymous`, or a 401 `Unauthorized`
- **WHEN** the command fails
- **THEN** the kind is `credentials`, not `permission-denied`, and the remedy says to re-authenticate the kubeconfig user
- **AND** a 401 names no principal

#### Scenario: Pod Security rejection

- **GIVEN** a Pod create that fails with 403 and the message `pods "x" is forbidden: violates PodSecurity "restricted:latest": allowPrivilegeEscalation != false (container "c" must set securityContext.allowPrivilegeEscalation=false), runAsNonRoot != true (pod or container "c" must set securityContext.runAsNonRoot=true)`
- **WHEN** the command fails
- **THEN** the kind is `rejected` and `policy` is `PodSecurity` at level `restricted`, version `latest`, with two violations, each naming the field that violated it
- **AND** the console output names the policy level and each violating field

#### Scenario: Admission webhook denial

- **GIVEN** a call that fails with `admission webhook "validate.kyverno.svc" denied the request: ...` and status 400, 403 or 422
- **WHEN** the command fails
- **THEN** the kind is `rejected` and `policy` names `validate.kyverno.svc` with the server's reason

#### Scenario: Unrecognised message falls back to the request

- **GIVEN** a 403 whose message matches neither the RBAC nor the admission form, or whose status body was not JSON
- **WHEN** the command fails
- **THEN** it is still classified by its code, and `request` holds the resource, namespace and name from the request URI and the HTTP method of the failure

#### Scenario: Other Kubernetes failures are not claimed

- **GIVEN** a `KubernetesClientException` with code 404, 409 or 422 (not an admission denial), a dry-run-not-supported 400, or code 0 wrapping a `ConnectException` or a SOCKS failure
- **WHEN** the command fails
- **THEN** `Event.Command.ExecutionError` is emitted with the cause chain

#### Scenario: Wrapped Kubernetes failure

- **GIVEN** a 403 `KubernetesClientException` kept as the cause of an `IllegalStateException` or inside a `CompletionException`
- **WHEN** the command fails
- **THEN** the handler finds it through the cause chain and the Kubernetes description is shown

#### Scenario: Real API server messages

- **GIVEN** a K3s TestContainer, a ServiceAccount-token kubeconfig with no RBAC binding, and a namespace labelled `pod-security.kubernetes.io/enforce=restricted`
- **WHEN** a call is made as that ServiceAccount, and a privileged Pod is created in that namespace
- **THEN** the first is described as `permission-denied` with the verb, resource, namespace and the ServiceAccount principal, and the second as `rejected` with `PodSecurity` level `restricted` and its violating fields

### Requirement: Kubernetes failures keep their cause up to the reporter

A site that turns a Kubernetes failure into another exception SHALL keep the Fabric8 exception as its cause, so that the reporter can describe it. No site SHALL rebuild the failure as a message-only exception. A site that reports the failure itself and throws `CommandFailedException` is unaffected. The capability probe SHALL carry the baseline Pod's `KubernetesClientException` as the cause of its failure, and SHALL leave the cluster-admin explanation to the handler.

#### Scenario: Post-step failure on a cluster without RBAC

- **GIVEN** `up`'s post steps, `grafana update-config` or the observability deploy fail with a 403
- **WHEN** the command fails
- **THEN** the user sees the Kubernetes `permission-denied` description, not `Failure executing: ...` text

#### Scenario: Probe baseline rejected by Pod Security

- **GIVEN** `init --kubeconfig` against a namespace that rejects the probe's baseline Pod under Pod Security
- **WHEN** the probe fails
- **THEN** the user sees the `rejected` description with the level and the violating fields

### Requirement: Kubernetes polls do not retry an authentication or authorization failure

A poll or retry around a Kubernetes call SHALL treat a 401 or 403 `KubernetesClientException` as not transient and SHALL fail on the first such answer, not at its deadline. The predicate is passed at each Kubernetes poll; the shared retry defaults are unchanged.

#### Scenario: Rollout wait meets a 403

- **GIVEN** a rollout-status, stress-job, backend-workload or collector-stop poll whose Kubernetes call answers 403
- **WHEN** the poll runs
- **THEN** it fails after the first attempt, and the failure is described as `permission-denied`
