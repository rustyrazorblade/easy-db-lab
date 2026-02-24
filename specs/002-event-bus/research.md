# Research: Event Bus Output System

**Branch**: `002-event-bus` | **Date**: 2026-02-23

## R-001: Event Model — Sealed Class Hierarchy

**Decision**: Use a sealed interface `Event` with domain-specific sealed sub-interfaces (e.g., `Event.Cassandra`, `Event.K3s`, `Event.Vpc`). Each concrete event is a data class with fields specific to that event. Event names are derived from class names for serialization via `kotlinx.serialization` polymorphic support.

**Rationale**: Sealed classes give compile-time exhaustive matching, type-safe event handling, and domain-specific fields per event. Consumers can match on type rather than string values. The class name serves as the event discriminator, eliminating the need for a separate `EventType` enum.

**Alternatives considered**:
- Data class with `EventType` enum — rejected by user feedback. Less type-safe; events would carry generic fields most of which are null for any given type.
- String-based event types — rejected per constitution principle V (Type Safety Over Strings).

## R-002: Event Bus Architecture

**Decision**: Create an `EventBus` that replaces `OutputHandler` as the Koin singleton. `EventBus` does NOT implement `OutputHandler` — it accepts typed `Event` objects directly. Commands and services emit specific events through the bus. Registered `EventListener` instances receive `EventEnvelope` objects (event + metadata) and decide how to handle them (console rendering, Redis publishing, MCP streaming).

**Rationale**: Since we're doing a full migration of all 200+ call sites, there's no need for the `OutputHandler` adapter pattern. Commands emit domain-specific events directly. Each listener handles rendering/transmission independently.

**Alternatives considered**:
- EventBus implements OutputHandler for backward compat — rejected since full migration means no backward compat bridge needed.
- Modifying OutputHandler interface to accept Events — rejected because it conflates output transport with event semantics.

## R-002a: Automatic Event Context via Stack-Based ThreadLocal

**Decision**: Use a stack-based `ThreadLocal<ArrayDeque<EventContext>>` to automatically inject `commandName` and `timestamp` into events. Events are pure domain data with no metadata fields. The `EventBus` reads the top of the context stack at dispatch time and wraps the event in an `EventEnvelope(event, timestamp, commandName)` before delivering to listeners.

Each command pushes its name onto the stack on entry and pops on exit. When commands call other commands (e.g., `Init` calls `Up`), the innermost command's name is on top — so events emitted during `Up`'s execution show `"up"` as the command, not `"init"`.

**Rationale**: Eliminates all repetition at call sites — callers never pass `commandName` or `timestamp`. The stack correctly handles nested command execution, which is a real pattern in this codebase (Init → Up). The `PicoBaseCommand.run()` method or `CommandExecutor` manages the push/pop lifecycle automatically, so individual commands don't need to think about it.

**Alternatives considered**:
- Passing commandName on every `emit()` call — rejected as repetitive across 200+ call sites.
- Simple set/clear ThreadLocal — rejected because nested command calls (Init → Up) would show the outer command name instead of the inner one.
- Coroutine context — rejected because commands run on threads, not necessarily coroutines.

## R-003: Redis Client Library

**Decision**: Use Lettuce for Redis pub/sub publishing.

**Rationale**: Lettuce is the standard async-capable Redis client for JVM. Supports non-blocking publish operations, aligning with FR-006 (graceful failure) and the spec requirement that Redis must not block CLI operations.

**Alternatives considered**:
- Jedis — simpler API but synchronous-only.
- Redisson — too heavy for simple pub/sub.

## R-004: Redis Connection Configuration

**Decision**: Environment variable `EASY_DB_LAB_REDIS_URL` with format `redis://host:port/channel`. Path segment = channel name. Parsed with `java.net.URI`.

**Rationale**: Single env var, standard URI format, consistent with project's `Environment` constants pattern.

**Alternatives considered**:
- Separate env vars for host/port/channel — more complex.
- Query parameter for channel — less conventional.

## R-005: Console Rendering Strategy

**Decision**: Each event sealed class provides a `toDisplayString(): String` method that returns the human-readable console output matching current behavior exactly. The `ConsoleEventListener` calls this method.

**Rationale**: Keeps rendering logic co-located with the event definition. Makes backward compatibility verifiable — each event's `toDisplayString()` must match the current `handleMessage()` string.

**Alternatives considered**:
- Separate renderer class with `when` exhaustive matching — valid but scatters rendering far from event definitions. Could miss new events.
- Template strings in a resource file — over-engineering for console output.

## R-006: Serialization for Wire Format

**Decision**: Use `kotlinx.serialization` polymorphic serialization. The sealed class hierarchy is serialized with a `type` discriminator field containing the class name (e.g., `"type": "Cassandra.Starting"`). Only domain-specific data fields are serialized — there is no `message` or `displayString` field in the wire format. The `toDisplayString()` method is Kotlin-only, used for console rendering; it is never serialized.

**Rationale**: Matches the project's mandatory serialization framework. Polymorphic serialization handles sealed hierarchies natively. Consumers deserialize to specific types and use the structured data fields directly. This gives Redis/MCP consumers queryable, machine-parseable data rather than opaque text strings.

**Alternatives considered**:
- Custom serializer — unnecessary, kotlinx handles sealed classes natively.
- Protobuf — not language-agnostic enough for the Redis pub/sub use case where external systems consume events.

## R-007: MCP Integration

**Decision**: Replace `FilteringChannelOutputHandler` + `ChannelMessageBuffer` with an `McpEventListener` that receives `Event` objects. Docker frame filtering moves into the listener. Structured events are buffered for the `get_server_status` polling endpoint, returning JSON event objects instead of plain strings.

**Rationale**: User Story 2 requires MCP clients to receive structured data with metadata. The existing channel-based pipeline is preserved but upgraded to carry `Event` objects.

## R-008: Migration Scale Assessment

**Decision**: Full migration of all 200+ call sites across 45+ files in a single feature branch.

**Domain areas by call count**:
- AWS Infrastructure (VPC, subnet, SG, IGW, NAT): ~35 sites
- Kubernetes Operations: ~30 sites
- EMR & Spark: ~25 sites
- Backup & Restore: ~15 sites
- K3s Cluster Management: ~12 sites
- OpenSearch: ~12 sites
- S3 Object Store: ~12 sites
- Up command (cluster provisioning): ~30 sites
- Cassandra, Tailscale, SQS, Grafana, Stress, Registry, AWS Setup: ~30 sites combined

**Rationale**: User explicitly chose full migration. Domain-specific events lose value if half the system still uses generic strings.
