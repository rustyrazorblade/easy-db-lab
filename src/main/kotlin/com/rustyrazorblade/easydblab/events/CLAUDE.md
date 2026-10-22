# Events Package

This package implements the structured event bus system for all user-facing output.

## Architecture

```
Command/Service → eventBus.emit(Event.Domain.Type(...)) → EventBus → EventListeners
                                                                    ├── ConsoleEventListener (stdout/stderr)
                                                                    ├── McpEventListener (MCP status buffer)
                                                                    └── RedisEventListener (pub/sub, optional)
```

## Files

| File | Purpose |
|------|---------|
| `Event.kt` | Sealed interface hierarchy: one sealed sub-interface per domain (listed below), each holding that domain's concrete event types |
| `EventBus.kt` | Central dispatcher: `emit(event)` → wraps in `EventEnvelope` → dispatches to listeners |
| `EventContext.kt` | Stack-based `ThreadLocal` for tracking current command name |
| `EventEnvelope.kt` | Wraps `Event` + timestamp + commandName; serializable to JSON |
| `EventListener.kt` | Interface: `onEvent(envelope)`, `close()` |
| `ConsoleEventListener.kt` | Writes `event.toDisplayString()` to stdout (or stderr for errors) |
| `McpEventListener.kt` | Buffers envelopes for MCP `get_server_status` tool |
| `RedisEventListener.kt` | Publishes JSON envelopes to Redis pub/sub (conditional on env var) |

## Event Hierarchy

Events are organized by domain as sealed sub-interfaces of `Event`:

- `Event.Cassandra.*` — Database lifecycle (start, stop, restart)
- `Event.Profiling.*` — Runtime async-profiler control on Cassandra nodes (start/stop, attach and shipping health, fetch/flamegraph)
- `Event.K3s.*` — K3s cluster management
- `Event.Cilium.*` — Cilium CNI operations
- `Event.K8s.*` — Kubernetes operations
- `Event.Infra.*` — AWS infrastructure (VPC, subnet, security group)
- `Event.Ec2.*` — EC2 instance operations
- `Event.Emr.*` — EMR/Spark operations
- `Event.OpenSearch.*` — OpenSearch domain management
- `Event.S3.*` — S3 object store operations
- `Event.Grafana.*` — Grafana dashboard deployment
- `Event.Backup.*` — Backup/restore operations
- `Event.Registry.*` — Container registry operations
- `Event.Tailscale.*` — Tailscale VPN operations
- `Event.AwsSetup.*` — AWS resource setup (IAM roles, etc.)
- `Event.Stress.*` — Stress testing operations
- `Event.Service.*` — SystemD service management
- `Event.Provision.*` — Cluster provisioning orchestration
- `Event.Command.*` — Command execution and general command output
- `Event.Status.*` — Cluster status display sections
- `Event.Teardown.*` — Cluster teardown lifecycle
- `Event.Ami.*` — AMI pruning, listing, validation
- `Event.Docker.*` — Container lifecycle operations
- `Event.Mcp.*` — MCP tool execution
- `Event.Logs.*` — Log queries (`logs query`)
- `Event.Metrics.*` — The MCP live metrics stream (`Node`, `System`, `Cassandra`)
- `Event.Setup.*` — Profile setup and initialization
- `Event.Ssh.*` — SSH remote command execution
- `Event.Platform.*` — Platform substrate operations (StorageClass, PVs, info)
- `Event.Install.*` — Kit scaffold generation
- `Event.Kit.*` — Kit phase execution (script and step start/finish/failure, `Kit.ShellStepFailed` for a shell step that exited non-zero with its exit code and last output lines (carried as data only: the output was already streamed, so the console line does not repeat it), metrics registration, hooks, requirements, `Kit.HelmReleaseKept` when a `helm-uninstall` step keeps an operator another kit instance still uses, `Kit.CollisionDetected` when a collision-checked kit is started while already running, `Kit.StopIncomplete` when its pods outlive the wait after `stop` or `uninstall` (its `phase` field names which), and `Kit.StopUnverified` when the cluster cannot be queried during that wait) and `Kit.EndpointsAvailable`, the declared endpoints resolved to node private IPs after a successful start
- `Event.Cleanup.*` — Per-node kit cleanup progress and completion
- `Event.Server.*` — Server lifecycle (shutdown when the cluster's VPC no longer exists)
- `Event.Sql.*` — Shared SQL query results, used by every SQL kit command
- `Event.Compactor.*` — The account compactor service: `Started` (created, or scaled from 0 to 1 task), `AlreadyRunning` (left as it is), `Starting` (no task runs yet, but one is pending or none has stopped; left as it is), `NoTaskRunning` (an error: a task is asked for, none runs or is pending, and the latest stopped; left as it is), `Stopped` (desired count set to 0; emitted only after the update), `AccessDenied` (an error: ECS refused a call because the operator lacks the `EasyDBLabCompactor` policy; the call then fails), `NotCreated` (a stop found no service and changed nothing), `KeptRunning` (`down` found other clusters that use the account bucket)
- `Event.Proxy.*` — The SOCKS5 tunnel as `socks start` and `socks stop` manage it: `TunnelReady` (the port, and whether a running tunnel was reused; `up` emits it too for the tunnel it starts or reuses), `TunnelNotNeeded` (a Tailscale cluster), `NoRunningCluster` (an error: nothing to tunnel to), `TunnelStopped` (the PID), `TunnelStopFailed` (an error: the verified tunnel process did not end; it stays recorded), `NoTunnelRunning` (nothing recorded, or the recorded PID is gone or now another process's, and no orphaned ssh of it runs). `down` reports the same stop as `Teardown.Socks5ProxyStopped` or `Teardown.Socks5ProxyStopFailed`. `ProcessSocksProxyService` itself emits `TunnelReplaced` (a recorded tunnel failed the reuse check and a new one started: the old port, the new port, and a `ReplaceReason` of `gateway-changed`, `unreachable`, `process-gone`, `pid-reused` (a live process holds the recorded PID but is not the tunnel script) or `ssh-config-changed`; its text tells the user to change the browser's SOCKS port) and `SupersededTunnelStopFailed` (an error: the tunnel being replaced did not stop; its PID and port), for every caller of `ensureRunning`
- `Event.Report.*` — Reports: `Uploaded` (the zip's key, the unpacked prefix, whether the experiment's pointer moved to this upload, and the directory's files), from `report upload`
- `Event.LocalStack.*` — The local observability stack (`observability local`): `StoreUnreadable` (an error: the profile cannot read the account bucket; the profile, the AWS error code, and `ssoProfile`: the `aws sso login` hint for an SSO profile (`AwsLoginKind`), else "the profile's keys were refused"; nothing written), `StoreUnreachable` (an error: the check could not reach S3, a timeout, refused connection, DNS or TLS failure; the profile and the reason, no login hint; nothing written), `Written` (the directory, the number of owned files, the edited owned files it replaced, the home tenant, the tenants and the Grafana URL), `DatasourcesReloaded` (a re-run's running Grafana reloaded its datasources), `DashboardsInstalled` (the number installed, `from` null for the packaged dashboards, whether Grafana reloaded them), `GrafanaNotRunning` (an error: `local dashboards` found no Grafana on the recorded port and changed nothing), `ReloadRefused` (an error: a running Grafana refused a datasources or dashboards reload after the files were rewritten; the directory, the Grafana URL, the kind, the HTTP status and Grafana's answer, and `docker compose restart grafana` to load them)
- `Event.Export.*` — `observability export`: `StoreUnreadable` (an error: the credentials cannot read the account bucket; the profile, null for access keys, the AWS error code, and `ssoProfile`: the `aws sso login` hint only for an SSO profile, else "the profile's keys were refused"; nothing written), `StoreUnreachable` (an error: the check could not reach S3; the profile, null for access keys, and the reason, no login hint; nothing written), `TenantChosen` (an experiment export's tenant and its source: `option`, `workspace` or `default`), `SizeEstimated` (objects, bytes and the free space, before copying), and the errors `InsufficientSpace` (what the export needs, the data and the work directory's two copies of the planned Loki index, and the free space; nothing written), `EmptyTenant` (the tenant and the prefixes listed) and `Failed` (the path, the signal, the step and the reason, the saved `compose.log`s of a query export's stacks or of a tenant export's Loki index split, and a partial file that could not be removed with its error and whether the zip was already complete; otherwise no zip left), then `Completed` (the path, the tenant, the entries and bytes, and `excluded`: profiles, issue 1016), and last, once the work directory exists, on success or failure, `WorkDirectoryKept`. Query exports (`--cluster`, `--experiment`): `ClustersSelected` (each cluster and its tenant, for a `--cluster` export where the tenant came from, `workspace` or `query`, and the home tenant), the errors `InvalidCluster` (each `--cluster` value that is not a `<name>-<uuid>`, and the rule; nothing read), `StackNotRemoved` (a temporary compose project `down` could not remove: the project, its directory, the exit code, the output, and the manual `down` command), `AwsProfileRequired`, `ClusterNotFound` (the cluster and the tenants searched), `ClusterInSeveralTenants`, `ClusterTenantUnknown` and `ExperimentNotFound` (the experiment, its tenant and the query), `InvalidDiscoveredCluster` (the experiment, its tenant, each `cluster` label value Mimir named for it that is not a `<name>-<uuid>`, and the rule; none was queried, nothing written), `TracesFetched` (a cluster's trace fetch phase: the cluster, its tenant, the traces fetched so far and the total found; every `TRACE_PROGRESS_EVERY_TRACES` traces or `TRACE_PROGRESS_INTERVAL_SECONDS`, whichever comes first, and once at the end; none for a cluster with no traces), `MetricsStaged` (a tenant's metrics staging: the tenant, the blocks whose every object is staged and the blocks chosen, the bytes staged and their total; through the same `ProgressThrottle`, `ProgressPolicy.METRICS_STAGE`: every `STAGE_PROGRESS_EVERY_BYTES` (1 GiB) or `STAGE_PROGRESS_INTERVAL_SECONDS` (30s), whichever comes first, and once at the end; none for a tenant with no block to stage), then one `SignalExported(signal, items, bytes)` each for metrics (blocks), logs (lines) and traces, zero as zero, `QueryCompleted` (the path, the clusters, the tenants, entries, bytes, `excluded`), and last, on success or failure, `WorkDirectoryKept` (the work directory and its size; never removed)
- `Event.Ingress.*` — SSH ingress rules that `ingress allow` and `ingress revoke` change, one event per CIDR (canonical, e.g. `203.0.113.7/32`; `detected` is true for this machine's detected public IP): `RuleAdded`, `RuleAlreadyPresent`, `RuleRemoved`, and the errors `RuleNotFound` (revoke found no rule), `RuleChangeFailed` (AWS refused one CIDR; `action` `allow`/`revoke` and AWS's reason). One error per precondition that stops the command before any rule changes: `InvalidCidrs` (every bad value and its reason), `PublicIpDetectionFailed`, `NotInWorkspace`, `NoSecurityGroupInState`, `SecurityGroupNotFound`. `up` keeps reporting its own rules as `Infra.SecurityGroupRuleConfigured`, which `AwsInfrastructureService` emits once per rule it adds; the provider emits no rule event
- `Event.Message` / `Event.Error` — Generic types (kept for tests only, zero production usage)

## Adding New Events

1. Add a new `@Serializable data class` inside the appropriate sealed sub-interface in `Event.kt`
2. Implement `toDisplayString()` returning the exact user-facing output string
3. If it's an error event, override `isError(): Boolean = true`
4. Use `eventBus.emit(Event.Domain.NewType(...))` at the call site
5. Serialization is automatic via `@Serializable` sealed interfaces

Example:
```kotlin
// In Event.kt, inside the Cassandra sealed interface:
@Serializable
data class Decommissioning(val host: String) : Cassandra {
    override fun toDisplayString(): String = "Decommissioning $host..."
}

// In service code:
eventBus.emit(Event.Cassandra.Decommissioning(host.alias))
```

## Serialization

Uses kotlinx.serialization with `classDiscriminator = "type"`. All events serialize automatically because the sealed hierarchy is annotated with `@Serializable`. Wire format:

```json
{
  "timestamp": "2026-02-23T10:15:30.123Z",
  "commandName": "start",
  "event": {
    "type": "com.rustyrazorblade.easydblab.events.Event.Cassandra.Starting",
    "host": "cassandra0"
  }
}
```

## Redis Integration

Set `EASY_DB_LAB_REDIS_URL=redis://host:port/channel` to enable Redis pub/sub. Events are published as JSON envelopes. If Redis is unavailable at startup, the tool fails fast with a connection error.

## Migration Status

**Migration complete.** All production code uses domain-specific typed events — there are zero `Event.Message` or `Event.Error` usages in production code. `Event.Message` and `Event.Error` are retained in `Event.kt` only for test convenience.

### Design Rules
- **Events represent domain facts — things that happened.** Use events for lifecycle transitions: something started, stopped, failed, was created. Do NOT use events for status displays, tabular output, or informational strings. If you're showing "current state" rather than "something happened", use `println()` directly.
- **Data-only constructors**: Event fields carry structured data, NOT pre-formatted display strings. `toDisplayString()` constructs human-readable output internally.
- **`data object` for no-data events**: Events with no meaningful fields use `data object` (e.g., `data object CreatingPvs : ClickHouse`).
- **Error events**: Override `isError() = true` so `ConsoleEventListener` routes to stderr.
