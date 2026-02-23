# Implementation Plan: Event Bus Output System

**Branch**: `002-event-bus` | **Date**: 2026-02-23 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-event-bus/spec.md`

## Summary

Replace the current `OutputHandler`-based output system with a structured event bus using sealed class event hierarchy. All 200+ `handleMessage()` / `handleError()` call sites are migrated to emit domain-specific typed events. Events are dispatched to pluggable listeners: console (backward-compatible text), MCP (structured metadata), and Redis pub/sub (configurable via environment variable). Event types are derived from class names, enabling consumers to filter and match on type rather than string values.

## Technical Context

**Language/Version**: Kotlin (JVM 21, Temurin)
**Primary Dependencies**: PicoCLI, Koin (DI), kotlinx.serialization, Lettuce (new — Redis client), Ktor (MCP server)
**Storage**: N/A (Redis is pub/sub only, not storage)
**Testing**: JUnit 5 + AssertJ + BaseKoinTest + TestContainers (Redis)
**Target Platform**: Linux CLI tool + MCP server
**Project Type**: CLI tool
**Performance Goals**: Events dispatched within 100ms of emission (SC-002); Redis publishing must not block CLI
**Constraints**: Zero regression in console output; Redis failures must not crash the tool
**Scale/Scope**: ~200 call sites across ~45 files; ~120 domain-specific event types across ~15 sealed sub-interfaces

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. CLI-First UX | PASS | Console output preserved via `toDisplayString()` on each event. No logging frameworks for user output. |
| II. Layered Architecture | PASS | EventBus is a service-layer component. Commands emit events, services dispatch them. No layer violations. |
| III. Reasonable TDD | PASS | Tests verify: event serialization round-trips, console rendering matches current output, Redis publishing (TestContainers), fan-out to multiple listeners. No mock-echo tests. |
| IV. Never Disable, Always Fix | PASS | Redis graceful degradation logs warnings but does not disable functionality. Redis is an optional *destination*, not a core feature being disabled. |
| V. Type Safety Over Strings | PASS | Sealed class hierarchy for events. kotlinx.serialization for wire format. No string-based event types. |

**Quality Gates**: ktlint, detekt, tests in CI + local + devcontainer.
**Tech Stack**: Koin for DI, kotlinx.serialization (not Jackson), AssertJ assertions, BaseKoinTest, RetryUtil for Redis reconnection, constants in Constants.kt.

**Post-Design Re-check**: All principles still satisfied. The sealed class hierarchy with `toDisplayString()` is the most type-safe approach possible while maintaining backward compatibility.

## Project Structure

### Documentation (this feature)

```text
specs/002-event-bus/
  plan.md              # This file
  research.md          # Phase 0 output
  data-model.md        # Phase 1 output — full event taxonomy
  quickstart.md        # Phase 1 output
  contracts/           # Phase 1 output — wire format contract
  tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (new and modified files)

**New files**:
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/Event.kt` — sealed interface hierarchy with all domain event types
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/EventBus.kt` — central dispatcher (reads EventContext, creates EventEnvelope)
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/EventEnvelope.kt` — envelope wrapping event + timestamp + commandName
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/EventContext.kt` — stack-based ThreadLocal for command name tracking
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/EventListener.kt` — listener interface (receives EventEnvelope)
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/ConsoleEventListener.kt` — renders events to stdout/stderr
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/RedisEventListener.kt` — publishes events to Redis pub/sub
- `src/main/kotlin/com/rustyrazorblade/easydblab/events/McpEventListener.kt` — streams structured events to MCP channel
- `src/main/kotlin/com/rustyrazorblade/easydblab/di/EventBusModule.kt` — Koin module for EventBus and listeners
- `src/test/kotlin/com/rustyrazorblade/easydblab/events/EventBusTest.kt` — unit tests
- `src/test/kotlin/com/rustyrazorblade/easydblab/events/ConsoleEventListenerTest.kt` — rendering tests
- `src/test/kotlin/com/rustyrazorblade/easydblab/events/RedisEventListenerTest.kt` — TestContainers integration
- `src/test/kotlin/com/rustyrazorblade/easydblab/events/EventSerializationTest.kt` — serialization round-trip tests

**Modified files** (major):
- `build.gradle.kts` — add Lettuce dependency
- `gradle/libs.versions.toml` — add Lettuce version
- `src/main/kotlin/com/rustyrazorblade/easydblab/Constants.kt` — add EventBus constants
- `src/main/kotlin/com/rustyrazorblade/easydblab/commands/PicoBaseCommand.kt` — inject EventBus instead of OutputHandler; push/pop EventContext in run()
- `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/McpServer.kt` — use McpEventListener
- `src/main/kotlin/com/rustyrazorblade/easydblab/mcp/ChannelMessageBuffer.kt` — accept Event objects
- `src/main/kotlin/com/rustyrazorblade/easydblab/di/OutputModule.kt` — rewire to EventBus

**Modified files** (migration — ~45 files):
- All command classes in `commands/` that call `outputHandler.handleMessage()` / `handleError()`
- All service classes that call `outputHandler.handleMessage()` / `handleError()`
- Key services: K3sClusterService, CassandraService, K8sService, EMRService, OpenSearchService, S3ObjectStore, EC2VpcService, AwsInfrastructureService, ClusterProvisioningService, etc.

**Structure Decision**: New `events/` package under the existing source root. Follows the project's flat package structure (events alongside commands, services, providers, configuration, mcp, etc.).

## Implementation Phases

### Phase 1: Core Event Infrastructure

Build the event sealed class hierarchy, EventContext (stack-based ThreadLocal), EventEnvelope, EventBus, EventListener interface, and ConsoleEventListener. Wire up Koin. Integrate EventContext push/pop into `PicoBaseCommand.run()`. At this point no call sites are migrated — the infrastructure compiles and is tested in isolation.

**Key decisions**:
- Events are pure domain data — no metadata fields (no timestamp, no commandName)
- Each concrete event has `toDisplayString()` for human-readable rendering
- `EventContext` uses stack-based `ThreadLocal<ArrayDeque<EventContext>>` for nested command support (e.g., Init → Up shows "up" as command name)
- `PicoBaseCommand.run()` pushes context before `execute()`, pops in `finally` block
- `EventBus.emit(event)` reads context from stack top, creates `EventEnvelope(event, Instant.now(), context?.commandName)`, dispatches envelope to listeners
- EventBus is thread-safe (synchronized listener list)
- ConsoleEventListener writes `envelope.event.toDisplayString()` to stdout, errors to stderr

**Tests**: EventBus dispatch, EventContext push/pop/nesting, ConsoleEventListener rendering, event construction.

### Phase 2: Event Serialization & AsyncAPI Spec

Add kotlinx.serialization polymorphic support to the event hierarchy. Test round-trip serialization for all event types. This establishes the wire format contract before any external integration.

Generate an AsyncAPI 3.0 specification document (`docs/reference/event-bus-asyncapi.yaml`) describing the Redis pub/sub channel, the `EventEnvelope` schema, and all event type schemas. This is a downloadable, machine-readable contract for third-party consumers and LLMs.

**Key decisions**:
- Polymorphic serializer with class discriminator field `"type"`
- All event fields serializable (no Throwable fields — convert to String)
- JSON wire format
- AsyncAPI spec lives in `docs/reference/` and is linked from the mdbook SUMMARY.md
- AsyncAPI spec is hand-authored YAML (not generated), kept in sync with the sealed class hierarchy

**Tests**: Serialization round-trip for representative events from each domain.

### Phase 3: Redis Listener

Add Lettuce dependency. Implement `RedisEventListener` that serializes events to JSON and publishes to Redis channel. Handle connection failures gracefully (log warning, continue operating). Configure via `EASY_DB_LAB_REDIS_URL` environment variable.

**Key decisions**:
- Lettuce async API for non-blocking publish
- Connection string parsed with `java.net.URI`
- Graceful degradation: startup failure logs warning, runtime failure logs and drops events
- Redis listener only registered when env var is set

**Tests**: TestContainers with Redis — publish and subscribe, connection failure, malformed URL.

### Phase 4: MCP Listener

Replace `FilteringChannelOutputHandler` + `ChannelMessageBuffer` with `McpEventListener`. The MCP status endpoint returns structured event objects with metadata instead of plain strings. Docker frame filtering logic moves into the listener.

**Key decisions**:
- McpEventListener sends Event objects through the channel
- ChannelMessageBuffer updated to buffer Event objects
- `get_server_status` returns JSON events with type, timestamp, message, and domain-specific fields
- Frame filtering (every Nth frame) preserved in McpEventListener

**Tests**: MCP event buffering, frame filtering, status endpoint returns structured data.

### Phase 5: Full Call Site Migration

Migrate all ~200 call sites from `outputHandler.handleMessage()` / `handleError()` to `eventBus.emit(Event.*)`. This is the bulk of the work. Migrate domain by domain:

1. Cassandra operations (~5 sites)
2. K3s cluster management (~12 sites)
3. Kubernetes operations (~30 sites)
4. AWS infrastructure / VPC (~35 sites)
5. EMR & Spark (~25 sites)
6. OpenSearch (~12 sites)
7. S3 / SQS (~16 sites)
8. Backup & Restore (~15 sites)
9. Grafana, Tailscale, Registry, Stress, AWS Setup (~20 sites)
10. Up command / provisioning (~30 sites)
11. Command execution & errors (~10 sites)

Each domain migration is a discrete unit that can be tested independently.

**Tests**: For each domain, verify `toDisplayString()` output matches the original `handleMessage()` string exactly.

### Phase 6: Cleanup & OutputHandler Removal

Remove or deprecate the old `OutputHandler` interface and implementations that are fully replaced:
- `ConsoleOutputHandler` → replaced by `ConsoleEventListener`
- `LoggerOutputHandler` → evaluate if still needed for non-event logging
- `CompositeOutputHandler` → replaced by EventBus fan-out
- `FilteringChannelOutputHandler` → replaced by `McpEventListener`
- `ChannelOutputHandler` → replaced by `McpEventListener`
- `BufferedOutputHandler` → replaced by a test-oriented `BufferedEventListener`

Update all test infrastructure to use EventBus instead of OutputHandler mocks.

### Phase 7: Documentation & CLAUDE.md Updates

- Add `docs/reference/event-bus-asyncapi.yaml` — AsyncAPI 3.0 spec (machine-readable, downloadable for LLM consumption)
- Add `docs/reference/event-bus.md` — human-readable event bus guide (Redis setup, event types overview, links to AsyncAPI spec)
- Update `docs/SUMMARY.md` to include new reference pages
- Update `CLAUDE.md` root with event bus architecture overview
- Update `commands/CLAUDE.md` to reference EventBus instead of OutputHandler
- Update `mcp/CLAUDE.md` with new MCP event flow
- Add `events/CLAUDE.md` for the new package

## Complexity Tracking

No constitution violations to justify.
