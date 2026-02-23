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
| `Event.kt` | Sealed interface hierarchy with ~120 concrete event types |
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
- `Event.K3s.*` — K3s cluster management
- `Event.K8s.*` — Kubernetes operations
- `Event.Infra.*` — AWS infrastructure (VPC, subnet, security group)
- `Event.Ec2.*` — EC2 instance operations
- `Event.Emr.*` — EMR/Spark operations
- `Event.OpenSearch.*` — OpenSearch domain management
- `Event.S3.*` — S3 object store operations
- `Event.Sqs.*` — SQS queue operations
- `Event.Grafana.*` — Grafana dashboard deployment
- `Event.Backup.*` — Backup/restore operations
- `Event.Registry.*` — Container registry operations
- `Event.Tailscale.*` — Tailscale VPN operations
- `Event.AwsSetup.*` — AWS resource setup (IAM roles, etc.)
- `Event.Stress.*` — Stress testing operations
- `Event.Service.*` — SystemD service management
- `Event.Provision.*` — Cluster provisioning orchestration
- `Event.Command.*` — Command execution errors
- `Event.Message` / `Event.Error` — Generic transitional types

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

Set `EASY_DB_LAB_REDIS_URL=redis://host:port/channel` to enable Redis pub/sub. Events are published as JSON envelopes. If Redis is unavailable, a warning is logged and the listener is skipped.

## Migration Status

The service layer is fully migrated to `eventBus.emit()`. Many command files still use `outputHandler.handleMessage()` — both patterns coexist during the transition. `PicoBaseCommand` provides both `outputHandler` and `eventBus`.
