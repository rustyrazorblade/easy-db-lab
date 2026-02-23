# Tasks: Event Bus Output System

**Input**: Design documents from `/specs/002-event-bus/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Organization**: Tasks grouped by user story. Tests included per constitution mandate (Reasonable TDD — test non-trivial code with meaningful behavior).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Paths relative to `src/main/kotlin/com/rustyrazorblade/easydblab/` unless fully specified

---

## Phase 1: Setup

**Purpose**: Add dependencies and constants needed for all user stories

- [X] T001 Add Lettuce Redis client dependency to `gradle/libs.versions.toml` (version entry) and `build.gradle.kts` (implementation dependency)
- [X] T002 Add EventBus constants to `Constants.kt` — new `EventBus` object with `REDIS_URL_ENV_VAR = "EASY_DB_LAB_REDIS_URL"`, `DEFAULT_CHANNEL = "easydblab-events"`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core event infrastructure that ALL user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [X] T003 Create `EventContext` with stack-based `ThreadLocal<ArrayDeque>` in `events/EventContext.kt` — `push(commandName)`, `pop()`, `current(): String?` static methods
- [X] T004 [P] Create `Event` sealed interface in `events/Event.kt` — root interface with `toDisplayString(): String`, no metadata fields
- [X] T005 [P] Create `EventEnvelope` data class in `events/EventEnvelope.kt` — fields: `event: Event`, `timestamp: Instant`, `commandName: String?`; add `@Serializable` with polymorphic event support
- [X] T006 [P] Create `EventListener` interface in `events/EventListener.kt` — `onEvent(envelope: EventEnvelope)`, `close()`
- [X] T007 Create `EventBus` class in `events/EventBus.kt` — `emit(event: Event)` reads `EventContext.current()`, creates `EventEnvelope`, dispatches to synchronized list of `EventListener` instances; `addListener()`, `removeListener()`, `close()`
- [X] T008 Create `ConsoleEventListener` in `events/ConsoleEventListener.kt` — writes `envelope.event.toDisplayString()` to stdout; writes error events to stderr
- [X] T009 Create `EventBusModule.kt` in `di/EventBusModule.kt` — register `EventBus` as Koin singleton, register `ConsoleEventListener`, add module to `KoinModules.getAllModules()`
- [X] T010 Integrate EventContext into command execution — modify `PicoBaseCommand.kt` to `push(commandName)` before `execute()` and `pop()` in `finally`; add `protected val eventBus: EventBus by inject()` alongside existing `outputHandler`
- [X] T011 [P] Test EventContext push/pop/nesting in `src/test/kotlin/.../events/EventContextTest.kt` — verify: single push/pop, nested push shows inner name, pop restores outer, empty stack returns null, thread isolation
- [X] T012 [P] Test EventBus dispatch in `src/test/kotlin/.../events/EventBusTest.kt` — verify: dispatches to multiple listeners, envelope has timestamp and commandName from context, thread-safe add/remove, close propagates to listeners
- [X] T013 Add kotlinx.serialization polymorphic module skeleton for Event sealed hierarchy in `events/Event.kt` — configure `classDiscriminator = "type"`, register root sealed interface. Concrete event type registrations are added in T026 after event sub-interfaces are created in Phase 3
- [X] T014 Test serialization round-trip in `src/test/kotlin/.../events/EventSerializationTest.kt` — verify: EventEnvelope serializes to JSON with `type` discriminator, deserializes back, all domain-specific fields preserved

**Checkpoint**: EventBus infrastructure compiles, is tested, and is injectable via Koin. Both `outputHandler` and `eventBus` are available in commands during migration.

---

## Phase 3: User Story 1 — Backward Compatible CLI Output (Priority: P1)

**Goal**: Migrate all ~200 `outputHandler.handleMessage()`/`handleError()` call sites to emit domain-specific typed events. Console output remains identical to current behavior.

**Independent Test**: Run any existing command — terminal output must be unchanged. Run `ConsoleEventListenerTest` to verify rendering matches original strings.

### Domain Event Types + Migration

Each task creates the sealed sub-interface with all event data classes (per data-model.md) AND migrates the call sites in the corresponding service/command files. Event types include `toDisplayString()` returning the exact current message string.

- [X] T015 [P] [US1] Create `Event.Cassandra` sealed sub-interface + migrate `CassandraService.kt` (~5 call sites) — events: Starting, StartedWaitingReady, Restarting, WaitingReady
- [X] T016 [P] [US1] Create `Event.K3s` sealed sub-interface + migrate `K3sClusterService.kt` (~12 call sites) — events: ClusterStarting, ServerStarting, ServerStartFailed, NodeTokenFailed, KubeconfigFailed, KubeconfigWritten, KubeconfigInstruction, ClusterStarted, AgentConfiguring, AgentConfigFailed, AgentStartFailed
- [X] T017 [P] [US1] Create `Event.K8s` sealed sub-interface + migrate `K8sService.kt` (~30 call sites) — events per data-model.md Event.K8s table
- [X] T018 [P] [US1] Create `Event.Infra` + `Event.Ec2` sealed sub-interfaces + migrate `EC2VpcService.kt` and `AwsInfrastructureService.kt` (~35 call sites) — events per data-model.md Event.Infra and Event.Ec2 tables
- [X] T019 [P] [US1] Create `Event.Emr` sealed sub-interface + migrate `EMRService.kt`, `EMRSparkService.kt`, `EMRProvisioningService.kt` (~25 call sites) — events per data-model.md Event.Emr table
- [X] T020 [P] [US1] Create `Event.OpenSearch` sealed sub-interface + migrate `OpenSearchService.kt` (~12 call sites) — events per data-model.md Event.OpenSearch table
- [X] T021 [P] [US1] Create `Event.S3` + `Event.Sqs` sealed sub-interfaces + migrate `S3ObjectStore.kt`, `SQSService.kt` (~16 call sites) — events per data-model.md Event.S3 and Event.Sqs tables
- [X] T022 [P] [US1] Create `Event.Backup` sealed sub-interface + migrate `VictoriaBackupService.kt`, `ClusterBackupService.kt`, `BackupRestoreService.kt`, `ClusterConfigurationService.kt` (~15 call sites) — events per data-model.md Event.Backup table
- [X] T023 [P] [US1] Create `Event.Grafana` + `Event.Registry` + `Event.Tailscale` + `Event.Stress` + `Event.AwsSetup` + `Event.Service` sealed sub-interfaces + migrate `GrafanaDashboardService.kt`, `EC2RegistryService.kt`, `TailscaleService.kt`, `StressJobService.kt`, `AWSResourceSetupService.kt`, `SystemDServiceManager.kt` (~20 call sites)
- [X] T024 [P] [US1] Create `Event.Provision` + `Event.Command` sealed sub-interfaces + migrate `Up.kt`, `CommandExecutor.kt`, `ClusterProvisioningService.kt`, `EC2InstanceService.kt` (~40 call sites) — events per data-model.md Event.Provision and Event.Command tables
- [X] T025 [US1] Remove `outputHandler` injection from `PicoBaseCommand.kt` — all call sites now use `eventBus.emit()`; remove `OutputHandler` injection from all migrated services; update `McpToolRegistry.kt` OutputHandler usage. `Docker.kt` frame output stays outside EventBus (frames are raw byte streams, not structured events) — keep a minimal OutputHandler or direct stdout writer for frame output only
- [X] T026 [US1] Register all new sealed sub-interfaces in the kotlinx.serialization polymorphic module (T013) — ensure every concrete event type is registered
- [X] T027 [US1] Test ConsoleEventListener rendering in `src/test/kotlin/.../events/ConsoleEventListenerTest.kt` — for each domain, verify `toDisplayString()` matches the exact original `handleMessage()` string using representative events

**Checkpoint**: All commands produce identical console output. `outputHandler.handleMessage()` calls eliminated. `eventBus.emit()` used everywhere. SC-001 (100% backward compatibility) verifiable.

---

## Phase 4: User Story 2 — MCP Server Structured Messages (Priority: P1)

**Goal**: MCP server receives structured event data with type information, timestamps, and metadata instead of plain strings.

**Independent Test**: Start MCP server, trigger a command, poll `get_server_status` — response contains JSON events with `type`, `timestamp`, `commandName`, and domain-specific fields.

- [X] T028 [US2] Create `McpEventListener` in `events/McpEventListener.kt` — sends `EventEnvelope` objects through a `Channel<EventEnvelope>`; provides `resetFrameCount()`. Docker frame filtering stays in the existing frame output path (frames are excluded from EventBus)
- [X] T029 [US2] Update `ChannelMessageBuffer` in `mcp/ChannelMessageBuffer.kt` — change internal buffer from `String` to `EventEnvelope`; `getAndClearMessages()` returns serialized JSON event envelopes
- [X] T030 [US2] Update `McpServer` in `mcp/McpServer.kt` — register `McpEventListener` with `EventBus` instead of using `FilteringChannelOutputHandler` with `CompositeOutputHandler`; update `get_server_status` tool response to include structured event data
- [X] T031 [US2] Test MCP structured events in `src/test/kotlin/.../events/McpEventListenerTest.kt` — verify: events buffered with metadata, status endpoint returns typed JSON

**Checkpoint**: MCP clients receive structured events. SC-006 (all events plus metadata) verifiable.

---

## Phase 5: User Story 3 — External System Monitoring via Redis (Priority: P2)

**Goal**: Events published to Redis pub/sub channel when environment variable is set. Graceful degradation on Redis failures.

**Independent Test**: Set `EASY_DB_LAB_REDIS_URL`, start Redis subscriber, run a command — events appear on the channel as JSON envelopes.

- [X] T032 [US3] Create `RedisEventListener` in `events/RedisEventListener.kt` — uses Lettuce async API; parses `EASY_DB_LAB_REDIS_URL` with `java.net.URI` (host, port from authority; channel from path); serializes `EventEnvelope` to JSON via kotlinx.serialization; publishes non-blocking; logs warning on connection failure; `close()` shuts down Lettuce connection
- [X] T033 [US3] Add conditional Redis listener registration in `di/EventBusModule.kt` — check `System.getenv(Constants.EventBus.REDIS_URL_ENV_VAR)`; if set, parse URL, create `RedisEventListener`, add to `EventBus`; if malformed URL, log error and skip (don't crash)
- [X] T034 [US3] Test Redis publish/subscribe with TestContainers in `src/test/kotlin/.../events/RedisEventListenerTest.kt` — start Redis container, configure listener, emit events, verify subscriber receives JSON envelopes with correct structure
- [X] T035 [US3] Test graceful degradation in `src/test/kotlin/.../events/RedisEventListenerTest.kt` — verify: unavailable Redis at startup logs warning and continues; malformed URL logs error and skips; runtime disconnection drops events without blocking

**Checkpoint**: Redis integration works end-to-end. SC-002 (<100ms delivery), SC-003 (0% failure from Redis issues) verifiable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Cleanup, documentation, and quality assurance

- [ ] T036 [P] Remove old OutputHandler infrastructure — delete `ConsoleOutputHandler`, `CompositeOutputHandler` from `output/OutputHandler.kt`; retain `FilteringChannelOutputHandler` and `ChannelOutputHandler` for Docker frame output to MCP (frames excluded from EventBus); evaluate `LoggerOutputHandler` (keep if used for non-event internal logging) and `BufferedOutputHandler` (replace with `BufferedEventListener` for tests); remove `OutputModule.kt` if fully replaced by `EventBusModule.kt`
- [ ] T037 [P] Create `BufferedEventListener` for test infrastructure in `src/test/kotlin/.../events/BufferedEventListener.kt` — thread-safe in-memory event buffering for test assertions; update `BaseKoinTest` to register it; update tests that previously mocked `OutputHandler`
- [ ] T038 [P] Author AsyncAPI 3.0 specification in `docs/reference/event-bus-asyncapi.yaml` — define channel, EventEnvelope schema, all ~120 event type schemas with discriminator, per contracts/redis-wire-format.md skeleton
- [ ] T039 [P] Add event bus user guide in `docs/reference/event-bus.md` — Redis setup, event types overview, consumer guidelines, link to AsyncAPI spec; update `docs/SUMMARY.md` to include new pages
- [X] T040 [P] Update CLAUDE.md files — root `CLAUDE.md` (add event bus to architecture overview), `commands/CLAUDE.md` (EventBus instead of OutputHandler), `mcp/CLAUDE.md` (McpEventListener flow), new `events/CLAUDE.md` (event hierarchy patterns, adding new events)
- [ ] T041 [P] Verify constitution.md amendment (Principle I updated to `eventBus.emit()` pattern, version 1.1.0) — already applied during analysis, confirm it's accurate post-implementation
- [X] T042 Run `./gradlew ktlintFormat && ./gradlew ktlintCheck && ./gradlew detekt && ./gradlew :test` to verify no regressions across all quality gates

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Phase 2 — BLOCKS US2 (MCP needs events to exist)
- **US2 (Phase 4)**: Depends on Phase 3 (needs event types + migration complete)
- **US3 (Phase 5)**: Depends on Phase 2 only (Redis listener needs EventBus + serialization, not full migration)
- **Polish (Phase 6)**: Depends on Phases 3, 4, 5 all complete

### User Story Dependencies

- **US1 (P1)**: Foundational → US1. No other story dependencies. **This is the MVP.**
- **US2 (P1)**: Foundational → US1 → US2. Needs event types created in US1. MCP listener consumes the same events.
- **US3 (P2)**: Foundational → US3. Can start after Phase 2 (only needs EventBus + serialization). Can run **in parallel with US1** if desired.

### Within Each Phase

- T015–T024 (domain migrations) are all parallel — different files, no dependencies
- T028–T030 (MCP) are sequential — listener → buffer → server wiring
- T032–T033 (Redis) are sequential — listener → Koin registration

### Parallel Opportunities

**Phase 2** (after T003 completes):
```
Parallel: T004, T005, T006 (Event, EventEnvelope, EventListener — independent files)
Then: T007 (EventBus depends on all three)
Parallel: T008, T011, T012 (ConsoleEventListener, EventContextTest, EventBusTest)
```

**Phase 3** (all domain migrations):
```
Parallel: T015, T016, T017, T018, T019, T020, T021, T022, T023, T024
(all touch different service files and create different Event sub-interfaces)
Then: T025 (remove old outputHandler after all migrations)
Then: T026, T027 (serialization registration + rendering tests)
```

**Phase 5 can overlap Phase 3**:
```
US3 (T032–T035) only needs Phase 2 complete.
Can run in parallel with US1 domain migrations.
```

**Phase 6** (all polish tasks are parallel):
```
Parallel: T036, T037, T038, T039, T040
Then: T041 (final validation)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T002)
2. Complete Phase 2: Foundational (T003–T014)
3. Complete Phase 3: User Story 1 (T015–T027)
4. **STOP and VALIDATE**: Run all commands, verify console output unchanged
5. All existing functionality works with new event bus internally

### Incremental Delivery

1. Setup + Foundational → Event infrastructure ready
2. US1 (domain migrations) → Console backward compat verified (MVP!)
3. US2 (MCP listener) → AI assistants get structured data
4. US3 (Redis listener) → External monitoring enabled (can overlap with US1)
5. Polish → Cleanup, docs, AsyncAPI spec

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks in same phase
- Each domain migration task (T015–T024) creates event data classes AND migrates call sites in one task — keeps event definitions and usage co-located
- `toDisplayString()` on each event MUST return the exact string currently passed to `handleMessage()` — this is the backward compatibility contract
- During Phase 3, both `outputHandler` and `eventBus` coexist in `PicoBaseCommand` — T025 removes the old one after all migrations
- Redis (US3) is independent of migration (US1) — only needs EventBus + serialization from Phase 2
