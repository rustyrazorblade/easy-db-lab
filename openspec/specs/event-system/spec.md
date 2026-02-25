# Event System

Structured event bus for all user-facing output, with pluggable output destinations.

## Requirements

### REQ-ES-001: Structured Domain Events

The system MUST emit structured events containing: event type, timestamp, and command name (when available). Event constructors MUST carry only structured domain data fields — never pre-formatted display strings. The `toDisplayString()` method constructs human-readable output internally from those data fields.

**Scenarios:**

- **GIVEN** a command that produces output, **WHEN** it emits an event, **THEN** the event carries domain-specific typed fields (e.g., host name, resource ID, count) rather than a pre-formatted string.
- **GIVEN** a concrete event type, **WHEN** it is rendered for console display, **THEN** `toDisplayString()` constructs the human-readable text from its structured data fields.
- **GIVEN** an event with no meaningful data fields, **WHEN** it is defined, **THEN** it uses a singleton object form rather than carrying empty or dummy fields.

### REQ-ES-002: Event Type Hierarchy

Events MUST be organized into domain-specific groups, enabling consumers to filter by domain prefix or match on specific event types. Types follow the pattern `<Domain>.<Action>` (e.g., `Cassandra.Starting`, `K3s.ClusterStarted`).

**Scenarios:**

- **GIVEN** a consumer interested only in Cassandra events, **WHEN** it filters by the `Cassandra.*` prefix, **THEN** only Cassandra-related events match.
- **GIVEN** a new feature area, **WHEN** events are needed, **THEN** a new domain interface is added to the sealed hierarchy.

### REQ-ES-003: Backward-Compatible Console Output

The system MUST preserve existing console output behavior. The migration to structured events is transparent to CLI users.

**Scenarios:**

- **GIVEN** a user runs any command, **WHEN** events are emitted, **THEN** terminal output appears identical to pre-migration behavior.
- **GIVEN** no external output destinations are configured, **WHEN** commands run, **THEN** only console output is produced with no errors about missing destinations.

### REQ-ES-004: Multiple Output Destinations

The system MUST support multiple simultaneous output destinations via fan-out. Adding a new destination requires no changes to event-emitting code.

**Scenarios:**

- **GIVEN** console, MCP, and Redis destinations are all configured, **WHEN** an event is emitted, **THEN** all three destinations receive the event.
- **GIVEN** only console is configured, **WHEN** an event is emitted, **THEN** only console output is produced.

### REQ-ES-005: Redis Pub/Sub Destination

The system MUST support publishing events to a Redis pub/sub channel, configured via environment variable.

**Scenarios:**

- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set (format: `redis://host:port/channel`), **WHEN** the tool starts, **THEN** events are published to the specified Redis channel.
- **GIVEN** a Redis subscriber listening on the channel, **WHEN** commands run, **THEN** the subscriber receives structured event envelopes as JSON.
- **GIVEN** Redis is unavailable at startup, **WHEN** the environment variable is set, **THEN** the tool logs a warning and continues operating without Redis.
- **GIVEN** Redis becomes unavailable during operation, **WHEN** events are emitted, **THEN** the tool continues operating and drops events to Redis without blocking.

### REQ-ES-006: Wire Format Serialization

Events MUST be serializable to a JSON wire format suitable for cross-system consumption. The wire format uses a `type` discriminator field for polymorphic deserialization.

**Scenarios:**

- **GIVEN** an event envelope, **WHEN** it is serialized, **THEN** the JSON contains `timestamp`, optional `commandName`, and a nested `event` object with a `type` discriminator and domain-specific fields.
- **GIVEN** serialized event JSON, **WHEN** a consumer deserializes it, **THEN** the event can be reconstructed with all typed fields intact.
- **GIVEN** an event, **WHEN** it is serialized, **THEN** no `message`, `text`, or `displayString` field appears in the wire format — only structured data fields.

### REQ-ES-007: Command Context Tracking

The system MUST track which command is currently executing and include this in event metadata. Nested command execution (e.g., `init` calling `up`) MUST reflect the innermost command.

**Scenarios:**

- **GIVEN** a user runs `init` which internally calls `up`, **WHEN** events are emitted during `up`, **THEN** the `commandName` metadata reads `up`.
- **GIVEN** `up` completes and control returns to `init`, **WHEN** events are emitted, **THEN** the `commandName` metadata reads `init`.

### REQ-ES-008: No Generic Event Types in Production

`Event.Message` and `Event.Error` generic types exist only for test convenience. All production output MUST use domain-specific typed events with structured data fields.

**Scenarios:**

- **GIVEN** a command needs to emit output, **WHEN** the developer writes the emit call, **THEN** they use a domain-specific event type, not `Event.Message` or `Event.Error`.

## Data Contract: Event Envelope

Every message on the Redis channel (and in MCP structured output) is a JSON `EventEnvelope`:

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

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| timestamp | string (ISO-8601) | yes | When the event was emitted |
| commandName | string | no | Innermost executing CLI command (null for system events) |
| event | object | yes | Domain event with `type` discriminator + domain-specific fields |

### Consumer Guidelines

1. Match on `event.type` prefix for domain filtering (e.g., all Cassandra events start with `Cassandra.`).
2. Ignore unknown fields — new event types and fields may be added.
3. Use the `event.type` field as the polymorphic discriminator for typed deserialization.
4. Events do NOT contain a `message` or `displayString` field. Consumers use domain-specific fields directly.

## Success Criteria

- All existing CLI commands produce identical user-visible output after migration (100% backward compatibility).
- External systems can receive events within 100ms of emission when Redis is healthy.
- System continues operating normally if Redis is unavailable (0% failure rate due to Redis issues).
- Adding a new output destination requires no changes to event-emitting code.
- Events can be parsed by external systems without knowledge of internal implementation details.
- Zero production usage of `Event.Message` or `Event.Error` generic types.
- No event type uses a passthrough `message: String` constructor field where `toDisplayString()` simply returns that field.

## Assumptions

- Redis channel name is part of the connection string configuration, not hardcoded.
- Events are serialized to JSON (standard, language-agnostic format).
- Redis publishing is non-blocking; CLI responsiveness takes priority over guaranteed delivery.
- Redis failures are logged but do not prevent normal operation.
