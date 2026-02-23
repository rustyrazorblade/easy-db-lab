# Feature Specification: Event Bus Output System

**Feature Branch**: `002-event-bus`
**Created**: 2026-02-23
**Status**: Draft
**Input**: User description: "Upgrade output handler to structured event bus with Redis pubsub support"

## Clarifications

### Session 2026-02-23

- Q: What metadata should events include beyond type, message, and timestamp? → A: Command name, node targets, timestamp. No cluster name (endpoint is cluster-specific). Duration fully descoped — use OTel spans for timing.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Backward Compatible CLI Output (Priority: P1)

A CLI user runs easy-db-lab commands and sees the same human-readable output they always have. The upgrade to structured messages is transparent — existing workflows continue working without any visible changes to message format or behavior.

**Why this priority**: Existing users must not experience any regression. This is the foundation that enables all other stories.

**Independent Test**: Can be fully tested by running any existing command and verifying output matches current behavior exactly.

**Acceptance Scenarios**:

1. **Given** a user runs `easy-db-lab up`, **When** the command executes, **Then** terminal output appears identical to current behavior
2. **Given** a user runs any command that produces output, **When** messages are emitted, **Then** the human-readable text is displayed without visible changes
3. **Given** no external message destinations are configured, **When** commands run, **Then** only console output is produced (no errors about missing destinations)

---

### User Story 2 - MCP Server Structured Messages (Priority: P1)

The MCP server receives structured event data instead of plain strings. This enables AI assistants to receive rich, parseable information about cluster operations, including event types, timestamps, and contextual metadata.

**Why this priority**: MCP integration is a core feature. Structured messages unlock better AI assistant interactions and debugging capabilities.

**Independent Test**: Can be fully tested by starting the MCP server and verifying events contain structured data with type information.

**Acceptance Scenarios**:

1. **Given** MCP server is running, **When** an operation emits an event, **Then** the MCP client receives structured data with event type and message content
2. **Given** MCP server is connected, **When** a cluster operation runs, **Then** events include machine-parseable metadata (event type, timestamp, command name, node targets)
3. **Given** MCP client requests event stream, **When** events arrive, **Then** each event can be deserialized from its wire format

---

### User Story 3 - External System Monitoring via Redis (Priority: P2)

An operator wants external systems (dashboards, alerting tools, log aggregators) to monitor easy-db-lab operations in real-time. By setting an environment variable, events are published to a Redis pub/sub channel that any subscriber can consume.

**Why this priority**: Enables integration with external monitoring without modifying easy-db-lab code. Valuable for teams running automated pipelines.

**Independent Test**: Can be fully tested by setting the environment variable, running a command, and verifying events appear on the Redis channel.

**Acceptance Scenarios**:

1. **Given** Redis connection is configured via environment variable, **When** easy-db-lab starts, **Then** events are published to the specified Redis channel
2. **Given** a Redis subscriber is listening on the configured channel, **When** a command runs, **Then** the subscriber receives structured event messages
3. **Given** Redis is unavailable at startup, **When** the environment variable is set, **Then** the tool logs a warning and continues operating (graceful degradation)
4. **Given** Redis becomes unavailable during operation, **When** events are emitted, **Then** the tool continues operating and logs connection issues without blocking

---

### Edge Cases

- What happens when Redis connection string is malformed? System logs an error at startup and disables Redis output (does not crash).
- What happens when Redis channel receives very high message volume? Messages are published without blocking; if Redis backs up, events may be dropped rather than blocking CLI operations.
- What happens when an event contains data that cannot be serialized? This is a programming error — all Event fields must be serializable. The system fails fast with a clear error rather than silently degrading.
- What happens when multiple output destinations are configured simultaneously? All configured destinations receive events (fan-out pattern).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST emit structured events containing: event type, human-readable message, timestamp, and command name (when available). Individual event types MAY include domain-specific context such as node targets, resource identifiers, and elapsed time where applicable
- **FR-002**: System MUST preserve existing console output behavior when no external destinations are configured
- **FR-003**: System MUST support multiple simultaneous output destinations (console, MCP, Redis) via fan-out
- **FR-004**: System MUST allow Redis pub/sub destination to be configured via environment variable
- **FR-005**: System MUST use standard Redis connection string format for configuration (e.g., `redis://host:port/channel` or `redis://host:port?channel=name`)
- **FR-006**: System MUST handle Redis connection failures gracefully without crashing or blocking operations
- **FR-007**: System MUST provide wire-format serialization of events suitable for cross-system consumption
- **FR-008**: Events MUST include a categorization/type field to enable filtering by consumers
- **FR-009**: System MUST support adding new output destinations without modifying existing destination code

### Key Entities

- **Event**: A structured occurrence in the system with: type, message content, timestamp, and command name (when available). Individual event types carry domain-specific context (node targets, resource identifiers, elapsed time) where applicable. Has both human-readable and machine-serializable representations. Does not include cluster name (endpoint is cluster-specific).
- **Event Type**: Each event has a type derived from its sealed class name (e.g., `Cassandra.Starting`, `K3s.ClusterStarted`, `Backup.VictoriaMetricsComplete`). Types are organized by domain (`Cassandra.*`, `K3s.*`, `Emr.*`, etc.), enabling consumers to filter by domain prefix or match on specific event types.
- **Output Destination**: A subscriber to the event stream that receives and processes events (console, MCP connection, Redis channel).
- **Event Bus**: Central component that receives events and distributes them to all registered output destinations.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All existing CLI commands produce identical user-visible output after migration (100% backward compatibility)
- **SC-002**: External systems can receive events within 100ms of emission when Redis is healthy
- **SC-003**: System continues operating normally if Redis is unavailable (0% failure rate due to Redis issues)
- **SC-004**: Adding a new output destination requires no changes to event-emitting code
- **SC-005**: Events can be parsed by external systems without knowledge of internal implementation details
- **SC-006**: MCP clients receive all events that were previously sent as strings, plus additional metadata

## Assumptions

The following assumptions were made where the specification could have multiple interpretations:

- **Environment Variable Name**: Will follow project conventions (likely `EASY_DB_LAB_REDIS_URL` or similar)
- **Redis Channel Naming**: Channel name is part of the connection string configuration, not hardcoded
- **Event Serialization**: Events are serialized to JSON for wire transmission (standard, language-agnostic format)
- **Non-Blocking Behavior**: Redis publishing is non-blocking; CLI responsiveness takes priority over guaranteed delivery
- **Graceful Degradation**: Redis failures are logged but do not prevent normal operation
- **Event Types**: Initial set of event types will be derived from existing message patterns; extensible for future needs
