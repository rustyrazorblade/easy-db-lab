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
| `Event.kt` | Sealed interface hierarchy with ~230+ concrete event types across 35 domain interfaces |
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
- `Event.Grafana.*` — Grafana dashboard deployment (`Grafana.KitDashboardInstallFailed` for a kit dashboard that is missing or that Grafana rejects, and `Grafana.KitDashboardsSkipped` for kit dashboards or a tenant listing that cannot be read; both fail the kit's `start`)
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
- `Event.Kit.*` — Kit phase execution (script and step start/finish/failure, `Kit.ShellStepFailed` for a shell step that exited non-zero with its exit code and last output lines (carried as data only: the output was already streamed, so the console line does not repeat it), metrics registration (`Kit.MetricsRegistrationFailed` fails the kit's `start`), `Kit.RegistrationFailed` when a workspace kit's commands cannot be registered (its `kit.yaml` does not parse or fails validation), hooks, requirements, `Kit.HelmReleaseKept` when a `helm-uninstall` step keeps an operator another kit instance still uses, `Kit.CollisionDetected` when a collision-checked kit is started while already running, `Kit.StopIncomplete` when its pods outlive the wait after `stop` or `uninstall` (its `phase` field names which), and `Kit.StopUnverified` when the cluster cannot be queried during that wait) and `Kit.EndpointsAvailable`, the declared endpoints resolved to node private IPs after a successful start
- `Event.Cleanup.*` — Per-node kit cleanup progress and completion
- `Event.Server.*` — Server lifecycle (shutdown when the cluster's VPC no longer exists)
- `Event.Sql.*` — Shared SQL query results, used by every SQL kit command
- `Event.Compactor.*` — The account compactor service: `Started` (created, or scaled from 0 to 1 task), `AlreadyRunning` (left as it is), `Starting` (no task runs yet, but one is pending or none has stopped; left as it is), `NoTaskRunning` (an error: a task is asked for, none runs or is pending, and the latest stopped; left as it is), `Stopped` (desired count set to 0; emitted only after the update), `AccessDenied` (an error: ECS refused a call because the operator lacks the `EasyDBLabCompactor` policy; the call then fails), `NotCreated` (a stop found no service and changed nothing), `KeptRunning` (`down` found other clusters that use the account bucket)
- `Event.Report.*` — Test documents: `DocumentsUploaded` (each stored document's name and S3 URI, and the rebuilt index's URI), from `report upload`
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
