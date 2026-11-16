# Tasks

Ordered by dependency. Groups 1–3 are independent of each other; group 4 depends on 3; group 5 depends on 1–4.

Practice TDD throughout: write the failing test first. Use AssertJ, extend `BaseKoinTest`, use `@TempDir` for temporary directories. No mock-echo tests — every test must exercise a decision, transformation, or real failure mode. Never mock `TemplateService`. Prefer K3s TestContainers over mocks for anything applying real K8s state.

## 1. Root cause: SSH host-key verification

- [x] 1.1 Write a failing test asserting `ClusterConfigWriter.writeSshConfig()` emits `UserKnownHostsFile=/dev/null` alongside the existing `StrictHostKeyChecking=no`, and that both appear before any `Host` block.
- [x] 1.2 Add `UserKnownHostsFile=/dev/null` to `ClusterConfigWriter.writeSshConfig()`.
- [x] 1.3 Write a test asserting the generated config for a multi-node cluster still produces one `Host <alias>` / `Hostname <publicIp>` pair per host, so the new directive does not disturb the existing structure.

## 2. Proxy verifies before it publishes

- [x] 2.1 Write a failing test: when the local port never accepts connections, `ProcessSocksProxyService.startNewProxy()` throws, and `Constants.Proxy.PORT_PROPERTY` is left unset. Use a `@TempDir` working directory as `ProcessSocksProxyServiceTest` already does.
- [x] 2.2 Change `verifyProxyAcceptingConnections()` to throw instead of logging `"proceeding anyway"`. The exception message must name the SOCKS proxy as the failing component and reference `socks5-proxy.log`.
- [x] 2.3 Write a failing test: `isValidProxy()` rejects a recorded proxy whose PID is alive but whose port is not accepting connections (zombie tunnel), so it is not reused and its port is not republished.
- [x] 2.4 Add an `isPortAccepting()` check to `isValidProxy()`, alongside the existing PID / `controlIP` / `sshConfig` checks.
- [x] 2.5 Write a failing test asserting `startNewProxy()` invokes `ssh` with the gateway host's recorded alias, not the literal `"control0"`.
- [x] 2.6 Replace the hardcoded `"control0"` argument in `startNewProxy()`'s `ProcessBuilder` with `gatewayHost.alias`.
- [x] 2.7 Update the KDoc on `startNewProxy()` so the documented contract and the code agree — the comment currently promises verification-before-publish that the code did not perform.

## 3. Proxy pre-flight becomes opt-in

- [x] 3.1 Create `annotations/RequiresProxy.kt` — `@Target(AnnotationTarget.CLASS)`, `@Retention(AnnotationRetention.RUNTIME)`, with a class-level KDoc explaining that it declares a command reaches the private K8s API (Fabric8) or a private cluster HTTP endpoint, and therefore needs the SOCKS tunnel.
- [x] 3.2 Write a failing test: `DefaultCommandExecutor` does not start a proxy for a command lacking `@RequiresProxy`, even when the cluster is provisioned, infrastructure is UP, and Tailscale is disabled.
- [x] 3.3 Write a failing test: for a command carrying `@RequiresProxy`, a proxy failure propagates and the command exits non-zero — it is not swallowed into `log.warn "proceeding without proxy"`.
- [x] 3.4 Gate `ensureProxyRunning()` on the `@RequiresProxy` annotation in `checkRequirements()`, alongside the existing `@RequireDocker` / `@RequireSSHKey` / `@RequireProfileSetup` checks. Remove the `try`/`catch` from `ensureProxyRunning()` and correct its now-false KDoc.
- [x] 3.5 Audit the command surface with the mechanical criterion from design D7: does the execution path reach `K8sClientProvider` or `ProxiedHttpClientFactory`? Annotate `Up`, `Status`, `GrafanaInstall`, `GrafanaUpdateConfig`, `PlatformCreatePvs`, `KitInstallCommand`, `KitRunnerCommand`, `KitStatusCommand`, `LogsBackup`, `MetricsBackup`, `cassandra/Start`, `cassandra/Stop`, `cassandra/Restart`, and the `cassandra/stress/*` family. Verify each by execution path rather than by name.
- [x] 3.6 Confirm `Down` is **not** annotated, and write a test asserting `Down` starts no proxy — it unpublishes the port and kills the ssh process as its first actions.
- [x] 3.7 ~~Write a guard test that fails when a command reaching `K8sClientProvider` or `ProxiedHttpClientFactory` lacks `@RequiresProxy`.~~ **Dropped by owner decision.** Any such test hand-maintains either a list of proxy-reaching service types or a suppression list for bytecode-reachability false positives. Both are lists masquerading as checks — they pass while silently covering less. The annotation is applied by reading the execution path; the opt-in design (D7) makes a missed annotation benign. `RequiresProxyAuditTest.kt` was written, reviewed, and deleted.

## 3a. `Status` degrades instead of failing (design D9)

- [x] 3a.1 Write a failing test: with the SOCKS proxy unable to establish, `Status` still reports EC2 instance and VPC networking state rather than aborting.
- [x] 3a.2 Write a failing test: under the same conditions, only the sections sourced from the private Kubernetes API (stress jobs, ClickHouse) are marked unavailable, each stating the proxy failure as the reason — while database versions still render over SSH, which never traverses the tunnel. Unavailability follows the transport, not a section list.
- [x] 3a.3 Write a failing test: a degraded `Status` exits non-zero, so a partial report is never read by a script as a healthy cluster.
- [x] 3a.4 Implement the degradation in `Status`: catch proxy establishment failure, render the observable sections, mark the rest unavailable with cause. Add typed `Event` fields for an unavailable section and its reason — do not use `Event.Message` or `Event.Error`.
- [x] 3a.5 Add a KDoc note on `Status` recording that this is the single specified exception to the fail-fast invariant, permitted because `Status` is read-only, and that no state-mutating command may take it.

## 4. Cluster shape invariants move before provisioning

- [x] 4.1 Add `Event.Provision` typed events for the two pre-provisioning validation failures (missing control node, missing S3 bucket), with structured fields. Do not use `Event.Message` or `Event.Error`.
- [x] 4.2 Write a failing test: `up` with a configuration producing no control node fails before any EC2 instance is launched, with an error stating a control node is required.
- [x] 4.3 Write a failing test: `up` with no S3 bucket configured fails before any EC2 instance is launched, with an error stating an S3 bucket is required.
- [x] 4.4 Implement both invariant checks ahead of EC2 provisioning in `Up`.
- [x] 4.5 Remove the now-unreachable `controlHosts.isEmpty()` guards in `startK3sOnAllNodes()`, `installCilium()`, and `configureRegistryTls()`, and remove the `bucketName.isBlank()` guard in `configureRegistryTls()`. Delete `Event.Provision.NoControlNodes` if it has no remaining emitter.
- [x] 4.6 Write a test: `up` with a control node, app nodes, and **zero db nodes** provisions successfully and emits no warning. Demote `log.warn { "No db nodes found..." }` to `log.debug`.

## 5. `up` fails fast

- [x] 5.1 Add a private helper in `Up` that runs a nested command via `commandExecutor.execute { }` and throws when the exit code is non-zero, naming the command in the message. Write a test covering both the zero and non-zero paths.
- [x] 5.2 Write a failing test for each nested-command site — `WriteConfig`, `SetupInstance`, `ConfigureAxonOps`, `GrafanaUpdateConfig` — asserting a non-zero exit code aborts `up` with a non-zero exit code. Route all four through the helper from 5.1.
- [x] 5.3 Write a failing test asserting `writeConfigurationFiles()` failure aborts `up`; replace `onFailure { log.error }` with propagation.
- [x] 5.4 Write a failing test asserting a `k3sClusterService.setupCluster()` result with `isSuccessful == false` aborts `up`; log each operation error, then throw.
- [x] 5.5 Write a failing test asserting `ensureLocalStorageClass()` and `ensureLocalStorageWfcClass()` failures abort `up`; replace `getOrElse { log.warn }` with `.getOrThrow()`, following `installCilium()`'s precedent.
- [x] 5.6 Write a failing test asserting any `labelNode()` failure — control, db, or app — aborts `up`; replace all three `getOrElse { log.warn }` sites with `.getOrThrow()`.
- [x] 5.7 Write a failing test asserting `Event.Provision.NodeLabelingComplete` is **not** emitted when labeling fails. Emit it only after labeling succeeds.
- [x] 5.8 Write a failing test asserting `reapplyS3Policy()` failure aborts `up`; replace `runCatching { }.onFailure { log.warn }` with propagation.
- [x] 5.9 Write a failing test asserting `TailscaleStart()` failure aborts `up`. Remove the `catch → warn` path; preserve the manual-setup instruction in the thrown error message rather than dropping it.
- [x] 5.10 Write a test asserting `--no-setup` still exits zero when all remaining steps succeed — an explicit opt-out is not a failure.

## 6. Control node SSH readiness

- [x] 6.1 Write a failing test asserting the SSH readiness wait covers `ServerType.Control` hosts, not only `ServerType.Cassandra`. (Design D10.)
- [x] 6.2 Separate the two concerns in `waitForSshAndDownloadVersions()`: the readiness check covers control and db hosts; the `/etc/cassandra_versions.yaml` download remains scoped to db hosts. Rename the method to reflect what it now does.
- [x] 6.3 Write a test asserting `up` exits non-zero when the control node never accepts SSH within the retry window.

## 7. Verification and documentation

- [ ] 7.1 Run `./gradlew ktlintFormat`, then `./gradlew test`, then `./gradlew detekt` (JDK 21 — detekt 1.23.8 cannot run under JDK 25). All must pass with no new detekt findings.
- [ ] 7.2 End-to-end check against a real cluster: provision with `init --up` and confirm the observability stack, both StorageClasses, and all node labels are present. Then simulate a dead tunnel and confirm `up` exits non-zero rather than reporting success.
- [x] 7.3 Update user documentation in `docs/` for the changed `up` failure semantics and for the `known_hosts` behavior change — `ssh` no longer writes host keys to the developer's `~/.ssh/known_hosts`.
- [x] 7.4 Update `commands/CLAUDE.md` to document `@RequiresProxy` alongside the other command annotations, and note that `RemoteOperationsService`-driven tooling (helm, kubectl, cilium on the control node) does not need it.
- [x] 7.5 Confirm no scope creep: `grafana update-config` dashboard handling, MINA SSHD's `AcceptAllServerKeyVerifier`, and `Down.cleanupSocks5Proxy()`'s cwd-relative state path (issue #738) remain untouched.
