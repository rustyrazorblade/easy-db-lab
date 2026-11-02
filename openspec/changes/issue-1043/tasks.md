## 1. Redis endpoint, shared publisher and best-effort listener

- [x] 1.1 Write `RedisEndpointTest` (BaseKoinTest): host, port and channel from `redis://host:port/channel`; the default channel from `Constants.EventBus.DEFAULT_CHANNEL` when the URL has no path.
- [x] 1.2 Extract `events/RedisEndpoint.parse(url)` from `RedisEventListener` until the test passes.
- [x] 1.3 Write `RedisPublisherIntegrationTest` in `src/integrationTest` (Redis TestContainer): the constructor pings; a stopped Redis gives `RedisUnavailableException` whose message names `EASY_DB_LAB_REDIS_URL` and the URL; `publish` reaches a subscriber; `ping` fails after the container stops.
- [x] 1.4 Add `events/RedisPublisher` and sealed `RedisConfiguration` (`NotConfigured`, `Configured(endpoint)`); wire them in `di/EventBusModule.kt` so `RedisPublisher` is resolved only when `Configured`.
- [x] 1.5 Write a Redis TestContainer test for `RedisEventListener`: after the container stops, `onEvent` logs and drops the event and does not throw; a command's other events still reach the console.
- [x] 1.6 Make `RedisEventListener` best effort on the shared `RedisPublisher`.
- [x] 1.7 Write a test that `Main` exits 1 with the `RedisUnavailableException` message (unwrapped from Koin's creation exception) when Redis does not answer at start.
- [x] 1.8 In `Main`, resolve the `EventBus` right after `startKoin` and handle `RedisUnavailableException` in the cause chain.

## 2. Events and constants

- [x] 2.1 Add `Event.Metrics.Sample`, `MetricFailing`, `MetricRecovered`, `InvalidNames` (with `InvalidMetricName(position, value, reason)`), `RedisNotConfigured`, `RedisLost`, `NoRunningCluster`, and `Event.Server.Ready(statusUrl, swaggerUrl)`, each with data fields only and `toDisplayString()`.
- [x] 2.2 Write serialization round-trip tests for the new events in the existing event serialization test, including `Metrics.Sample` inside an `EventEnvelope`.
- [x] 2.3 Add `Constants.LiveMetrics.CLUSTER_ENTITY`, `CLUSTER_MIN_ENTITY`, `CLUSTER_MAX_ENTITY`, `RATE_WINDOW`, `MICROS_PER_MILLI`, `GAUGE_MAX_AGE_SECONDS = 30`, and change its KDoc to "the `metrics stream` cycle".
- [x] 2.4 Update the `EventEnvelope` KDoc: `RedisSamplePublisher` also builds envelopes.

## 3. Catalogue and selection

- [x] 3.1 Write `StreamMetricTest` (BaseKoinTest): 14 names; the 11 defaults and the 3 non-defaults; each query is scoped to `cluster="<name>-<id>"`; scope and entity mapping for `PerHost` (missing `host_name` skipped), `Cluster` and `ClusterRange`; NaN, Inf and empty results give no sample; p99 values divided by 1000.
- [x] 3.2 Write `services/metrics/StreamMetric` with the PromQL from the design table until the test passes.
- [x] 3.3 Write `MetricSelectionTest`: `node.cpu,,x` rejects the empty entry and `x` with their positions; duplicates are removed in order; a blank `--metrics` value is rejected; no `--metrics` gives the defaults.
- [x] 3.4 Write `services/metrics/MetricSelection` (parses the raw string, not picocli `split`).
- [x] 3.5 Write `MetricHealthTest`: start healthy; first failure gives `Failed(reason)`; later failures give `None`; next success gives `Recovered`.
- [x] 3.6 Write `services/metrics/MetricHealth`.

## 4. Catalogue values on real Mimir

- [x] 4.1 Rewrite `PromQlCompatibilityIntegrationTest`: drop the `MetricsQueries` import; `toolQueries()` becomes `StreamMetric.entries.map { it.promql("lab-x") }` plus the trace-to-metrics queries.
- [x] 4.2 Write `StreamMetricValuesIntegrationTest` in `src/integrationTest` (Mimir TestContainer, `PrometheusRemoteWrite`): per-host sums across several disks (partitions and loop excluded) and interfaces (`lo` and Cilium excluded); `/mnt/db1` only, and a host without it gives no sample; multi-core CPU ratio between 0 and 100; p99 `cluster.min` leaves out zeros and is absent when every node is 0 while `cluster.max` is 0; a gauge sample older than 30 s next to a fresh one counts only the fresh one.

## 5. Tunnel route

- [x] 5.1 Write `TunnelProcessControl` tests for the new `status(pid, port, sshConfig)` overload with a fake `lookup`; the existing `status(recorded)` cases stay green.
- [x] 5.2 Add the overload and make `status(recorded)` delegate to it.
- [x] 5.3 Write `RecordedTunnelRoutesTest` with a temporary env file and a fake `lookup`: Tailscale gives `Direct`; a live tunnel gives `Socks(port)`; no record, a dead PID and a PID held by another program give `Unavailable` whose reason says to run `socks start`; a new port in the file is returned on the next call.
- [x] 5.4 Write `proxy/ObservabilityRoutes` and `RecordedTunnelRoutes`.
- [x] 5.5 Write `RoutedObservabilityHttp` tests (integration tier, MockWebServer): the tenant header is sent; `Direct` uses no proxy; `Socks(port)` sets an explicit `java.net.Proxy(SOCKS, 127.0.0.1:port)`; `ensureRunning` is never called; `socksProxyHost` / `socksProxyPort` are never set.
- [x] 5.6 Move the request building in `DefaultObservabilityHttp.send` into `controlNodeCall(...)`, write `services/RoutedObservabilityHttp` on it, and update the readers list in the `ObservabilityHttp` KDoc (`metrics stream` replaces "MCP metrics").

## 6. Publisher, cycle, loop and signals

- [x] 6.1 Write a Redis TestContainer test for `RedisSamplePublisher`: a published sample arrives as an `EventEnvelope` with `Metrics.Sample`, timestamp and command name; `ping` and `publish` throw `RedisPublishException` after the container stops.
- [x] 6.2 Write `services/metrics/SamplePublisher` and `RedisSamplePublisher` on the shared `RedisPublisher`.
- [x] 6.3 Write `MetricsCycleTest` (BaseKoinTest, fakes for `mimirFor`, `SamplePublisher`, `ObservabilityRoutes` and the stop flag): one failed metric does not block the others; each failure kind (no response, non-200, body status not `success`) counts; one `MetricFailing` per run of failures and one `MetricRecovered`; cluster not running fails every metric with no query; `Unavailable` route fails every metric with the `socks start` reason; publish failure drops the rest of the cycle and reconnects; ping failure goes through the same reconnect; reconnect success continues; all 3 attempts failed gives `RedisLost(url, reason)`; stop flag checked before each query, health record and publish, and before a failure counts as Redis lost; samples never reach the `EventBus`; the `CycleReport` counts.
- [x] 6.4 Write `services/metrics/MetricsCycle` with the reconnect `RetryConfig` defaulting to `createNetworkRetryConfig<Unit>()`.
- [x] 6.5 Write `TickLoopTest` with a fake clock and sleeper: ticks at `t0 + k·5s`; a slow cycle skips the missed tick; cycles never overlap; the loop ends on stop.
- [x] 6.6 Write `services/metrics/TickLoop`.
- [x] 6.7 Write `StopSignals` tests: the first signal sets the flag, restores the earlier handlers and interrupts the command thread; the interrupt flag is cleared when the loop ends.
- [x] 6.8 Write `services/metrics/StopSignals` and `JvmStopSignals` (`sun.misc.Signal` for INT and TERM).
- [x] 6.9 Write `MetricsStreamServiceTest`: `run` returns `Stopped` on stop and `RedisLost` after failed reconnects, and calls `onCycle` once per completed cycle.
- [x] 6.10 Write `services/metrics/MetricsStreamService` and register the new services in Koin.

## 7. Commands

- [x] 7.1 Write `MetricsStreamTest` (BaseKoinTest): bad names emit `Metrics.InvalidNames` and exit non-zero before any cycle; `EASY_DB_LAB_REDIS_URL` unset emits `Metrics.RedisNotConfigured` and exits non-zero; no running cluster emits `Metrics.NoRunningCluster` and exits non-zero; a redirected cluster is refused; one summary line per cycle with samples published and metrics failed, and no sample printed; `Stopped` returns exit 0; `RedisLost` exits non-zero naming the URL.
- [x] 7.2 Write `commands/metrics/Metrics.kt` (group), `MetricsStream.kt` (`@RequireProfileSetup`, `@RequiresProxy`, help says "see 'metrics list'") and register the group in `CommandLineParser.kt`.
- [x] 7.3 Write `MetricsListTest`: prints the 14 names, one per line, and emits no events.
- [x] 7.4 Write `commands/metrics/MetricsList.kt` with `println`.
- [ ] 7.5 Write a process-level test that the first SIGTERM to a running `metrics stream` exits 0 and a second one while stopping exits 143.

## 8. MCP removal

- [ ] 8.1 Delete `annotations/McpCommand.kt` and remove every `@McpCommand` use and import under `commands/` (`grep -rn "@McpCommand" src/main/kotlin` returns nothing), as one mechanical step.
- [ ] 8.2 Rename the `mcp/` package to `server/` (main and test), and `McpServer` to `StatusServer`, keeping the Ktor/Netty server, `/swagger`, `/status`, `/stress/status`, `StatusCache` and `StatusResponse`.
- [ ] 8.3 Write a `StatusServer` test: the server answers `/status` and `/swagger`, does not serve `/sse`, and emits `Event.Server.Ready` naming only the status and swagger URLs.
- [ ] 8.4 Remove from `StatusServer` the SDK imports, `initializeStreaming`, the tool and status handlers, tool and prompt registration, `configureSseRoutes`, the `MetricsCollector` start and `ServerStatusResponse`; emit `Event.Server.Ready` after bind.
- [ ] 8.5 Delete `McpToolRegistry.kt`, `ChannelMessageBuffer.kt`, `PromptLoader.kt`, `PromptResource.kt`, `MetricsCollector.kt`, `events/McpEventListener.kt`, and the resources `src/main/resources/com/rustyrazorblade/mcp/activate.md` and `provision.md`.
- [ ] 8.6 In `Server.kt`, delete `McpServerConfig` / `McpConfiguration`, the `.mcp.json` write, the `McpConfigSaved` emit and `context.isInteractive = true`; set the `@Command` description to "Start the REST status server (cluster status at /status, API docs at /swagger)"; update the `@RequiresProxy` KDoc to name only the status cache refresh.  Write a test that `server` writes no `.mcp.json`.
- [ ] 8.7 Remove the `Event.Mcp` domain, `Command.McpConfigSaved`, `Metrics.Node`, `Metrics.System` and `Metrics.Cassandra` from `Event.kt`.
- [ ] 8.8 Remove `OutputEvent`, `ChannelOutputHandler` and `FilteringChannelOutputHandler` from `output/OutputHandler.kt`; remove `Context.isMcp` and `Context.forMcp`, and the `isMcp` argument of `forCli` / `forRepl`.
- [ ] 8.9 Remove the `DefaultMimirQueryService` Koin binding from `ServicesModule.kt` (the class and its test stay).
- [ ] 8.10 Remove `libs.mcp.sdk` and `libs.kotlinx.io` from `build.gradle.kts` and `gradle/libs.versions.toml`; confirm the Ktor SSE plugin is gone from the dependency tree and ClassGraph stays.
- [ ] 8.11 Delete the tests `ChannelMessageBufferTest`, `CleanMessageTest`, `McpAnnotationTest`, `McpDebugTest`, `McpEnumTest`, `McpMessageFlowTest`, `McpSchemaValidationTest`, `McpServerBackgroundTest`, `McpServerSimpleTest`, `McpServerStreamingTest`, `McpToolNamespacingTest`, `MetricsCollectorTest`, `PromptLoaderTest`, `ChannelOutputHandlerTest`, `FilteringChannelOutputHandlerTest`, `FilteringChannelOutputHandlerFrameResetTest` and `McpEventListenerTest`; keep `StatusCacheTest`.
- [ ] 8.12 Remove the three `McpSchemaValidationTest` entries from `config/detekt/baseline-test.xml` (entries for a deleted file only; no rule is loosened).
- [ ] 8.13 Update stale KDocs and comments: `Constants.kt`, `ssh/Redaction.kt`, `profiling/ProfilingBounds.kt` (validation stays; reason becomes CLI input only), `commands/cassandra/CassandraInstall.kt`, `events/EventListener.kt`, `services/MarkdownDocument.kt`, `services/FrontmatterMarkdownLoader.kt`, `services/HelpTopicService.kt`, and the test comments in `SSHClientTest`, `ProfilingBoundsTest`, `ProfileCommandGroupTest`, `ProfilingCommandsTest`.
- [ ] 8.14 Update `.claude/skills/agent-test/SKILL.md` (the `**/mcp/**` pattern becomes `**/server/**`; "MCP server /status" becomes "server /status") and delete `.claude/commands/test-workflow.md`.

## 9. Docs

- [ ] 9.1 Add a "Metrics Commands" section to `docs/reference/commands.md` (`metrics stream`, `metrics list`) next to "Logs Commands", and change the `server` entry to REST only.
- [ ] 9.2 Write `docs/integrations/metrics-stream.md`: the catalogue with units and defaults; p99 `cluster.min` and `cluster.max` and the zero rule; the 30 s gauge age and the build-switch overlap; input bytes for `compaction_bytes_written`; the `Metrics.Sample` JSON and entities (`db0`); the failure and recovery events; the summary line; Redis behaviour (startup, per-cycle ping, reconnect timing, drop); tunnel reuse ("run `socks start`") and Tailscale; stopping and exit codes.  Add it to `docs/SUMMARY.md`.
- [ ] 9.3 Rewrite `docs/integrations/server.md` as REST and swagger only (delete "Adding to Claude Code", "Available Tools", "Tool Naming Convention", "Benefits", "Example Workflow" and "Live Metrics Streaming", which held the `db-0` error), and link to the metrics stream page.
- [ ] 9.4 Update `README.md` Server Integration to REST only.
- [ ] 9.5 Update `events/CLAUDE.md` (listeners, the `Metrics` domain, `Metrics.Sample` goes to Redis only, best-effort Redis after startup, no `Mcp` domain).
- [ ] 9.6 Update `commands/CLAUDE.md` (the `metrics` group, no `@McpCommand` in the annotation list, `Server` without `isInteractive`).
- [ ] 9.7 Replace `mcp/CLAUDE.md` with `server/CLAUDE.md` describing the REST status server and status cache.
- [ ] 9.8 Update the root `CLAUDE.md`: the events rule ("MCP client or Redis subscriber" becomes "Redis subscriber"), the listener list (no `McpEventListener`), the Server & REPL section, the subdirectory index (`server/CLAUDE.md`), and the Observability "CLI commands" line (`metrics stream`, `metrics list`).
- [ ] 9.9 Update `src/test/.../CLAUDE.md` to drop the MCP test references.
- [ ] 9.10 Check the delta specs in `openspec/changes/issue-1043/specs/` against the built behaviour (messages, names, constants) and correct any that differ.  At archive, also update the `live-stream-metrics` and `server` Purpose lines and the `event-system` Data Contract text "(and in MCP structured output)".

## 10. Build checks

- [ ] 10.1 Run `./gradlew ktlintFormat`, `./gradlew detekt` (JDK 21), `./gradlew test` and `./gradlew integrationTest` in a subagent, and fix every failure.
- [ ] 10.2 Run `./gradlew installDist`.

## 11. Real cluster check

- [ ] 11.1 Provision a cluster with db and app nodes, start Cassandra, set `EASY_DB_LAB_REDIS_URL` to a local Redis, and run `metrics stream --metrics node.cpu,cassandra.read_p99` with a subscriber: one `node.cpu` sample per host (`db0`, ...) and `cluster.min` / `cluster.max` for `cassandra.read_p99` each cycle.
- [ ] 11.2 Run `metrics stream` with the defaults under load; check the values against the Grafana panels; stop Cassandra and confirm the gauges stop within 30 s; start it again and confirm they come back with no restart.
- [ ] 11.3 Run `socks stop` mid-stream and confirm one failure per metric with the `socks start` reason; run `socks start` and confirm recovery on the new port.
- [ ] 11.4 Stop Redis mid-stream and confirm the reconnect and the exit naming the URL; send SIGTERM to a running stream and confirm exit 0.
- [ ] 11.5 Run `easy-db-lab server` and confirm `/status` and `/swagger` answer, `/sse` does not, and no `.mcp.json` is written.
