# Quickstart: Event Bus Output System

## For CLI Users

Nothing changes. All commands produce the same console output as before.

## For MCP Consumers

The `get_server_status` tool now returns structured events instead of plain strings. Each event includes:
- `type` — the event class name (e.g., `"Event.Cassandra.Starting"`)
- `timestamp` — ISO-8601 timestamp
- `commandName` — which command emitted the event
- Domain-specific fields (host names, resource IDs, counts, etc.)

## For Redis Subscribers

### 1. Set the environment variable

```bash
export EASY_DB_LAB_REDIS_URL=redis://localhost:6379/easydblab-events
```

### 2. Start a Redis subscriber

```bash
redis-cli SUBSCRIBE easydblab-events
```

### 3. Run any easy-db-lab command

Events appear on the channel as JSON envelopes:

```json
{
  "timestamp": "2026-02-23T10:15:30.123Z",
  "commandName": "cassandra start",
  "event": {
    "type": "Cassandra.Starting",
    "host": "cassandra0"
  }
}
```

### Graceful Degradation

- If Redis is unavailable at startup, a warning is logged and the tool operates normally.
- If Redis disconnects during operation, events are dropped (not queued) and the tool continues.
- Malformed connection strings log an error at startup and disable Redis output.

## For Developers Adding New Events

1. Add a new data class in the appropriate sealed sub-interface in `events/Event.kt`
2. Constructor fields must be **structured data only** (strings, ints, lists) — never pre-formatted display text
3. Implement `toDisplayString()` to construct the human-readable console text from the data fields
4. For events with no meaningful data, use `data object` instead of `data class`
5. Emit the event via `eventBus.emit(Event.YourDomain.YourEvent(...))`
6. The event automatically flows to all registered listeners (console, MCP, Redis)
7. Serialization is handled by kotlinx.serialization — just add `@Serializable`
8. `timestamp` and `commandName` are injected automatically — never pass them manually

**Example:**
```kotlin
// GOOD — data fields only, display constructed internally
data class JobStarted(val jobName: String, val image: String, val promPort: Int) : Stress {
    override fun toDisplayString(): String = "Stress job started: $jobName (image: $image, port: $promPort)"
}

// BAD — passthrough message string
data class JobStarted(val message: String) : Stress {
    override fun toDisplayString(): String = message  // violates data-only rule
}
```
