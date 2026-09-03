# Proposal: `up` fails fast; the SOCKS tunnel never dies silently

## Why

`init --up` can exit 0 having provisioned a cluster with **no observability stack, no `local-storage` StorageClasses, and no node labels**. The user is told the cluster is ready. It is not.

Three defects compound (GitHub issue #734):

1. The generated `sshConfig` sets `StrictHostKeyChecking=no` but never sets `UserKnownHostsFile`, so the `ssh -N -D` SOCKS tunnel consults the developer's `~/.ssh/known_hosts`. Every *other* SSH in the codebase goes through Apache MINA SSHD, which uses the default `AcceptAllServerKeyVerifier` and reads no known-hosts file. `StrictHostKeyChecking=no` auto-adds *unknown* hosts; it does **not** bypass a *changed* key for a known host. AWS recycles public EC2 IPs and `sshConfig` keys `Hostname` by `publicIp`, so on a recycled IP `ssh` hard-fails with `REMOTE HOST IDENTIFICATION HAS CHANGED` and exits. The tunnel never opens.
2. `ProcessSocksProxyService` publishes the proxy port anyway — despite a comment promising it would not — so every Fabric8 call gets `Connection refused: localhost:1080`.
3. `Up.kt` swallows those failures at fourteen separate sites, each turning an exception into a log line, and then reports success.

The governing invariant this change establishes:

> **If `up` reports success, every provisioning step succeeded.** There is never a valid case for a step to fail during `up` and for provisioning to continue.

Silent partial success is the worst possible outcome for a provisioning tool: the cluster looks healthy, the missing StorageClasses and node labels surface hours later as unrelated-looking failures, and the absent observability stack reads as "observability is broken in the product" rather than "observability was never deployed."

## What Changes

**Host-key verification (root cause).** The generated `sshConfig` gains `UserKnownHostsFile=/dev/null` alongside the existing `StrictHostKeyChecking=no`. The `ssh` CLI stops consulting — and stops writing to — the developer's `~/.ssh/known_hosts`, matching the accept-all behavior the MINA SSHD path has always had. Clusters are ephemeral and get fresh host keys on every provision.

**`up` fails fast.** All fourteen swallow sites in `Up.kt` propagate failure. `installCilium()`'s existing `.getOrThrow()` is the precedent every other site follows. Specifically:

- Discarded `commandExecutor.execute { }` exit codes (`WriteConfig`, `SetupInstance`, `ConfigureAxonOps`, `GrafanaUpdateConfig`) are checked and abort on non-zero.
- `writeConfigurationFiles()`, `k3sClusterService.setupCluster()`, `ensureLocalStorageClass()`, `ensureLocalStorageWfcClass()`, `labelNode()` (×3), `reapplyS3Policy()`, and `TailscaleStart()` abort on failure instead of logging and continuing.
- `Event.Provision.NodeLabelingComplete` is emitted only when labeling actually succeeded. Today it is emitted unconditionally — the log line users read as the last-good checkpoint asserts an outcome it never checked.

**Two cluster invariants move before provisioning.** A cluster must have a control node, and an S3 bucket must be configured. Both are validated *before* EC2 instances are launched, rather than discovered mid-`up` and silently skipped. This removes the `controlHosts.isEmpty()` guards that currently cause `up` to skip all of K3s, Cilium, node labeling, StorageClasses, and observability while still exiting 0.

A cluster with **zero db nodes remains legal** (a Trino + OpenSearch cluster has nothing to pin), so db-node labeling is still skipped — but silently. A normal configuration must not emit a warning.

**The proxy fails fast.** `ProcessSocksProxyService.startNewProxy()` throws when the local port never opens, so the dead port is never published — honoring the contract its own comment already documents. `isValidProxy()` verifies the port is accepting before reusing a proxy, not merely that the PID is alive; a zombie `ssh` (process up, tunnel dead) is no longer reused. `startNewProxy()` dials `gatewayHost.alias` rather than the hardcoded string literal `"control0"`.

**The proxy pre-flight becomes opt-in.** A new `@RequiresProxy` annotation, checked in `CommandExecutor.checkRequirements()` alongside the existing `@RequireDocker` / `@RequireSSHKey` / `@RequireProfileSetup`, declares which commands reach the private K8s API. `ensureProxyRunning()` stops catching and lets failure propagate — safe precisely because the annotation *is* the assertion that the proxy is required. `Down` does not declare it: its first two actions are to unpublish the proxy port and kill the ssh process, so the pre-flight was starting a tunnel that `Down` exists to tear down. That contradiction is what forced the swallow.

**`status` degrades rather than failing.** `Status` reaches Fabric8 and so declares `@RequiresProxy`, but it is the command a user reaches for *when the cluster is misbehaving*. It catches proxy establishment failure, reports EC2 and VPC state, marks the Kubernetes, workload, and database-version sections unavailable with the reason, and exits non-zero. This is the single specified exception to the invariant, permitted because `Status` is read-only and mutates nothing. No state-mutating command may take it.

**The SSH readiness wait covers control nodes.** `waitForSshAndDownloadVersions()` waits only on `ServerType.Cassandra` hosts today; the control node — which the tunnel dials — is never checked. This is an independent real bug. It is *not* the cause of issue #734 (control's `sshd` was demonstrably accepting connections, since `SetupInstance` and the K3s install both SSH to it successfully moments earlier), but it is a genuine gap.

**BREAKING**: `up` now exits non-zero on failures it previously logged and ignored. Configurations that appeared to succeed while silently producing a degraded cluster will now fail loudly. This is the intent of the change.

## Capabilities

### New Capabilities

None. The behavior belongs to two existing capabilities.

### Modified Capabilities

- `cluster-lifecycle`: Modifies `REQ-CL-004: Cluster Status` so `status` degrades rather than failing when the cluster API is unreachable, reporting what it can observe and marking the rest unavailable. Adds the governing invariant — `up` MUST exit non-zero when any provisioning step fails, and MUST NOT report success on partial provisioning. Adds the two pre-provisioning cluster invariants (a control node exists; an S3 bucket is configured) and codifies zero db nodes as a legal configuration.
- `networking`: `REQ-NET-005: SOCKS Proxy` currently has five scenarios, all happy-path or reuse. None specify behavior when the proxy cannot be established. Adds failure scenarios: a proxy that cannot be verified fails the command and never publishes its port; a live PID with a dead port is not reused; commands that do not declare `@RequiresProxy` never start a proxy. Also specifies that generated SSH configuration must not depend on the developer's `~/.ssh/known_hosts`.

## Impact

**Code**

- `commands/Up.kt` — fourteen swallow sites; `NodeLabelingComplete` emission; control node added to the SSH readiness wait.
- `configuration/ClusterConfigWriter.kt` — `writeSshConfig()` emits `UserKnownHostsFile=/dev/null`.
- `proxy/ProcessSocksProxyService.kt` — `verifyProxyAcceptingConnections()` throws; `isValidProxy()` checks the port; `startNewProxy()` uses `gatewayHost.alias`.
- `services/CommandExecutor.kt` — `ensureProxyRunning()` gated on `@RequiresProxy`, stops catching.
- `annotations/RequiresProxy.kt` — new annotation.
- `commands/*` — audit of which commands reach the private K8s API (Fabric8 / kubectl) and must be annotated. `Down` is explicitly not annotated.
- `events/Event.kt` — `Provision` events for the new pre-provisioning validation failures.

**Behavior**

- `up` exits non-zero where it previously exited 0 with a degraded cluster.
- Commands not annotated `@RequiresProxy` no longer start a SOCKS tunnel. `Down` no longer starts a tunnel it immediately destroys.
- `ssh` no longer writes host keys into the developer's `~/.ssh/known_hosts`, and no longer fails when an ephemeral cluster lands on a recycled public IP.

**Docs**

- User-facing documentation for `up` failure semantics and the `known_hosts` behavior change.

**Explicitly out of scope**

- `grafana update-config` dropping kit-installed dashboards (noted in #734, deferred).
- MINA SSHD's `AcceptAllServerKeyVerifier` — the library SSH path verifies no host keys at all. Separate issue.
- `Down.cleanupSocks5Proxy()` resolving its state file against cwd rather than `context.workingDirectory` — filed as issue #738.
