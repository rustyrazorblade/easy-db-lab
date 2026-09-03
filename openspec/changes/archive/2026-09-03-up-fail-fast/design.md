## Context

`init --up` exits 0 having provisioned a cluster with no observability stack, no `local-storage` StorageClasses, and no node labels (GitHub issue #734).

The failure chain:

```
sshConfig sets StrictHostKeyChecking=no, no UserKnownHostsFile
        │  ssh -N -D reads ~/.ssh/known_hosts; MINA SSHD (everything else) reads nothing
        │  AWS recycles public IPs → changed host key → ssh exits immediately
        ▼
verifyProxyAcceptingConnections() logs "proceeding anyway", returns Unit
        │
        ▼
applySystemProperties(1080) publishes a port nothing is listening on
        │
        ▼
Fabric8 calls → Connection refused: localhost:1080
        │  labelNode, ensureLocalStorageClass, ensureLocalStorageWfcClass,
        │  GrafanaUpdateConfig — all proxied, all failed
        ▼
14 swallow sites in Up.kt turn each exception into a log line
        │
        ▼
printProvisioningSuccessMessage(); exit 0
```

Two asymmetries make this invisible. First, only the `ssh` CLI consults `~/.ssh/known_hosts` — `DefaultSSHConnectionProvider` uses Apache MINA SSHD, and nothing in the repo calls `setServerKeyVerifier`, so it runs the default `AcceptAllServerKeyVerifier`. `SetupInstance` and the K3s install therefore connect to the control node successfully moments before the tunnel to that same node dies. Second, `ProcessSocksProxyService.startNewProxy()` carries a comment stating *"The new port is republished by applySystemProperties() only after the proxy is verified"* directly above code that republishes it unconditionally. The contract was written and never implemented.

The blast radius is wider than the reported symptom: `labelNode`, `ensureLocalStorageClass`, and `ensureLocalStorageWfcClass` are Fabric8 calls through the same dead tunnel. A cluster provisioned this way is missing its `type=control` label, all db/app node ordinals (breaking StatefulSet pod-to-node pinning for every kit), and both StorageClasses (PVCs never bind).

## Goals / Non-Goals

**Goals:**

- Establish and enforce the invariant: **if `up` reports success, every provisioning step succeeded.**
- Fix the root cause — the `ssh` CLI must not consult the developer's `~/.ssh/known_hosts`.
- Make the proxy fail loudly rather than publishing a port nothing listens on.
- Make the proxy pre-flight opt-in, so no command starts a tunnel it does not need.
- Move the two cluster shape invariants (control node exists, S3 bucket configured) ahead of EC2 provisioning.
- Confirm control-node SSH readiness before anything depends on connecting to it.

**Non-Goals:**

- Fixing MINA SSHD's `AcceptAllServerKeyVerifier`. The library SSH path verifies no host keys at all. This change makes the CLI path *match* that behavior rather than diverge from it; making both verify properly is a separate, larger change.
- `grafana update-config` dropping kit-installed dashboards (noted in #734).
- `Down.cleanupSocks5Proxy()` resolving its state file against cwd instead of `context.workingDirectory` (issue #738).
- Rolling back partially-provisioned infrastructure on failure. `up` aborts; the user runs `down`. Clusters are ephemeral.
- Retrying a proxy that fails to establish. Fail fast, surface `socks5-proxy.log`.

## Decisions

### D1: `UserKnownHostsFile=/dev/null` rather than a per-workspace `known_hosts`

`ClusterConfigWriter.writeSshConfig()` emits `UserKnownHostsFile=/dev/null` alongside the existing `StrictHostKeyChecking=no`.

`StrictHostKeyChecking=no` only auto-adds *unknown* hosts; a host already present under a different key still hard-fails with `REMOTE HOST IDENTIFICATION HAS CHANGED`. Since `sshConfig` writes `Hostname <publicIp>` and AWS recycles public IPs, entries collide across cluster lifetimes.

*Alternative considered:* a per-workspace `known_hosts` file (`UserKnownHostsFile <workdir>/known_hosts`), which dies with the cluster and would catch a host key changing *within* a single cluster's lifetime. Rejected: it costs the same one line but only *appears* to add safety, because MINA SSHD accepts any key on the very same hosts. Real host-key verification means fixing both paths, which is out of scope. `/dev/null` is the honest expression of what the system actually does today, and it stops writing to the developer's file.

### D2: Fail-fast is expressed by letting exceptions propagate, not by collecting errors

Every swallow site in `Up.kt` becomes a propagating failure. `installCilium()`'s existing `.getOrThrow()` is the precedent.

- `Result`-returning calls: `.getOrThrow()`.
- Nested `commandExecutor.execute { }` calls: check the returned `Int`; non-zero throws.
- `k3sClusterService.setupCluster()`: `if (!result.isSuccessful)` throws after logging each operation error.

*Alternative considered:* accumulate failures and report them all at the end of `up`. Rejected: provisioning steps are sequential and dependent — K3s setup failing makes every subsequent K8s call fail too, so an accumulated report is mostly noise derived from the first failure. Aborting at the first failure names the actual cause. It also matches the owner's stated rule: *"If something fails, it should fail fast."*

`DefaultCommandExecutor.executeWithLifecycle()` already catches, logs the cause chain, emits `Event.Command.ExecutionError`, and returns a non-zero exit code. `Up` calling nested commands must therefore inspect the return value rather than rely on an exception escaping — the executor has already absorbed it.

### D3: A new `Up`-local helper for nested command exit codes

Four call sites (`WriteConfig`, `SetupInstance`, `ConfigureAxonOps`, `GrafanaUpdateConfig`) discard `commandExecutor.execute { }`'s `Int`. Rather than repeat the check, `Up` gains a private helper that runs a nested command and throws when the exit code is non-zero, naming the command in the error.

This keeps the fix in one place and makes a future discarded exit code visibly anomalous.

### D4: Cluster shape invariants validate before provisioning, and the downstream guards are deleted

`up` today discovers a missing control node at `startK3sOnAllNodes()`, emits `NoControlNodes`, returns, and skips K3s, Cilium, node labeling, both StorageClasses, and observability — then exits 0. `configureRegistryTls()` and `installCilium()` carry their own `controlHosts.isEmpty()` guards.

A control node and an S3 bucket are required. Validation moves ahead of EC2 provisioning, so an unsatisfiable configuration fails before instances are launched. The three downstream `isEmpty()` guards are then unreachable and are **removed** rather than left as dead defensive code — a guard that silently does nothing is how this class of bug survives.

*Alternative considered:* keep the guards and make them throw. Rejected: three places asserting the same invariant is three places for it to drift, and the failure would still arrive after paying for EC2 instances.

### D5: Zero db nodes is legal; the warning is the bug

`up` with zero db nodes is a supported configuration (Trino + OpenSearch). The skip at db-node labeling stays; `log.warn { "No db nodes found..." }` drops to `log.debug`.

Once warnings in `up` reliably mean "something is off," a warning on a normal configuration trains the user to ignore them. This is the same disease as the swallow, in the logging layer.

### D6: `NodeLabelingComplete` is emitted only on success

`Event.Provision.NodeLabelingComplete` is currently emitted unconditionally after `labelNode(...).getOrElse { log.warn }`. It is the line users read as the last-good checkpoint before the failure in #734 — and it asserts an outcome it never checked.

Once labeling propagates failure (D2), reaching the emit implies success. This is a consequence of D2, not a separate mechanism, but it is called out because the misleading event is what made the issue hard to diagnose.

### D7: `@RequiresProxy` opt-in over `@NoProxy` opt-out

`DefaultCommandExecutor.ensureProxyRunning()` fires for **every** command, gated only on cluster state, and catches → `log.warn "proceeding without proxy"`. Its KDoc justifies the catch on the grounds that *"the proxy may not be reachable during teardown"* — but `Down.kt:93-102` unpublishes the proxy port and kills the ssh process as its **first two actions**. The pre-flight starts a tunnel that `Down` exists to tear down. The swallow existed to make that contradiction survivable.

A new `@RequiresProxy` class annotation, checked in `checkRequirements()` alongside `@RequireDocker` / `@RequireSSHKey` / `@RequireProfileSetup`, declares the dependency. `ensureProxyRunning()` runs only for annotated commands and stops catching — safe precisely because the annotation *is* the assertion that the proxy is required.

*Alternative considered:* `@NoProxy` opt-out on `Down`. Rejected on failure-mode asymmetry: a missed `@NoProxy` makes a working command start failing (a regression), while a missed `@RequiresProxy` makes a command fail exactly the way it does today (no worse than status quo). Opt-in also matches the three existing `@Require*` annotations.

**Commands requiring the annotation** — those whose execution path reaches Fabric8 (`K8sClientProvider`) or `ProxiedHttpClientFactory`:

`Up`, `Status`, `GrafanaInstall`, `GrafanaUpdateConfig`, `PlatformCreatePvs`, `KitInstallCommand`, `KitRunnerCommand`, `KitStatusCommand`, `LogsBackup`, `MetricsBackup`, `cassandra/Start`, `cassandra/Stop`, `cassandra/Restart`, and the `cassandra/stress/*` family (`StressStart`, `StressStop`, `StressStatus`, `StressList`, `StressLogs`, `StressInfo`, `StressFields`).

`Down` is **not** annotated. The annotation must be applied by verified execution path, not by guessing from the command name — the task list treats this as an audit with a mechanical criterion, since a missed annotation degrades to today's behavior rather than a new failure.

Note that commands driving remote tooling through `RemoteOperationsService` (helm, kubectl, cilium run *on* the control node over SSH) do **not** need the proxy — only local Fabric8 and local HTTP to private cluster IPs do.

Implementation widened the set from the ~20 identified by grep to **25**: `LogsQuery`, `LogsImport`, `MetricsImport`, `SparkLogs`, and `Server` also qualify. `Server` reaches Fabric8 only transitively (`Server` → `McpServer` → `StatusCache` → `K8sService`).

**No test enforces that the annotation is applied.** A guard test was written and deleted. Every form it can take is a list masquerading as a check: reflecting over injected properties requires a hand-maintained set of "proxy-reaching" service types, and bytecode reachability over-approximates, requiring a hand-maintained suppression list for its false positives. Both pass indefinitely while covering less and less as the codebase moves. The annotation is applied by reading the execution path, and D7's opt-in choice is what makes that safe: a missed annotation reproduces today's behaviour (a confusing Fabric8 error) rather than breaking a working command. That benign failure mode is the reason opt-in was chosen, so buying insurance against it with a decaying test is a bad trade.

### D8: The proxy verifies before it publishes, and verifies before it reuses

`ProcessSocksProxyService`:

- `verifyProxyAcceptingConnections()` throws when the port never opens, so `applySystemProperties(port)` is unreachable on failure. The existing `System.clearProperty()` at the top of `startNewProxy()` already ensures no stale port survives, so clients fall back to no-proxy rather than a dead one. This makes the code honor the comment already above it.
- The thrown error names the proxy as the failing component and points at `socks5-proxy.log`, which holds `ssh -v` stderr — the transcript that would have identified the host-key failure immediately.
- `isValidProxy()` currently checks PID-alive, `controlIP`, and `sshConfig` path, but never the port. `isRunning()` *does* check the port. A zombie `ssh` — process alive, tunnel dead — is therefore reused and its dead port republished, which is a second door to the same silent failure. `isValidProxy()` gains an `isPortAccepting()` check.
- `startNewProxy()`'s `ProcessBuilder` dials the string literal `"control0"` while holding a `gatewayHost` it uses only for logging. It uses `gatewayHost.alias`.

Two consequences surfaced during implementation, both inside this decision:

- **The in-memory fast path is a third door.** `ensureRunning()` short-circuits on `if (current != null && isAlive(pid))` *before* `isValidProxy()` is consulted. Fixing only `isValidProxy()` would still let a long-lived `Server` or `Repl` session reuse a zombie tunnel. The fast path must check `isPortAccepting()` too. PID-alive is never sufficient evidence that a tunnel is alive, at any of the three sites that ask.
- **Throwing before the state-file write leaks the ssh process.** The state file records the PID that `Down` uses for cleanup. Once `verifyProxyAcceptingConnections()` throws, that write is skipped, so a spawned-but-unverified `ssh` would survive untracked. `startNewProxy()` must `destroyForcibly()` the process before rethrowing. Failing fast must not fail dirty.

### D9: `Status` degrades rather than failing — the one permitted exception

`Status` reaches Fabric8 and therefore carries `@RequiresProxy`. Under D7 + D8 a proxy failure would abort it. But `status` is the command a user reaches for *when the cluster is misbehaving*, so failing fast removes the diagnostic exactly when it is needed.

`Status` catches proxy establishment failure, reports every section it can still observe, and marks only the genuinely unreachable ones unavailable with the reason. It exits non-zero, so a partial report is never mistaken by a script for a healthy cluster.

Unavailability follows the **transport**, not a hand-picked section list. `Status`'s sections split cleanly:

```
cluster / nodes / networking   cluster state + AWS SDK            no tunnel
database version               remoteOperationsService (SSH)      no tunnel
kubernetes / workloads         Fabric8 via K8sClientProvider      TUNNEL
```

SSH never traverses the SOCKS tunnel — the tunnel exists to reach the private *Kubernetes API*, not the nodes' sshd. So only the Kubernetes and workload sections may be marked unavailable; database versions still render over SSH.

This also dictates the structure: there is **no separate degraded rendering routine**. A second path listing which sections to render would drift — a new SSH-backed section added to the healthy report would silently go missing from the degraded one, and nothing would catch it. Instead the single rendering path guards only the Kubernetes-API-backed sections, emitting `Event.Status.SectionUnavailable` in their place when the proxy failed.

This is a swallow. It is narrow, deliberate, specified, and confined to a **read-only** command that mutates nothing — reporting partial state cannot leave the cluster in an unexpected condition. It is recorded here rather than left implicit precisely because unrecorded exceptions of exactly this shape are how the fourteen swallow sites accumulated. No state-mutating command may take it.

*Alternative considered:* fail fast like every other `@RequiresProxy` command, letting the proxy error message (which names `socks5-proxy.log`) serve as the diagnosis. Rejected: it is a coherent position, but it costs the user all visibility into EC2 and VPC state in the situation where they most need it, and the proxy error alone cannot distinguish "tunnel broken" from "instances gone."

### D10: Control node joins the SSH readiness wait; the version download does not

`waitForSshAndDownloadVersions()` retries SSH against `ServerType.Cassandra` hosts only, and within the same block downloads `/etc/cassandra_versions.yaml`. The control node — which the tunnel dials — is never checked.

The two concerns separate: the control node needs a **readiness check**, not the version download, which is meaningful only for db hosts. The readiness wait covers control and db hosts; the download stays scoped to db hosts.

This is an independent real bug. It is **not** the cause of #734 — control's `sshd` was demonstrably accepting connections, since `SetupInstance` (which SSHes to control as its final step) and the K3s install both succeeded immediately beforehand. It is fixed here because it is a genuine gap on the same path, not because it explains the incident.

## Risks / Trade-offs

**`Status`'s permitted swallow could be cited as precedent for reintroducing others** → D9 is a real exception to the invariant, and exceptions grow. Mitigation: the spec states the exception's boundary normatively — read-only commands only, no state-mutating command may take it — and `Status` still exits non-zero, so it cannot be mistaken for success. The exception is specified rather than implied, which is the difference between D9 and the fourteen sites this change removes.

**Previously "successful" provisions will start failing** → Intended. Any `up` that now fails was already producing a degraded cluster; the change makes existing breakage visible rather than introducing it. Because clusters are ephemeral, there is no migration burden — the remedy is `down` and re-provision.

**`UserKnownHostsFile=/dev/null` removes all host-key verification from the `ssh` CLI path** → A MITM on the path to a public EC2 IP becomes undetectable. Mitigation: this is already true of every other SSH in the system via MINA's `AcceptAllServerKeyVerifier`, so the change closes a divergence rather than opening a hole. Fixing verification properly is scoped as a separate issue.

**A missed `@RequiresProxy` annotation leaves a command without a tunnel** → Degrades to today's behavior: a confusing Fabric8 error rather than a clear one. Mitigation: the audit uses a mechanical criterion (does the execution path reach `K8sClientProvider` or `ProxiedHttpClientFactory`), and a test asserts that every command reaching those services carries the annotation.

**Aborting mid-`up` leaves EC2 instances running** → Accepted, and preferable to a silently degraded cluster. Instances are ephemeral and `down` reclaims them. Moving the shape invariants ahead of provisioning (D4) means the two most likely configuration failures now cost nothing.

**Tailscale startup failure now aborts `up`** → Previously it warned and printed manual-setup instructions. Under the invariant, a requested networking path that fails to come up is a failed provision. The manual-setup instruction is preserved in the error message rather than dropped.

## Open Questions

None outstanding. `Status`'s behavior on proxy failure is resolved in D9: it degrades rather than failing, as the single specified exception, confined to read-only commands.
