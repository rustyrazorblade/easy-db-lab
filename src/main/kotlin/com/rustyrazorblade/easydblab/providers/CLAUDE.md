# Providers Package

This package contains low-level infrastructure providers for AWS, SSH, and Docker. AWS service classes have been moved to `services/aws/` — this package retains only SDK wrappers, data types, retry utilities, and credentials.

## Directory Structure

```
providers/
├── aws/                    # AWS SDK wrappers and data types
│   ├── AwsClientsModule.kt # Koin: region, credentials, the SDK clients, AccountIdentity, IamRoles, RegionalS3Clients, RegionalEc2Clients, AWSClientFactory, AWSCredentialsManager (factory), AwsErrorHandler, SSM
│   ├── AwsErrorHandler.kt  # Classifies AWS credential and permission failures for the ProviderErrorHandler contract
│   ├── RegionalS3Clients.kt # Builds an S3Client for a named region, so services/aws never builds an SDK client
│   ├── RegionalEc2Clients.kt # Builds an Ec2Client for a named region (an AMI build's own region)
│   ├── Ec2BuilderInstances.kt # The EC2 adapter of containers/BuilderInstances: one AMI build's live instances (its build id, in an easy-db-lab packer VPC)
│   ├── AccountIdentity.kt  # AccountIdentity interface + StsAccountIdentity (STS: account ID, credential check)
│   ├── IamRoles.kt         # IamRoles interface (roles, instance profiles, inline policies, service-linked role) + RoleValidationResult
│   ├── AwsIamRoles.kt      # IamRoles on IamClient
│   ├── S3BucketAdmin.kt    # S3BucketAdmin interface; the one adapter is services/aws/AwsS3BucketService on S3Client
│   ├── EC2.kt              # Low-level EC2 operations
│   ├── RetryUtil.kt        # Centralized retry configuration
│   ├── PollUntil.kt        # pollUntil: fixed-interval poll over createPollUntilRetryConfig; pollUntilOrTimeout: the same bounded by a timeout, throws AwsTimeoutException
│   ├── AWSPolicy.kt        # Policy definitions and templates
│   ├── InstanceRolePolicies.kt # EasyDBLabEC2Role's inline policy set (S3Access + SessionManagerInstance)
│   ├── IamPolicy.kt        # IAM policy data model
│   ├── IamPolicySerializers.kt # IAM policy serialization
│   ├── AWSClientFactory.kt # Factory interface for AWS clients
│   ├── AWSCredentialsManager.kt # Credentials management
│   ├── VpcService.kt       # VPC service interface (contract); IngressAuthorization, IngressRevocation, SecurityGroupNotFoundException
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
│   ├── DefaultDockerClientProvider.kt
│   ├── DockerErrorHandler.kt  # An unreachable Docker daemon (connection failure only) as a ProviderErrorHandler, kind unavailable
│   └── DockerBuildRetry.kt    # Docker.buildImage's retry policy: only a build whose content lease an image prune deleted
├── ssh/                    # SSH connection providers
│   ├── SSHModule.kt
│   ├── SSHConnectionProvider.kt
│   ├── DefaultSSHConnectionProvider.kt
│   ├── SshRoute.kt        # How each SSH transport reaches a node (DirectSshRoute)
│   ├── RemoteOperationsService.kt
│   ├── OpenSshPublicKey.kt # The OpenSSH public key line of a private key file (a build key pair is imported from it)
│   ├── InstallFile.kt     # RemoteOperationsService.installFile: the one stage-then-sudo-mv sequence (unique staging name, mode, quoted paths)
│   └── DefaultRemoteOperationsService.kt
```

The SSM Session Manager SSH transport lives in `providers/aws/ssm/` (it is AWS, so the SDK boundary applies), and its Koin bindings are in `AwsClientsModule`: `SsmSessionCommand` (`aws ssm start-session` command lines and credentials), `SsmProxyWrapper` (installs the `edl-ssm-proxy` ProxyCommand wrapper into the profile dir), `SsmSessionTerminator` (ends a stopped forward's session with TerminateSession), `SsmForwardNotReadyException` (unchecked, retryable: a forward exited or timed out before ready) and `SsmSshRoute` (the `ssm` `SshRoute`: ProxyCommands and per-instance port forwards). A session reaches only instances in its own region, so `AwsClientsModule` also binds `RegionalSsmRoutes`, which builds an `SsmSshRoute` for a region named at call time; `ssh/RegionalRemoteOperations` (bound in `SSHModule`) uses it to give an AMI build outside the profile region SSH connections of its own under `ssm` (direct, and the profile region, use the shared service). They open on the first connection asked for, so a build that never reaches its builder starts no SSH client, and closing its `RegionRemote` stops them (or closes the unused route). `containers/Packer.inBuildRegion` opens the site and the remote for the build region together.

## What Belongs Here vs `services/aws/`

**Here (providers/aws/):** Low-level SDK wrappers, interfaces, data types, retry utilities, credentials, policy definitions. Things that directly wrap AWS SDK calls without business logic.

**In services/aws/:** Business-logic services that orchestrate AWS operations, implement service interfaces, and are injected into commands. See [`services/aws/CLAUDE.md`](../../services/aws/CLAUDE.md).

## RetryUtil Factory Methods

**Location:** `providers/aws/RetryUtil.kt`

Always use factory methods instead of creating manual retry configurations.

| Method | Attempts | Backoff | Use Case |
|--------|----------|---------|----------|
| `createIAMRetryConfig()` | 5 | Exponential 1s→16s | IAM operations, handles 404 eventual consistency |
| `createEC2InstanceRetryConfig<T>()` | 5 | Exponential 1s→16s | EC2 instance ops, retries `InvalidInstanceID.NotFound` (matched on the error code, since the message varies with the number of ids) |
| `createAwsRetryConfig<T>()` | 3 | Exponential 1s→4s | S3, EC2, EMR (standard AWS) |
| `createDockerRetryConfig<T>()` | 3 | Exponential 1s→4s | Container start/stop/remove; retries the project `DockerException` (a socket failure, from the client seam), `IOException` and docker-java API errors except the 404 `NotFoundException` |
| `docker/DockerBuildRetry.config<T>()` (not in `RetryUtil`) | 3 | Exponential 1s→2s | `Docker.buildImage`; retries only a build whose content lease containerd deleted under it ("lease does not exist", `Constants.Docker.LEASE_LOST_MESSAGE`, in the daemon's error below the project `DockerException`, whose message holds the build output and is not read). Under the containerd image store every image prune deletes the leases of pulls and builds in progress, whatever its filter; Testcontainers' JVM-hook reaper prunes at each test JVM's exit. A failing `RUN` step or a missing base fails at once, and an interrupted build is never retried and leaves the thread's interrupt flag set. Each retry is printed (`attempt N of M`), and the final failure names its attempt (`attempt N`) |
| `createNetworkRetryConfig<T>()` | 3 | Exponential 1s→4s | Generic network ops |
| `createSshConnectionRetryConfig(interval)` | 30 | Fixed 10s (tests pass a short `interval`) | SSH boot-up (~5 min total); retries `SshException` and `SsmForwardNotReadyException` (`isSshNotReady`, in `providers/ssh/SshNotReady.kt`), fails fast on any other `IOException`. `SshException` is checked: decorate with `decorateCheckedRunnable`/`decorateCheckedSupplier` |
| `createS3LogRetrievalRetryConfig<T>()` | 10 | Fixed 3s | S3 log retrieval (eventual consistency) |
| `createBooleanPollRetryConfig(maxAttempts, interval)` | `maxAttempts` | Fixed `interval` | Retries on result until a boolean check is `true`: the tailnet reachability probe before K3s, and a failed step's `stderr.gz` uploaded after the step ended (28 attempts, 15s apart) |
| `createVpcTeardownRetryConfig<T>()` | 5 | Exponential 5s→40s | VPC teardown DependencyViolation |
| `createVpcAutoCidrRetryConfig()` | 3 | None | VPC creation on an auto-selected CIDR (`SdkException` only); `up` picks a new random unused block per attempt |
| `createLocalPortBindRetryConfig()` | 3 | Fixed 100ms | Local listener lost its port between selection and bind (`BindException` only); the SOCKS proxy selects a new port per attempt |
| `createPollUntilRetryConfig<T>(maxAttempts, interval, done, deadline?, isTransient)` | caller | Fixed `interval` | Poll until a result condition holds; any exception is retried within the budget, only the last look's exception fails; an unmet condition returns the last result. An optional wall-clock `deadline` ends the poll however many attempts remain (`waitForRollouts` passes `Int.MAX_VALUE` attempts and a deadline). An `InterruptedException` is never retried. An optional `isTransient` predicate names the exceptions that are retried; any other fails the poll at once. `pollUntilOrTimeout` (the AWS waits) passes `isTransientAwsFailure`: a throttle, a 5xx or a network failure is retried, access denied, expired credentials, a 4xx and a defect end the wait at once |
| `createEc2ThrottleRetryConfig<T>(backoffBase)` | `Constants.Scale.THROTTLE_MAX_ATTEMPTS` (8) | Exponential from `backoffBase`, capped at 30s | EC2 calls at scale: retries `RequestLimitExceeded`, `InvalidInstanceID.NotFound` and 5xx. RunInstances keeps one client token across its retries, so a retry cannot launch a second set |

### Checked Exceptions Need The Checked Decorators

resilience4j's plain `Retry.decorateRunnable` and `decorateSupplier` catch only
`RuntimeException`. A checked exception thrown from the body (Kotlin does not
declare them, so the compiler will not warn) skips the retry entirely and escapes on the first
attempt, whatever the predicate says. For every retry:

- If the body can throw a checked exception the policy means to retry or to classify, decorate
  with `decorateCheckedRunnable` / `decorateCheckedSupplier` (or `retry.executeCallable`).
  Common sources: MINA's `SshException`, OkHttp's `IOException` (`ObservabilityHttp`),
  `ProcessBuilder.start()`'s `IOException`, `Process.waitFor()`'s `InterruptedException`,
  `BindException`.
- Give the policy a predicate that names exactly what is transient; everything else fails fast.
- Never retry an `InterruptedException`. Restore the interrupt flag and rethrow (`pollUntil` and
  `createPollUntilRetryConfig` do this).
- A library that wraps an `IOException` in a bare `RuntimeException` (docker-java's HTTP
  transport) needs it turned into a typed exception at the client seam before the predicate sees
  it (`DefaultDockerClient.dockerTransport` raises the checked project `DockerException`).
- A body whose only checked throws are local, non-transient failures (a local file delete inside
  an S3 retry) may stay on the plain decorator, so that failure escapes at once.

### Convenience Wrappers

The `with*Retry` wrappers are top-level functions in `RetryWrappers.kt`, beside `RetryUtil`, not
members of it; `RetryUtil` holds only the config factories.

```kotlin
// Short-hand for common patterns (top-level functions in RetryWrappers.kt)
val result = withAwsRetry("describe-cluster") { emrClient.describeCluster(request) }
val result = withEc2InstanceRetry("describe") { ec2Client.describeInstances(request) }
withVpcTeardownRetry("delete-sg") { ec2Client.deleteSecurityGroup(request) }
withS3BucketPolicyRetry("put-bucket-policy") { s3Client.putBucketPolicy(request) }
// EC2 calls at scale (RunInstances, CreateTags, the RunInstances dry run), on createEc2ThrottleRetryConfig
withEc2ThrottleRetry("run-instances-db0", throttleBackoff) { ec2Client.runInstances(request) }

// Poll until a condition holds (top-level pollUntil in PollUntil.kt, over createPollUntilRetryConfig);
// returns the last result if it never does
val pods = pollUntil("wait-for-pod", maxAttempts = 10, interval = Duration.ofSeconds(3), done = { it.isNotEmpty() }) {
    k8sService.getPods().getOrThrow()
}

// The AWS waits (EC2, EMR, OpenSearch) use pollUntilOrTimeout: it retries a look that throws
// (a throttle) within the timeout and throws AwsTimeoutException when the condition never holds.
// Never write a while-sleep loop for a wait. A wait that must stop on a failure of its own besides the
// timeout (EC2InstanceService.waitForInstancesRunning stops on an ended instance, through `done`) calls
// pollUntil directly and passes isTransient = ::isTransientAwsFailure, so a permanent failure still fails at once.
val states = pollUntilOrTimeout("instances to terminate", timeout, interval, done = { it.all { s -> s == TERMINATED } }) {
    describeStates()
}
```

## Koin Registration

`AwsClientsModule.kt` registers the region, the credentials provider, the SDK clients, `AWS`, `AWSClientFactory`, `AWSCredentialsManager`, `AwsErrorHandler` and the SSM transport. Its only edges are `configuration`, `events` and `Context`. The bindings of `services/aws` classes live in `services/aws/AwsServicesModule.kt`, so the services layer never builds an SDK client. `AWSCredentialsManager` is a `factory`, not a `single`: its credentials file is lazy, and a single would keep stale SSO credentials across builds in REPL or Server mode.

## The SDK Boundary

AWS SDK types stay inside `providers/aws` and `services/aws`, and `compactorlease/aws` in the lease agent module (see the root `CLAUDE.md` and `architecture/PackageBoundaryTest`). Three rules follow:

- **No SDK-touching `inline` functions in the adapters.** An inline function copies its body, SDK references included, into every caller outside the adapter, and the boundary test then fails there.
- **Translation wraps a retried call and never runs inside it.** A `catch` that turns an SDK exception into a value or a project exception goes outside `withAwsRetry` (see `OpenSearchService.lookupDomain`). Inside the retry, it would hide the exception from the retry's predicate.
- **`Up.kt` retries on `SdkException` through `createVpcAutoCidrRetryConfig`, from outside the adapter.** If an adapter ever translated exceptions, that retry would silently stop. Adapter-level exception translation is therefore blocked until the ClusterNetwork work moves that retry into the adapter.

## Provider Errors

`kernel/ProviderErrorHandler` returns an `ErrorDescription` (provider, kind, detail, remedy, and the answer's `ProviderResponse`: service, error code, HTTP status, request id) for an exception it owns, or null. `AwsErrorHandler` classifies by SDK class and error code, and checks the code before the HTTP status because STS, IAM and S3 answer 403 for bad credentials:

- **Credentials:** `ExpiredToken`, `InvalidClientTokenId`, `SignatureDoesNotMatch` and the other codes in `Constants.AWS.ErrorCodes.CREDENTIALS`, an expired SSO session (`sso.auth.ExpiredTokenException`, by type), and an `SdkClientException` that an SDK credentials provider raised itself (its first frame past the SDK exception builder is in `software.amazon.awssdk.auth.credentials`, or in `services.ssooidc` / `services.sso` for an `sso_session` profile's "Token is expired" and "Unable to load SSO token") with a message starting with one of `Constants.AWS.ErrorCodes.CREDENTIALS_MESSAGES`. The SDK gives a missing chain no subtype or code, so where it was raised is what tells it from a wrapped S3 or network error that quotes the same words. The remedy is `easy-db-lab profile setup`, then, conditionally on the user having the AWS CLI, `aws sso login` (add `--profile` and the profile name for a non-default profile; the handler has no profile to name) and `aws sts get-caller-identity`; the AWS CLI is never assumed. The provider check is pinned by tests that build the exceptions from real SDK providers; the expired-SSO case is recognised by `sso.auth.ExpiredTokenException`'s type.
- **Permission denied:** `AccessDenied`, `AccessDeniedException`, `UnauthorizedOperation`, or any other 403 except clock skew. The remedy names the lab policy for the exception's service (`AWSPolicy.UserIAM` constants) and `easy-db-lab show-iam-policies <policy>`, worded "if this is missing permissions on your AWS user" and noting what else can also deny the request (a bucket policy, SCP or KMS key policy for S3; an SCP or permissions boundary for any other service), because a 403 does not prove the cause. An STS denial names no policy: its only call, `GetCallerIdentity`, needs no IAM permission, so the remedy points at an SCP, permissions boundary or session policy.
- A network `SdkClientException` is not claimed and stays an ordinary execution error.

A handler claims an exception by its type alone and takes no dependencies, so it works before a profile exists. `services/CommandFailureReporter` walks the cause chain across every registered handler (`getAll()` in Koin) and emits `Event.Command.ProviderError`; a site that adds detail (the AWS setup credential check, the key-pair permission path) emits the same event itself with `ErrorDescription.toEvent()`, then its policy listing, and rethrows `CommandFailedException(message, cause)` so the user sees one block.

**Docker.** docker-java's transport wraps a socket failure in a bare `RuntimeException(IOException)` that names no path (for a missing default socket it is just "No such file or directory"). `DefaultDockerClient` is the seam: every call, including the pull's `awaitCompletion`, runs in `dockerTransport`, which turns that shape into the project `DockerException`, naming the endpoint (`DOCKER_HOST` or the default socket). `DockerErrorHandler` claims a connection failure below a Docker exception, or one whose message names the configured endpoint: for a unix `DOCKER_HOST` (colima, for example) the socket path, for TCP the host and port.

## SSH Provider

- `SSHConnectionProvider` — manages connection pool, auto-reconnects, keepalive
- `RemoteOperationsService` — high-level SSH ops (execute, upload, download)
- Registered in `SSHModule.kt`: provider as **singleton**, remote ops as **factory**

### SSH Transport (`direct` / `ssm`)

The profile's `User.sshTransport` decides how a TCP connection to a node's port 22 is made; SSH
itself is the same either way. It is read from the profile at runtime, never snapshotted into
`state.json`, because clusters are provisioned identically under both transports.

The transport is decided in exactly one place: the `SshRoute` binding in `SSHModule.kt`
(`DirectSshRoute` or `aws/ssm/SsmSshRoute`). Everything else asks the route; do not add another
`when (sshTransport)` elsewhere. The only other reader is `up`'s local-tooling preflight.

The binding is a `LazySshRoute`: the profile is read on the route's first use, not when Koin
builds it. `profile setup` creates the profile, and Koin builds the SOCKS proxy (which holds the
route) before that command runs, so a route that read the profile at construction made
`profile setup` fail on a new profile. Nothing built at Koin resolution may read `User` or
anything else that needs the profile (`ProcessSocksProxyService` takes `verifyAttempts` as a
function for the same reason).

- **OpenSSH paths** (the SOCKS tunnel, every `env.sh` helper and the `bin/` SSH wrappers) use
  `sshConfig`. `ClusterConfigWriter.writeSshConfig` puts every cluster-wide option (host keys, user,
  identity, keepalives, `ConnectTimeout`) in one `Host db* app* control* !*.*` block, its wildcards
  derived from `ServerType`; `!*.*` keeps every dotted name and IP address out, because OpenSSH patterns
  have no character classes and `db*` alone would match `db.boo.com`. Then one block per host, and it
  ends with two disjoint `Match originalhost` blocks (`!originalhost db*,app*,control*` and
  `originalhost db*.*,app*.*,control*.*`) that include `~/.ssh/config` and then `/etc/ssh/ssh_config`,
  so any other host (`github.com`, `db.boo.com`, an IP) resolves as it would without `-F`, and cluster
  hosts never read the personal or system config (`GeneratedSshConfigResolutionTest` checks
  this with real `ssh -G`). `ClusterConfigurationService` writes `route.proxyCommand(host)` into each `Host` block (and
  into the fallback config `env.sh` writes when `sshConfig` is missing). Under `ssm` that is
  `aws ssm start-session --document-name AWS-StartSSHSession`. Each `Hostname` line still
  directly follows its `Host` line. Nothing reads the file that way any more (the old `env.sh`
  `start-socks5` read it with `grep -A 1`; the CLI now manages the tunnel), but the order is
  harmless and the `ssm-ssh-transport` spec scenario still names it.
  Under every transport the config carries global `ServerAliveInterval`/`ServerAliveCountMax`
  (`Constants.Ssh`): an idle SOCKS tunnel's connection can die silently (a laptop sleep or a network
  change on a direct connection, Session Manager's 20-minute idle timeout under `ssm`), and without
  keepalives ssh lingers with its local port open; with them it exits within about 90s. Every
  transport's config also carries `ConnectTimeout 30` (`Constants.Ssh.CONNECT_TIMEOUT_SECONDS`),
  because keepalives start only after auth, and a connect that never completes (a dropped SYN, or a
  session that passes no data) would otherwise hang ssh at the banner exchange.
  Each `ProxyCommand` runs through `edl-ssm-proxy` (`aws/ssm/SsmProxyWrapper`, the packaged
  resource `ssm/edl-ssm-proxy.sh` written into the profile dir): the AWS CLI does not pass a termination on
  to `session-manager-plugin`, and a stuck plugin ignores its stdin closing, so the wrapper ends the
  whole tree when ssh closes stdin, exits, is killed, or sends SIGHUP.
- **The in-process MINA client** cannot run a `ProxyCommand`. `DefaultSSHConnectionProvider`
  dials `route.endpoint(host)`: the public IP under `direct`, or under `ssm` a loopback port
  forwarded by an `AWS-StartPortForwardingSession` process (one per instance per JVM).
- Both paths build their command lines with `aws/ssm/SsmSessionCommand`, so region and
  credentials cannot drift. Static keys reach the AWS CLI through the profile's
  `AWSCredentialsManager` file (`AWS_SHARED_CREDENTIALS_FILE`), never as environment variables
  written into `sshConfig`, and with `AWS_CONFIG_FILE=/dev/null`, so the operator's
  `~/.aws/config` cannot replace them.
- A forward that fails to start throws `aws/ssm/SsmForwardNotReadyException`, which is unchecked, so
  the SSH operations retry treats it as a `RuntimeException`. `createSshConnectionRetryConfig`
  lists it, which covers the window before a fresh node's SSM agent registers.
- **Retrying SSH needs the checked decorators** (see "Checked Exceptions Need The Checked
  Decorators" above). MINA reports a refused, timed-out or dropped connection as `SshException`,
  a checked `IOException`. `up`'s readiness wait and every `DefaultRemoteOperationsService`
  operation use `decorateCheckedRunnable`/`decorateCheckedSupplier`; both retry `SshException`
  and fail fast on any other `IOException`.
- Forward processes, including the `session-manager-plugin` child, are stopped by
  `SsmSshRoute.close()` and its JVM shutdown hook, from the moment they are spawned. Every stop
  also calls TerminateSession (`aws/ssm/SsmSessionTerminator`, the SDK `SsmClient` from `AwsClientsModule`)
  for the session ID the CLI printed, because killing the processes leaves the session open in
  Session Manager for 20 minutes. Nothing calls
  `SSHConnectionProvider.stop()` in production today; if something starts to, it closes the route
  too.
- **A failed connection retires its path.** An SSM forward can be alive yet carry no SSH data, so
  `DefaultSSHConnectionProvider` calls `route.invalidate(host)` when connect, key exchange or auth
  fails, and `DefaultRemoteOperationsService` calls `connectionProvider.discard(host)` on any
  `SshException` before retrying (that also drops the cached session). `SsmSshRoute.invalidate`
  stops the forward and ends its session; `DirectSshRoute` ignores it. Any new SSH retry must do
  the same, or it will redial the stuck forward.
- The route also supplies the in-process client's key-exchange and auth bound
  (`SshRoute.authTimeout`): `Constants.Ssm.SSH_AUTH_TIMEOUT_SECONDS` (30s) under `ssm`, where a
  healthy one takes about 1-1.5s; MINA's default (120s) under `direct`.
- `SSHConnectionCache` connects under a per-host lock, so connections to different hosts open in
  parallel and two requests for one host share one connection. Never connect inside
  `ConcurrentHashMap.compute`: it blocks every host whose key shares the bin.
- `aws/ssm/SdkSsmSessionTerminator` builds its `SsmClient` lazily: the first forward's start begins a
  daemon warm-up (client build and credential resolution) that overlaps the forward's start, and
  TerminateSession waits for it within its deadline. Nothing is built at Koin resolution. A failed
  preparation is not kept: the next call prepares again (client and credentials), shared by
  concurrent callers. TerminateSession's total budget is 20s
  (`Constants.Ssm.TERMINATE_SESSION_TIMEOUT_SECONDS`), with 6s per SDK attempt.
- The route also supplies the SOCKS tunnel's verification budget (`SshRoute.tunnelVerifyAttempts`,
  read in `ProxyModule`): about 30s under `ssm`, about 5s under `direct`. It also supplies
  `SshRoute.tunnelConnectFailureHint(sshOpenToAll)`, the sentence a tunnel failure adds when
  `ssh` never connected (`SshConnectStage`): under `direct`, that the security group allows SSH only
  from the IPs it lists and that `easy-db-lab ingress allow` adds the current public IP, unless the
  cluster is `init --open`; none under `ssm`. `ProxyModule`
  reads `open` from the cluster state when the failure is built, not at Koin start.
- `Host.instanceId` is required under `ssm`. Any new place that builds a `Host` must populate it
  (`ClusterHost.toHost()` does).

### Credential Redaction Is A Boundary, Not A Call-Site Convention

Remote commands legitimately carry a URL with an embedded credential — a git clone of a private
fork is the common case — and the remote side echoes the command straight back on failure.
`ssh/SSHClient` therefore redacts at the single point every remote command passes through, covering
all four sinks at once: the debug log, each line passed to the `RemoteOutputSink`, the returned
`Response`, and the thrown `RemoteCommandFailedException`. The helpers are in `Redaction.kt` in the
root package: `redactUrlCredentials` (URL userinfo, used for the `Response`) and `redact`, which also
hides `authkey=`, `token=`, `secret=`, `password=` and `key=` values (also ending a name, as in `AWS_SECRET_ACCESS_KEY=`), `Authorization:` header values
and AWS access key ids (used for streamed lines, the log, the exception). `output/ConsoleNodeOutput`
runs `redact` again on every line it prints or keeps, pod log lines too, so a transport other than
SSH is covered as well.

Rules that follow from this:

- **Do not redact per call site.** A convention only has to be forgotten once to leak. If a new
  sink appears (a new exception type, a new event), redact it inside `SSHClient` too.
- **Do not lower `org.apache.sshd` below `INFO`** in `easydblab-logback.xml`. MINA logs every
  remote command verbatim at DEBUG, one layer *below* this boundary, straight into `debug.log` and
  its 30 days of archives. `LogbackConfigurationTest` locks this in.
- **HTTP wire and header loggers are `OFF`** in `easydblab-logback.xml` (`org.apache.http.wire`
  and `.headers`, `org.apache.hc.client5.http.wire` and `.headers`, `org.apache.hc.core5.http2.frame`,
  `software.amazon.awssdk.request`, the SDK's SigV4 signers, the HttpClient `MainClientExec`
  request-line loggers, `okhttp3.OkHttpClient`, fabric8's `HttpLoggingInterceptor`,
  `io.netty.handler.logging`). They wrote the SSO bearer token, Authorization headers, the session
  token (in the signer's canonical request), role credentials and the account ID (in request query
  strings) into `debug.log`. A new HTTP client's wire or header logger goes on
  that list and in `LogbackConfigurationTest`.
- `executeRemotely(secret = true)` is stronger still: neither the command nor any of its output is
  repeated anywhere, because a failing secret command routinely echoes its own argument back.
  `SSHClient` gives a secret command no sink at all.

### Remote Output Streams To A Sink, Never To Events

`ISSHClient.executeRemoteCommand(command, sink: RemoteOutputSink?, secret)` takes the sink that receives what the command prints, one whole line at a time, while it runs. `SSHClient` tees each MINA stream into its capture buffer (for the `Response` and the failure) and an `output/LineSplittingOutputStream`, which hands each redacted line to the sink with its `NodeStream` (`Stdout` or `Stderr`). The splitter is UTF-8 safe across reads. A `\r` without `\n` starts a segment that replaces the current one, and at `\n` or close the last non-empty segment is emitted once, so a progress bar prints its final value. A line is cut into pieces of at most `Constants.NodeOutput.MAX_LINE_BYTES`, before a character that does not fit, and a URL that starts within `Constants.NodeOutput.URL_CARRY_BYTES` of a cut moves whole to the next piece so its credentials are still redacted.

A command runs through `ssh/RemoteExec` (`ChannelRemoteExec` in production; tests pass a fake to `SSHClient`'s constructor). `RemoteExec` does not use MINA's `executeRemoteCommand`, because that waits in `waitFor`, which catches an `InterruptedException`, drops it and keeps waiting, so Ctrl-C in the REPL could not stop a remote command. `ChannelRemoteExec` opens the exec channel itself and waits on its close. A command with input (`executeRemoteCommandWithInput`) runs through the same `RemoteExec`, which sets the input as the channel's stdin, so it gets the same interrupt and failure handling. Closing the channel does not stop the remote process (there is no pty, so sshd sends no SIGHUP), so an interrupt sends the RFC 4254 `signal` request `INT` (OpenSSH runs `killpg` on the command's process group, and refuses it for a root login, so the test logs in as a normal user as nodes do), then `KILL` after `Constants.Ssh.INTERRUPT_SIGNAL_GRACE_SECONDS`, then closes the channel and rethrows. An interrupt while the channel opens (MINA's `InterruptedIOException`) is rethrown as an `InterruptedException`. The session stays usable (`SSHClientStreamingIntegrationTest` checks, against a real sshd, that the call returns, the remote process is gone and the next command runs, and that a command which ignores `INT` is killed after the grace).

MINA pins its caller's carrier: `AbstractClientChannel.open` blocks inside `synchronized`, and its waits use `Object.wait`. With bring-up running one virtual thread per node, enough pinned callers left no carrier for anything else, and `up` hung on JDK 21. So `ssh/PlatformThreadCall.run` runs every MINA call of a virtual caller (connect, `executeRemoteCommand`, SCP upload and download, `session.close`) on a cached pool of platform daemon threads, passing the caller's interrupt on; a platform caller runs the block itself. The sink is wrapped in `ssh/OffThreadOutputSink`, whose drain is a platform daemon thread (`Constants.NodeOutput.DRAIN_THREAD_NAME`), so MINA's NIO threads never wait for the console. `SSHClient.operationLock` is a `ReentrantLock`, never `synchronized`. Any new MINA call goes through `PlatformThreadCall` too, and everything it needs that takes `operationLock` (the cached SCP client) is fetched before the hop: the lock is held by the virtual caller, and the platform thread waiting for it would deadlock. The hand-off passes a caller's interrupt only while the call runs: one from before the call is applied when it starts, and none reaches the pooled thread after it ends.

Both splitters, then the off-thread sink, are closed in `finally`, each in turn even if one throws, so a failed command still delivers its partial last line and all of its output prints before the call returns. On an interrupted thread the sink waits at most `Constants.NodeOutput.INTERRUPTED_DRAIN_WAIT` and then restores the flag. A drain that times out calls the sink's `stalled()`, and the node gets the notice `output still printing; the tail may be incomplete`. `DefaultRemoteOperationsService` builds the sink only when `output` is true and `secret` is false; it writes to `output/NodeOutput` under `host.alias` with `host.instanceId`. `upload` prints one `notice` (callers may pass their own: `installFile` prints `installing <path> (mode <mode>)` instead of the staging path), and `uploadDirectory` prints one. A notice is printed but never kept in the node's tail. When the retry policy runs a command again it prints a `mark` ("retrying the command (attempt N)"), which is kept, so a failure's tail shows which run a line came from. Nothing in this layer emits an event: raw output is not a domain fact (see `events/CLAUDE.md`). A test double of `ISSHClient` feeds its configured output through the real splitter into the sink it is given (`MockSSHClient`).

## Docker Provider

- `DockerClientProvider` — lazy-initialized Docker client (expensive to create)
- Registered in `DockerModule.kt` as **singleton**
- `Docker` instances created as **factory** (stateful, tied to context)
