## Why

EDBL Camera starts `easy-db-lab metrics stream` at the start of a cluster session, to show live metrics on stream.  The command does not exist.

Today the only live-metrics path is `easy-db-lab server` with `EASY_DB_LAB_REDIS_URL` set.  It publishes two fixed events (`Metrics.System`, `Metrics.Cassandra`) every 5 seconds, so a caller cannot choose the metrics.  That path is also broken: its Cassandra queries use metric names nothing exports, and its CPU query is the form the dashboards ban.  Claude Code chooses the metrics from the test plan, so it also needs a way to list the names the command accepts.

The MCP server is not used, and keeping every change compatible with it costs time.  This change removes the MCP part of `server`.

## What Changes

- New command group `metrics` (`commands/metrics/`), registered in `CommandLineParser`:
  - `metrics stream [--metrics <name>,<name>,...]`.  Every 5 seconds it queries each selected metric from Mimir and publishes each value as one `Metrics.Sample(name, entity, value)` on the Redis channel that `EASY_DB_LAB_REDIS_URL` names.  It runs until it is stopped.  It carries `@RequireProfileSetup` and `@RequiresProxy`, and refuses a redirected cluster.
  - `metrics list`: prints the 14 accepted names, one per line, with `println`.  No events.
- A catalogue of 14 names.  Each query comes from the Grafana dashboards and the OTel Java agent's JMX rules (`cassandra-jmx-rules.yaml`), not from `MetricsQueries`:
  - `node.cpu`, `node.memory`, `node.disk_read`, `node.disk_write`, `node.filesystem_used`, `node.network_receive`, `node.network_transmit`.
  - `cassandra.read_p99`, `cassandra.write_p99`, `cassandra.read_ops`, `cassandra.write_ops`, `cassandra.compaction_pending`, `cassandra.compaction_completed`, `cassandra.compaction_bytes_written`.
- 11 default names when `--metrics` is not given: all of the above except `node.memory`, `cassandra.compaction_completed` and `cassandra.compaction_bytes_written`.
- `node.*` publish one sample per reporting host (`entity` = OS hostname, for example `db0`).  Disks are summed with the dashboard filter `device!~"loop.*|.*p[0-9]+"`, network with `device=~"ens.*|eth.*"`.  `node.filesystem_used` covers only `/mnt/db1`.
- `cassandra.read_p99` and `cassandra.write_p99` publish two samples per cycle: `entity=cluster.min` (lowest node value above 0) and `entity=cluster.max` (highest).  Other `cassandra.*` names publish `entity=cluster`.  `cassandra.compaction_bytes_written` maps to `cassandra_compaction_bytes_compacted_total`, which counts input bytes; the docs say so.
- The gauge queries (both p99s and `compaction_pending`) ignore samples 30 seconds old or older, so a stopped JVM or the previous build drops out within 30 seconds.
- Bad names (unknown or empty) are named with their positions and the command exits non-zero before the first cycle.  Repeated names are used once.
- No value, NaN or Inf publishes nothing and is not a failure.  A failed query does not block other metrics.  One `Metrics.MetricFailing(name, reason)` when a metric starts failing, one `Metrics.MetricRecovered(name)` when it next succeeds.
- One summary line per cycle on the terminal (`println`): samples published and metrics failed.  Samples go to Redis only, never through the `EventBus`, never to the console.
- Redis: the command needs `EASY_DB_LAB_REDIS_URL` (`Metrics.RedisNotConfigured`).  It pings Redis at the start of every cycle.  On a ping or publish failure it drops the rest of the cycle and reconnects with `RetryUtil.createNetworkRetryConfig()` (3 attempts, 1 s then 2 s apart).  If all fail, it emits `Metrics.RedisLost` and exits non-zero naming the URL.
- Every command with `EASY_DB_LAB_REDIS_URL` set: the startup ping stays fail-fast, and `Main` now prints a message naming `EASY_DB_LAB_REDIS_URL` and the URL.  After startup, `RedisEventListener` is best effort: a failed publish is logged and the event dropped.  URL parsing moves into a shared `RedisEndpoint`, and one `RedisPublisher` is shared by the listener and the stream.
- Tunnel: `@RequiresProxy` starts or reuses the tunnel before the first cycle.  After that, the stream never starts or replaces one.  Each cycle reads `EDL_SOCKS_PORT` / `EDL_SOCKS_PID` from `.socks5-proxy.env` and queries through a reuse-only `ObservabilityHttp` route with an explicit per-client SOCKS proxy and the tenant header.  No live tunnel: each metric fails once with a reason that says to run `socks start`.  A new tunnel on another port is used from the next cycle.  Tailscale: direct.
- Cluster state is checked each cycle.  No running cluster at start: `Metrics.NoRunningCluster` and exit non-zero.  Torn down mid-stream: each metric fails once and the stream keeps running.
- Cycles run on 5-second ticks on the command thread.  A tick that arrives while a cycle runs is skipped.  The first Ctrl-C or SIGTERM stops cleanly with exit 0, even mid-cycle, and nothing more is published.  A second signal exits at once with 130 or 143.
- MCP removal.  `server` keeps the REST `/status` and `/stress/status` endpoints, `/swagger`, the background `StatusCache`, `--auto-shutdown` and its other options.  It loses:
  - the MCP endpoint (`/sse`), the MCP tools, `McpToolRegistry`, `ChannelMessageBuffer`, `McpEventListener`, the prompts (`PromptLoader`, `PromptResource`, `activate.md`, `provision.md`), `MetricsCollector` / `MetricsQueries`;
  - the `@McpCommand` annotation and every use of it under `commands/`;
  - the `.mcp.json` write and `Event.Command.McpConfigSaved`;
  - the `Event.Mcp` domain, replaced by `Event.Server.Ready(statusUrl, swaggerUrl)`;
  - `Event.Metrics.Node`, `Metrics.System` and `Metrics.Cassandra`;
  - the `@Command` description that tells users to add an SSE MCP server;
  - the channel output handlers in `output/OutputHandler.kt` and their tests, `Context.isMcp` / `Context.forMcp`, and the `MimirQueryService` Koin binding;
  - the MCP SDK, `kotlinx.io` and Ktor SSE dependencies.
  - The `mcp/` package becomes `server/`, and `McpServer` becomes `StatusServer`.
- `PromQlCompatibilityIntegrationTest` is rewritten against the new catalogue.
- Stale references are updated: `README.md`, `config/detekt/baseline-test.xml` (entries for deleted tests only), `.claude/skills/agent-test/SKILL.md`, KDocs (`Server.kt`, `Constants.LiveMetrics` and others), `src/test/.../CLAUDE.md`.  `.claude/commands/test-workflow.md`, which uses only MCP tools, is deleted.
- Docs: new `docs/integrations/metrics-stream.md` and `docs/SUMMARY.md`; a Metrics section in `docs/reference/commands.md`; `docs/integrations/server.md` (REST only, `db0` not `db-0`); `events/CLAUDE.md`, `commands/CLAUDE.md`, the root `CLAUDE.md`, and `mcp/CLAUDE.md` replaced by `server/CLAUDE.md`.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `live-stream-metrics`: the `metrics stream` and `metrics list` commands, the catalogue, defaults, validation, aggregation, gauge age, failure reporting, Redis, tunnel, scheduling and stop behaviour.  The server-scoped streaming requirements are removed.  The Mimir query requirement covers the recorded tunnel and Tailscale.
- `event-system`: `Metrics.Sample` goes to Redis only; console and Redis are the destinations; the startup failure names the variable and URL; Redis is best effort after startup.
- `server`: a REST status server with no MCP; the event streaming and metrics collection requirements are removed.
- `observability-store`: `metrics stream` replaces the MCP metrics collector in the reader lists, and reads with the tenant and the cluster.

## Impact

- Kotlin, new: `commands/metrics/{Metrics,MetricsStream,MetricsList}.kt`; `services/metrics/` (`StreamMetric`, `MetricSelection`, `MetricHealth`, `SamplePublisher`, `RedisSamplePublisher`, `MetricsCycle`, `TickLoop`, `StopSignals`, `MetricsStreamService`); `events/RedisEndpoint`, `events/RedisPublisher`, `events/RedisConfiguration`; `proxy/ObservabilityRoutes` + `RecordedTunnelRoutes`; `services/RoutedObservabilityHttp`.
- Kotlin, changed: `Event.kt` (new `Metrics` and `Server.Ready` events; removed MCP and old metrics events), `EventEnvelope` KDoc, `RedisEventListener`, `di/EventBusModule.kt`, `Main.kt`, `ObservabilityHttp.kt` (shared `controlNodeCall`), `TunnelProcessControl` (`status(pid, port, sshConfig)` overload), `Constants.LiveMetrics`, `Server.kt`, `Context.kt`, `output/OutputHandler.kt`, `ServicesModule.kt`, `CommandLineParser.kt`, and every command file that carries `@McpCommand`.
- Kotlin, deleted: the MCP files listed above.
- Build: `build.gradle.kts` and `gradle/libs.versions.toml` lose the MCP SDK and `kotlinx.io`.
- Tests: new unit tests (BaseKoinTest) and integration tests (Redis and Mimir TestContainers); 17 MCP and channel-handler tests deleted; `PromQlCompatibilityIntegrationTest` rewritten.
- Behaviour change for every command with `EASY_DB_LAB_REDIS_URL` set: a mid-run Redis loss no longer stops the command.
- Any external user of `/sse` or `.mcp.json` loses it, by the owner's decision.  The REST endpoints are unchanged.
- The `live-stream-metrics` Purpose (names VictoriaMetrics) and the `server` Purpose (names MCP), and the `event-system` Data Contract text "(and in MCP structured output)", are outside any requirement, so the delta cannot change them.  They are corrected when the change is archived.
- Related issues: 935 ("Add structured-output query commands for metrics, logs, and traces") also plans a `metrics` group; whichever lands second adds to it.  897 owns the REPL command list.
