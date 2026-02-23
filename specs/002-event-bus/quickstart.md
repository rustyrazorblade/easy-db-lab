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
2. Implement `toDisplayString()` to return the human-readable console text
3. Emit the event via `eventBus.emit(Event.YourDomain.YourEvent(...))`
4. The event automatically flows to all registered listeners (console, MCP, Redis)
5. Serialization is handled by kotlinx.serialization — just add `@Serializable`
6. `timestamp` and `commandName` are injected automatically — never pass them manually
