## Context

EDBL Camera needs `easy-db-lab metrics stream` to show live metrics on stream, and Claude Code needs `metrics list` to choose names from a test plan.  Neither command exists.

Current state that this change meets:

- The only live-metrics path is `server` with `EASY_DB_LAB_REDIS_URL` set.  `mcp/MetricsCollector.kt` publishes `Metrics.System` and `Metrics.Cassandra` every 5 s.  `MetricsQueries.forCluster` (`:203-220`) queries `org_apache_cassandra_metrics_*` names that nothing exports, and its CPU query (`:208`) is the form `dashboards/CLAUDE.md` bans.  It is not a source for the new catalogue.
- Cassandra p99 is computed on each node.  `cassandra-jmx-rules.yaml:101-103` says these percentiles "cannot be re-aggregated into a cluster p99".
- The OTel Java agent exports Cassandra metrics every 5 s (`packer/cassandra/cassandra.in.sh:84`).  The collector's `metrics/otlp` pipeline batches with `timeout: 10s` before `prometheus_remote_write`.  A live gauge's newest sample is normally at most about 15 s old when queried.
- `timestamp()` already parses on the pinned Mimir 3.2.1: four dashboards use it, and `PromQlCompatibilityIntegrationTest` sends every dashboard query to the pinned Mimir.
- `ObservabilityHttp` always calls `ensureRunning` (`ObservabilityHttp.kt:86-88`), which can replace a tunnel that fails its reuse check (`ProcessSocksProxyService.kt:160-176`).
- When `EASY_DB_LAB_REDIS_URL` is set, every command builds a Redis listener with the Koin `EventBus` (`di/EventBusModule.kt:25-29`), and it pings then (`RedisEventListener.kt:41`).  A ping failure comes out of Koin with a message that names neither the variable nor the URL.  Mid-run, `onEvent` throws through `EventBus.emit`, so a Redis drop kills any command.
- `createNetworkRetryConfig` makes 3 attempts with waits of 1 s and 2 s (`RetryUtil.kt:226-235`).
- `MimirQueryService.query` returns a `Result`, and `PromQueryResult.numericValue` maps NaN and Inf to null.  `EventEnvelope` already carries the timestamp.
- Host aliases are the OS hostnames (`commands/SetupInstance.kt:125`).  The data mount is `Constants.K8s.DB_MOUNT_PATH` (`/mnt/db1`).

Constraints: the owner's decisions at the design stop (`.spec-flow/design-decisions.md`) override the architect's recommendations where they differ.  The SOCKS rule in the root `CLAUDE.md` forbids touching `socksProxyHost` / `socksProxyPort`; a new client gets an explicit per-connection `java.net.Proxy(SOCKS, ...)`.  Shell-side state comes from `.socks5-proxy.env`, never JSON.

## Goals / Non-Goals

**Goals:**
- `metrics stream` publishes chosen metrics as `Metrics.Sample` on Redis every 5 s, with correct queries.
- `metrics list` prints the accepted names.
- The stream survives query failures, tunnel loss and short Redis outages, and reports each failure once.
- Every command survives a mid-run Redis loss, and a Redis failure at startup names the variable and URL.
- Remove the MCP part of `server`; keep the REST status server.

**Non-Goals:**
- Changing the REST status endpoints or the status cache.
- One-shot `metrics query` (issue 935).
- Caller-supplied PromQL, or names beyond the 14.
- A configurable interval (fixed at 5 s).
- Listing the names in `--help`.
- Queueing or replaying samples that could not be published.
- The REPL command list (`Repl.kt` `ShellCommands`, issue 897).

## Decisions

### Approach

New command group `metrics`:

- **`metrics list`** prints the 14 names with `println`.  It emits no events.
- **`metrics stream [--metrics a,b,...]`** is thin.  It checks its preconditions, then calls `MetricsStreamService.run`.

Each cycle runs on the command's thread and does these steps in order:

1. **Stop check.**
2. **Redis ping** (D15).  On failure, check the stop flag, then reconnect.
3. **Cluster state check** (D8).  If the infrastructure is not UP, or there is no control host, every metric fails with "the cluster is not running".
4. **Route lookup** (D12).  Tailscale gives a direct route.  Otherwise the recorded tunnel's port is used, if the tunnel is alive.  If no live tunnel is recorded, every metric fails with "no live SOCKS tunnel is recorded; run `easy-db-lab socks start`".
5. **One query per selected metric**, through a `MimirQueryService` built on a reuse-only `ObservabilityHttp` for this cycle's route.  The stop flag is checked before each query.
6. **Health transitions** go to `MetricFailing` / `MetricRecovered` on the `EventBus`.  The stop flag is checked before each record.
7. **Publish each sample** to Redis directly.  The stop flag is checked before each publish.  On a publish failure, the stop flag is checked, then the stream reconnects and drops the rest of the cycle (D11).
8. **Return a `CycleReport`.**  The command prints it as one line.

`TickLoop` aligns cycles to 5 s ticks and skips any tick that arrives while a cycle runs (D7).  The first INT or TERM sets the stop flag, restores the JVM's own handlers, and interrupts the command thread.  The command then returns, and the process exits 0.  A second signal reaches the JVM default and exits 130 or 143 (D6).

```
MetricsStream (command, thin)
  ├─ MetricSelection.parse(--metrics) ─► Rejected ► Metrics.InvalidNames + CommandFailedException
  ├─ RedisConfiguration.NotConfigured ─► Metrics.RedisNotConfigured + CommandFailedException
  ├─ running-cluster check ────────────► Metrics.NoRunningCluster + CommandFailedException
  ├─ requireLocalTelemetryStack("metrics stream")
  └─ MetricsStreamService.run(metrics) { report -> println(report.summaryLine()) } : StreamEnd
        StopSignals (1st INT/TERM: flag + restore JVM handlers + interrupt)
        TickLoop (Clock, skips ticks)
          └─ MetricsCycle
               ├─ SamplePublisher.ping()            ─ failure ► reconnect (Retry, createNetworkRetryConfig)
               ├─ ClusterStateManager.load()        ─ not running ► all metrics fail
               ├─ ObservabilityRoutes.current(state) ─ Unavailable ► all metrics fail ("run socks start")
               ├─ mimirFor(RoutedObservabilityHttp(state, route)).query(metric.promql(cluster))
               ├─ StreamMetric.samples(results)
               ├─ MetricHealth.record(...) ► eventBus.emit(MetricFailing / MetricRecovered)
               └─ SamplePublisher.publish(sample)   ─ failure ► reconnect, drop rest of cycle
```

### Structure and boundaries

New package `services/metrics/`, matching `services/aws`, `services/documents` and `services/sql`:

| Type | Single responsibility |
|---|---|
| `StreamMetric` (enum, 14 entries) | The catalogue.  Each entry has `metricName`, `scope` (`PerHost`, `Cluster`, `ClusterRange`), `defaultOn`, `promql(cluster)` and `samples(results)`.  The companion has `byName` and `defaults()`. |
| `MetricSelection` | Parses the raw `--metrics` string into a sealed `Selected(list)` or `Rejected(entries)`.  Duplicates are removed in order.  Empty and unknown entries are reported with their position.  It parses a raw `String`, not picocli `split`. |
| `MetricHealth` | `record(metric, Result)` returns a `Transition` of `None`, `Failed(reason)` or `Recovered`.  Every metric starts healthy. |
| `SamplePublisher` + `RedisSamplePublisher` | `publish(sample)` and `ping()` throw `RedisPublishException`; also `url`.  It builds the `EventEnvelope` (timestamp, `EventContext.current()`) and serializes it with `EventEnvelope.toJson`. |
| `MetricsCycle` | One cycle.  Returns `CycleOutcome.Completed(report)`, `RedisLost(url, reason)` or `Stopped`.  Takes `mimirFor: (ObservabilityHttp) -> MimirQueryService = ::DefaultMimirQueryService`. |
| `TickLoop` | Ticks at `t0 + k·5s`, skipping missed ticks.  Uses an injected `java.time.Clock` (the pattern at `AnnotationMirror.kt:116`) and a sleeper. |
| `StopSignals` + `JvmStopSignals` | INT/TERM through `sun.misc.Signal`.  The first signal sets the flag, puts back the handlers that were installed before, and interrupts the command thread. |
| `MetricsStreamService` | Composes the pieces.  `run(metrics, onCycle): StreamEnd` (`Stopped` or `RedisLost`). |
| `commands/metrics/{Metrics,MetricsStream,MetricsList}.kt` | The group follows `commands/logs/Logs.kt`.  Registered in `CommandLineParser.kt`.  `metrics stream` carries `@RequireProfileSetup` and `@RequiresProxy`.  `metrics list` carries none. |

New Redis types in `events/`:

- `RedisEndpoint.parse(url)`: host, port and channel, extracted from `RedisEventListener.kt:31-36`.
- `RedisPublisher(endpoint)`: owns the `RedisClient`.  Its constructor pings and throws `RedisUnavailableException`, whose message names `EASY_DB_LAB_REDIS_URL` and the URL.  It has `publish(json)`, `ping()` and `close()`.
- Sealed `RedisConfiguration`: `NotConfigured` or `Configured(endpoint)`, a Koin singleton built from the environment.
- `eventBusModule` resolves `RedisPublisher` only when the configuration is `Configured`.  `RedisEventListener` (best effort) and `RedisSamplePublisher` (throws) share that one publisher.

Test seams: `mimirFor` (a lambda returning a fake `MimirQueryService`), `SamplePublisher` (a fake that fails N times), `Clock` and sleeper (a fake clock), `StopSignals` (a fake), `ObservabilityRoutes` (an interface, faked), and the reconnect `RetryConfig` (a constructor default of `createNetworkRetryConfig<Unit>()`; tests pass one with no waits).  Every constructor stays under the detekt threshold of 8.

### Tunnel route: reuse only, never `ensureRunning`

`proxy/ObservabilityRoutes` (interface) and `RecordedTunnelRoutes`:

```kotlin
sealed interface ObservabilityRoute { data object Direct : ObservabilityRoute; data class Socks(val port: Int) : ObservabilityRoute }
sealed interface RouteLookup { data class Usable(val route: ObservabilityRoute) : RouteLookup; data class Unavailable(val reason: String) : RouteLookup }
fun current(state: ClusterState): RouteLookup
```

- **Tailscale** (`state.isTailscaleEnabled()`, the same test `ObservabilityHttp.kt:86` uses) gives `Direct`.
- **Otherwise** it reads `ProxyEnvFile.read().tunnel`: the `EDL_SOCKS_PORT` / `EDL_SOCKS_PID` pair, written only after end-to-end verification and removed as a pair.  It reads no JSON.  A live tunnel gives `Socks(port)`.  No record, or a dead PID, gives `Unavailable("no live SOCKS tunnel is recorded; run 'easy-db-lab socks start'")`.
- **Liveness (R2):** the identity check that `TunnelProcessControl.status` already uses: the PID must be the `edl-socks-tunnel` script, with the recorded port and `<workspace>/sshConfig`.  This is stronger than `kill -0`, because a PID the OS gave to another program passes `kill -0` but fails this check.  `TunnelProcessControl` gains `status(pid, port, sshConfig)`, and the existing `status(recorded)` delegates to it.

`services/RoutedObservabilityHttp(state, route, httpClientFactory)` is an `ObservabilityHttp` for one cycle:
- It uses the cycle's `ClusterState`: the control node's private IP and the tenant.
- It builds its client as `httpClientFactory.createClient().newBuilder().proxy(...)`.  `Socks(port)` becomes `java.net.Proxy(SOCKS, InetSocketAddress(Constants.Proxy.TUNNEL_LISTEN_ADDRESS, port))`.  `Direct` becomes `Proxy.NO_PROXY`.  An explicit OkHttp `proxy` overrides the client's `Socks5ProxySelector`, which reads `Constants.Proxy.PORT_PROPERTY` and could point at a tunnel that has since been replaced.
- It never calls `ensureRunning`, and it never touches `socksProxyHost`/`socksProxyPort`.

No duplicated query code: the request building in `DefaultObservabilityHttp.send` (URL, tenant header, read timeout, `callTimeout(ZERO)`, execute) moves into one internal function, `controlNodeCall(...)`.  Both implementations call it.  `DefaultObservabilityHttp` keeps its `ensureRunning` behaviour for every other reader.  `MimirQueryService` is unchanged; `MetricsCycle` builds `DefaultMimirQueryService(RoutedObservabilityHttp(...))` each cycle through `mimirFor`.

### Data model

No tables, files or state changes.  One new event on the Redis channel, in `Event.Metrics`:

```kotlin
@Serializable @SerialName("Metrics.Sample")
data class Sample(val name: String, val entity: String, val value: Double) : Metrics
```

`entity` is a host alias, `cluster`, `cluster.min` or `cluster.max`.  Each sample is a normal `EventEnvelope` (`timestamp`, `commandName = "stream"`).

### Catalogue and PromQL

Notation:
- `c` stands for `cluster="<name>-<id>"` (`ClusterState.clusterLabelName()`).
- The mount is `Constants.K8s.DB_MOUNT_PATH`.
- The rate window is a fixed `[1m]`.
- `A` is `Constants.LiveMetrics.GAUGE_MAX_AGE_SECONDS = 30` (D13).
- `fresh(S)` stands for `(S and (time() - timestamp(S) < A))`.  Set operators match label sets without `__name__`, so this keeps only the series of `S` whose newest sample is younger than A.
- `Lat(op)` is `cassandra_client_request_latency_p99_microseconds{cassandra_operation="op", c}`.

| Name | Default | Entity | Unit | PromQL |
|---|---|---|---|---|
| `node.cpu` | yes | host | percent | `100 * (1 - sum by (host_name) (rate(system_cpu_time_seconds_total{state="idle", c}[1m])) / sum by (host_name) (rate(system_cpu_time_seconds_total{c}[1m])))` |
| `node.memory` | no | host | bytes | `max by (host_name) (system_memory_usage_bytes{state="used", c})` |
| `node.disk_read` | yes | host | B/s | `sum by (host_name) (rate(system_disk_io_bytes_total{direction="read", device!~"loop.*\|.*p[0-9]+", c}[1m]))` |
| `node.disk_write` | yes | host | B/s | same as above, with `direction="write"` |
| `node.filesystem_used` | yes | host | percent | `100 * sum by (host_name) (system_filesystem_usage_bytes{state="used", mountpoint="/mnt/db1", c}) / sum by (host_name) (system_filesystem_usage_bytes{state=~"used\|free", mountpoint="/mnt/db1", c})` |
| `node.network_receive` | yes | host | B/s | `sum by (host_name) (rate(system_network_io_bytes_total{direction="receive", device=~"ens.*\|eth.*", c}[1m]))` |
| `node.network_transmit` | yes | host | B/s | same as above, with `direction="transmit"` |
| `cassandra.read_p99` | yes | `cluster.min`, `cluster.max` | ms | `label_replace(min(fresh(Lat(read) > 0)), "entity", "cluster.min", "", "") / 1000 or label_replace(max(fresh(Lat(read))), "entity", "cluster.max", "", "") / 1000` |
| `cassandra.write_p99` | yes | `cluster.min`, `cluster.max` | ms | same as above, with `write` |
| `cassandra.read_ops` | yes | cluster | req/s | `sum(rate(cassandra_client_request_count_total{cassandra_operation="read", c}[1m]))` |
| `cassandra.write_ops` | yes | cluster | req/s | same as above, with `write` |
| `cassandra.compaction_pending` | yes | cluster | tasks | `sum(fresh(cassandra_compaction_tasks_pending{c}))` |
| `cassandra.compaction_completed` | no | cluster | tasks/s | `sum(rate(cassandra_compaction_tasks_completed_total{c}[1m]))` |
| `cassandra.compaction_bytes_written` | no | cluster | B/s | `sum(rate(cassandra_compaction_bytes_compacted_total{c}[1m]))` (counts input bytes; the docs say so) |

Sources:
- System metrics: `dashboards/infrastructure/system-overview.json` ("CPU Usage", "Memory Usage", "Disk I/O", "Network I/O", "Filesystem Usage %").
- Latency and throughput: `cassandra/cassandra-overview.json`.  Latency is in ms (`latency_unit=1000`).
- Compaction: `compaction-storage.json`.
- Memory uses `max by` because a host briefly has two series after a label change (`dashboards/CLAUDE.md:85`).

Sample mapping:
- `PerHost`: `entity` is the `host_name` label, for example `db0` or `ip-…` on EMR.  A series with no `host_name` is skipped.
- `Cluster`: `entity` is `"cluster"`.
- `ClusterRange`: `entity` is the `entity` label that `label_replace` writes (`CLUSTER_MIN_ENTITY`, `CLUSTER_MAX_ENTITY`).

One query per p99 metric (R3): the `or` of two `label_replace`d aggregates returns both values from one query, so a p99 metric fails or recovers as one unit.  If every node reports 0, the `min` side is empty, so no `cluster.min` is published; `cluster.max` publishes 0.

Empty results: when Cassandra is not running, the `cassandra.*` queries return empty vectors, so nothing is published and nothing fails.  When Cassandra starts mid-stream, its metrics publish from the next cycle.  When Cassandra stops, the gauges drop its series within 30 s; the rates empty within the 1 m window.

### Interfaces and exit codes

- `easy-db-lab metrics stream [--metrics <csv>]`.  The help text says "see 'metrics list'".
- `easy-db-lab metrics list`.

| Situation | Result |
|---|---|
| First INT or TERM | exit 0 |
| Second INT or TERM | the JVM default (130 or 143) |
| Bad names, Redis unset, no running cluster, or Redis lost after 3 attempts | exit 1, through `CommandFailedException` after a typed event |
| Redis not answering at start | exit 1 from `Main` |
| Redirect cluster | refused by `requireLocalTelemetryStack` |

```kotlin
interface SamplePublisher { val url: String; fun publish(sample: Event.Metrics.Sample); fun ping() }
sealed interface StreamEnd { data object Stopped : StreamEnd; data class RedisLost(val url: String, val reason: String) : StreamEnd }
data class CycleReport(val published: Int, val failed: Int) { fun summaryLine(): String }
fun MetricsStreamService.run(metrics: List<StreamMetric>, onCycle: (CycleReport) -> Unit): StreamEnd
```

Events (data fields only, each with `toDisplayString()`):

| Event | Error? | Purpose |
|---|---|---|
| `Metrics.Sample(name, entity, value)` | no | Redis only; never on the bus (D1) |
| `Metrics.MetricFailing(name, reason)` | yes | the first failure of a run of failures |
| `Metrics.MetricRecovered(name)` | no | the next success after a failure |
| `Metrics.InvalidNames(entries: List<InvalidMetricName(position, value, reason)>)` | yes | names every bad entry |
| `Metrics.RedisNotConfigured(envVar)` | yes | the variable is not set |
| `Metrics.RedisLost(url, reason)` | yes | all reconnect attempts failed |
| `Metrics.NoRunningCluster(workspace)` | yes | no running cluster at start |
| `Server.Ready(statusUrl, swaggerUrl)` | no | replaces `Mcp.ServerReady`, in the existing `Event.Server` domain |

Removed events: `Metrics.Node`, `Metrics.System`, `Metrics.Cassandra`, the whole `Event.Mcp` domain, and `Command.McpConfigSaved`.

New constants in `Constants.LiveMetrics`: `CLUSTER_ENTITY`, `CLUSTER_MIN_ENTITY`, `CLUSTER_MAX_ENTITY`, `RATE_WINDOW`, `MICROS_PER_MILLI` and `GAUGE_MAX_AGE_SECONDS`.  Its KDoc changes to "the `metrics stream` cycle".  The running-cluster check follows `SocksStart.kt:28-33`.

### Startup Redis failure

`Main` resolves the `EventBus` right after `startKoin`.  If `RedisUnavailableException` is anywhere in the cause chain (unwrapping Koin's creation exception), `Main` prints its message, which names `EASY_DB_LAB_REDIS_URL` and the URL, and exits 1.  This happens before any command.  It is the one place with no typed event, because the bus itself could not be built.

### Owner decisions

- **D1 (Q1) Sample route:** a dedicated `SamplePublisher` sharing the bus's `RedisPublisher`; samples skip the bus and the console.  Rationale: the stream sees Redis loss directly instead of through `emit` exceptions that depend on listener order.  Cost: a second place builds `EventEnvelope`s, and the event-system spec gets a `Metrics.Sample` exception.
- **D2 (Q2) Global listener mid-run:** best effort.  After startup a failed publish is logged and the event dropped, for every command.  The startup ping stays fail-fast and names `EASY_DB_LAB_REDIS_URL` and the URL.  Rationale: a short Redis outage must not kill other commands.
- **D3 (Q9) Cycle summary:** `println` from the command's `onCycle` callback, not an event.  Rationale: no external system needs it.
- **D4 (Q5, Q13) Cluster p99:** both `cluster.min = min(x > 0)` and `cluster.max = max(x)` every cycle, always both.  Names stay `cassandra.read_p99` / `cassandra.write_p99`.  Rationale (owner): "Show the min and max … that is essential telemetry".  Zero nodes served no requests, so they are left out of `min`.
- **D5 (Q4) `compaction_bytes_written`:** keep the name, map to `cassandra_compaction_bytes_compacted_total` (input bytes), and say so in the docs.  Rationale: keeps the 14 names; a new JMX rule is out of scope.
- **D6 (Q7, Q9) Signals:** `sun.misc.Signal` handlers for INT and TERM (in `jdk.unsupported`, shipped by every JDK 21+; no jlink runtime).  The first signal sets the stop flag, restores the earlier handlers and interrupts the command thread; the command returns and the process exits 0 with shutdown hooks run normally.  A second signal exits at once with 130 or 143.  Handlers are installed when `run()` starts; a signal during preconditions or `@RequiresProxy` gets the JVM default.  No per-query timeout change.
- **D7 (Q9) Scheduler:** a single-threaded, tick-aligned loop on the command thread that skips missed ticks.  Rationale: keeps `EventContext` correct and cycles never overlap.
- **D8 (Q9) State check each cycle:** yes.  No queries run against a cluster that is not UP or has no control host.
- **D9 (Q6) Device filters:** the dashboards' filters.  Disk `device!~"loop.*|.*p[0-9]+"`, network `device=~"ens.*|eth.*"`.
- **D10 (Q9) Reconnect timing:** `Retry.of(..., RetryUtil.createNetworkRetryConfig<Unit>())` around `ping()`, unchanged: 3 attempts, 1 s then 2 s apart.  Jedis exceptions are runtime exceptions, so the plain decorators apply.
- **D11 (Q9) After a publish failure:** drop the rest of the cycle's samples, reconnect, continue on the next tick.
- **D12 (Q3, Q11) Tunnel:** reuse only (see "Tunnel route").  `@RequiresProxy` still starts or reuses one before the first cycle.  No live tunnel: each metric fails once with "run socks start".
- **D13 (Q12) Gauge age:** gauges keep only samples younger than 30 s.  Why 30 s: a live series is at most about 15 s old (5 s export plus up to 10 s batch); 30 s is twice that, so a live node never drops out, and a stopped JVM or the previous build's series is gone within 30 s instead of Mimir's 5-minute lookback.  For up to 30 s after a build switch, both builds can count; the docs say so.
- **D15 (Q8) Redis detection:** ping Redis at the start of every cycle; a ping failure goes through the same reconnect path as a publish failure.  The stop flag is checked before a ping or publish failure counts as Redis lost, so a stop during a reconnect wait ends with `Stopped`.
- **D16 (Q10) MCP removal:** remove the MCP part of `server` in this change.  `server` stays a REST status server with its status cache.  Fold in the `RedisEndpoint` extraction and the `db-0` → `db0` docs fix.
- **Fold-ins without question:** the stop flag is checked before each query, health record and publish (critic 1); the spec states the gauge window (critic 3).

### MCP removal

What `server` keeps: the Ktor/Netty embedded server; `swaggerUI("/swagger")` with the OpenAPI routing source; the `/status` and `/stress/status` routes with their `describe {}` blocks and `respondWithCachedStatus`; `StatusCache` (refresh interval, `--auto-shutdown` watchdog, `Server.InfrastructureGone`); `StatusResponse`; and the `--port`, `--bind`, `--refresh` and `--auto-shutdown` options.  `McpServer` becomes `StatusServer`, holding only these.  After it binds, it emits `Event.Server.Ready(statusUrl, swaggerUrl)`.  It names no `/sse`.

`Server.kt`:
- Delete `McpServerConfig` / `McpConfiguration`, the `.mcp.json` write and the `McpConfigSaved` emit, and `context.isInteractive = true` (it only kept resources open for commands run in-process by MCP tools).
- New `@Command` description: "Start the REST status server (cluster status at /status, API docs at /swagger)".
- `@RequiresProxy` stays.  Its KDoc names only `StatusCache`'s refresh, which calls K3s/K8s through Fabric8 every cycle.

Main code deleted:

| What | Where |
|---|---|
| The MCP server parts | `mcp/McpServer.kt`: SDK imports, `initializeStreaming`, the tool and status handlers, tool and prompt registration, `configureSseRoutes`, the `MetricsCollector` start, `ServerStatusResponse` |
| Tool registry | `mcp/McpToolRegistry.kt` |
| Output buffer | `mcp/ChannelMessageBuffer.kt` |
| Prompts | `mcp/PromptLoader.kt`, `mcp/PromptResource.kt`, `src/main/resources/com/rustyrazorblade/mcp/activate.md` and `provision.md`.  `FrontmatterMarkdownLoader` stays, for `HelpTopicService`. |
| Old metrics stream | `mcp/MetricsCollector.kt` (`MetricsCollector`, `MetricsQueries`) |
| MCP listener | `events/McpEventListener.kt` |
| The annotation | `annotations/McpCommand.kt` and every `@McpCommand` use and import under `commands/` (`grep -rn "@McpCommand" src/main/kotlin`) |
| Events | the `Event.Mcp` domain, `Command.McpConfigSaved`, `Metrics.Node`/`System`/`Cassandra` |
| Channel output | `output/OutputHandler.kt`: `OutputEvent`, `ChannelOutputHandler`, `FilteringChannelOutputHandler`.  `CompositeOutputHandler` and the other handlers stay. |
| Context flags | `Context.isMcp` and `Context.forMcp`; `forCli`/`forRepl` lose the `isMcp` argument |
| Koin binding | `singleOf(::DefaultMimirQueryService) bind MimirQueryService::class` in `ServicesModule.kt`.  The class and its unit test stay. |
| Dependencies | `libs.mcp.sdk`, `libs.kotlinx.io` in `build.gradle.kts` and `gradle/libs.versions.toml`.  The Ktor SSE plugin came in with the MCP SDK and goes with it.  ClassGraph stays. |

Tests deleted: in `src/test/.../mcp/`: `ChannelMessageBufferTest`, `CleanMessageTest`, `McpAnnotationTest`, `McpDebugTest`, `McpEnumTest`, `McpMessageFlowTest`, `McpSchemaValidationTest`, `McpServerBackgroundTest`, `McpServerSimpleTest`, `McpServerStreamingTest`, `McpToolNamespacingTest`, `MetricsCollectorTest`, `PromptLoaderTest`; in `src/test/.../output/`: `ChannelOutputHandlerTest`, `FilteringChannelOutputHandlerTest`, `FilteringChannelOutputHandlerFrameResetTest`; and `src/test/.../events/McpEventListenerTest`.  `StatusCacheTest` stays.  None of the deleted tests cover the REST routes.  The three `McpSchemaValidationTest` entries in `config/detekt/baseline-test.xml` are removed; this removes entries for a deleted file and does not loosen any rule.  `PromQlCompatibilityIntegrationTest` drops the `MetricsQueries` import, and `toolQueries()` becomes `StreamMetric.entries.map { it.promql("lab-x") }` plus the trace-to-metrics queries.

Stale references updated: KDocs and comments (`Constants.kt` LiveMetrics, `ssh/Redaction.kt`, `profiling/ProfilingBounds.kt` (the validation stays; the reason becomes CLI input only), `commands/cassandra/CassandraInstall.kt`, `events/EventListener.kt`, `services/ObservabilityHttp.kt` readers list, `services/MarkdownDocument.kt`, `services/FrontmatterMarkdownLoader.kt`, `services/HelpTopicService.kt`); test comments (`SSHClientTest`, `ProfilingBoundsTest`, `ProfileCommandGroupTest`, `ProfilingCommandsTest`); CLAUDE.md files (`src/test/.../CLAUDE.md`, `mcp/CLAUDE.md` → `server/CLAUDE.md`, root `CLAUDE.md`, `commands/CLAUDE.md`, `events/CLAUDE.md`); user docs (`README.md` Server Integration, `docs/integrations/server.md`, `docs/reference/commands.md`); agent files (`.claude/skills/agent-test/SKILL.md`; delete `.claude/commands/test-workflow.md`).  `docs/plans/` is a dated historical record and is left alone.

Package name (R1): `mcp/` holds only `StatusCache`, `StatusResponse` and the REST server after the removal, so it is renamed to `server/`, with `server/CLAUDE.md` and the test directory to match.

## Alternatives Considered

Each entry lists the options presented at that decision point, the choice, and why each other option was rejected.

### D1: how samples reach Redis
- **A dedicated `SamplePublisher` sharing the bus's `RedisPublisher`; samples skip the bus and console (chosen, as recommended).**
- **Emit samples on the bus with an `Event.isConsoleOutput()` flag that `ConsoleEventListener` skips.**  Rejected: the stream would only see Redis loss through exceptions from `emit`, and that depends on listener order (`EventBusModule.kt:23-28`).

### D2: the global `RedisEventListener` mid-run
- **Best effort after startup; startup stays fail-fast and names the variable and URL (chosen, as recommended).**
- **Keep throwing, and wrap every emit in the stream in a catch.**  Rejected: it depends on listener order, and a short Redis outage would still kill other commands.

### D3: the per-cycle summary
- **`println` from the command's `onCycle` callback (chosen, as recommended).**
- **A `Metrics.CycleCompleted` event.**  Rejected: no external system needs it, and it would put a message on the Camera's channel every 5 s.

### D4: cluster value for p99 — owner override
- **Both `cluster.min = min(x > 0)` and `cluster.max = max(x)`, always both (chosen).**  **Owner override:** the architect recommended `max` only.  Owner: "Show the min and max … that is essential telemetry", and "I do not need the override".
- **`max` only (the slowest node).**  The architect's recommendation; overridden by the owner.
- **`avg`.**  Rejected: it hides a slow node.
- **One sample per host.**  Rejected: the AC asks for cluster-level entities.
- **A way to request only one of min or max.**  Rejected by the owner: always both.

### D5: `cassandra.compaction_bytes_written`
- **Keep the name, map to `cassandra_compaction_bytes_compacted_total` (input bytes), documented (chosen, as recommended).**
- **Rename it to `cassandra.compaction_bytes_compacted`.**  Rejected: it changes the 14 names.
- **Add a JMX rule for bytes written.**  Rejected: out of scope.

### D6: Ctrl-C and SIGTERM
- **`sun.misc.Signal` handlers; first signal is a clean stop with exit 0 (chosen, as recommended).**
- **A shutdown hook that calls `Runtime.halt(0)`.**  Rejected: it kills the other hooks that are still running.
- **The JVM default only.**  Rejected: it exits 130 or 143, not 0.

### D7: the scheduler
- **A single-threaded, tick-aligned loop on the command thread, skipping missed ticks (chosen, as recommended).**
- **`scheduleAtFixedRate`.**  Rejected: it runs late executions back to back.
- **`scheduleWithFixedDelay`.**  Rejected: it drifts.
- **An executor plus a busy guard.**  Rejected: two threads, and events lose the command name.

### D8: cluster state check each cycle
- **Check each cycle (chosen, as recommended).**
- **Query regardless.**  Rejected: slow, and with reuse-only routing it is pointless.

### D9: device filters
- **The dashboards' filters (chosen, as recommended).**
- **Sum every device.**  Rejected: partitions and pod interfaces would be counted twice.

### D10: reconnect timing
- **`createNetworkRetryConfig` unchanged, 3 attempts 1 s then 2 s apart; the AC wording now matches (chosen, as recommended).**
- **A different config, to reach a 4 s wait.**  Rejected: the AC names `createNetworkRetryConfig`.

### D11: after a publish failure
- **Drop the rest of the cycle, reconnect, continue on the next tick (chosen, as recommended).**
- **Keep publishing the rest of the cycle after a reconnect.**  Not chosen; the design files record no further reason beyond the owner accepting the recommendation.

### Tunnel reuse (critic 2, Q3 and Q11, D12)
- **Reuse only: each cycle reads `EDL_SOCKS_PORT` / `EDL_SOCKS_PID` from `.socks5-proxy.env` and queries through a reuse-only `ObservabilityHttp`; never `ensureRunning` (chosen).**  Raised by the critic, not the architect; the v1 design used `ensureRunning` per query.
- **Keep `DefaultObservabilityHttp`, which calls `ensureRunning` per query.**  Rejected: it could replace another command's tunnel mid-run.
- **Read the in-JVM `PORT_PROPERTY`.**  Rejected: it goes stale when another process records a new tunnel.
- **Probe the tunnel once per cycle before the queries.**  Rejected: the queries themselves show it.

### Second signal (critic 4, Q7)
- **A second INT/TERM while stopping exits at once with the default status, 130 or 143 (chosen).**
- **On a second signal, keep waiting for the stop.**  Rejected by the owner.
- **Change the per-query timeout so a stop is never slow.**  Not done: no per-query timeout change.

### Per-cycle ping (critic 5, Q8, D15)
- **Ping Redis at the start of every cycle; failure goes through the reconnect path; stop flag checked first (chosen).**
- **Detect loss only on publish.**  Rejected: a cycle that publishes nothing (Cassandra down, all metrics failing) would never notice.

### Stale gauges (Q12, D13)
- **Gauge queries keep only samples younger than 30 s via `timestamp()` (chosen; the critic raised it, the architect chose the age).**
- **Mimir's 5-minute lookback.**  Rejected by the owner; the 5-minute exception in the AC is removed.
- **10 s or 15 s.**  Rejected: within the normal batch delay, so live nodes would flicker out.
- **Filter on `cassandra_build`.**  Rejected: the stream would need to know the current build.

### Zero in min (Q13)
- **`min(x > 0)`: zero-valued nodes are left out; if none served, no `cluster.min` (chosen).**
- **`min(x)` including zeros.**  Rejected: idle nodes would pin the minimum to 0.

### MCP removal placement (Q10, D16) — owner scope change
- **Remove the MCP part of `server` in this change (chosen).**  **Owner override:** the architect recommended a separate issue to fix or retire `MetricsCollector` only.  Owner: "I am sick of clarifying every change for the MCP server I never use".
- **A separate issue for `MetricsCollector`.**  The architect's recommendation; overtaken by the owner's scope change.
- **Point `MetricsCollector` at `StreamMetric` and keep MCP.**  Rejected by the owner.

### MCP part only, not the whole server (Q10) — owner override
- **Remove only the MCP part; `server` stays as a REST status server with its status cache (chosen).**  Owner: "only the mcp part".
- **Remove the whole `server` command.**  Rejected by the owner.  The REST status endpoints and status cache are out of scope and stay as they are.

### R1: package name (architect recommendation, adopted pending owner review)
- **Rename `mcp/` to `server/`, `McpServer` to `StatusServer` (adopted).**  A package called `mcp` with no MCP in it misleads the next reader.
- **Keep `mcp/`, as the issue text assumes.**  Not adopted for that reason.

### R2: tunnel liveness (architect recommendation, adopted pending owner review)
- **`TunnelProcessControl` identity check (adopted).**  Stronger than `kill -0`: a reused PID fails it.
- **Literal `kill -0` via `ProcessHandle.of(pid).isAlive`.**  Matches the shell wrappers word for word, but a reused PID would show up as a failed query instead of "run socks start".

### R3: p99 query shape (architect recommendation, adopted pending owner review)
- **One query per p99 metric (`or` of two `label_replace`d aggregates) (adopted).**  The metric fails and recovers as one unit, so one failure event names it.
- **Two queries per metric.**  Simpler PromQL, but two health states for one name.

## Risks / Trade-offs

- [Redis blast radius] Every command with `EASY_DB_LAB_REDIS_URL` set changes: a clear startup message, and no command dies on a mid-run Redis loss. → `EventBusModule.kt` and `Main` change; covered by event-system scenarios and a Redis TestContainer test.
- [MCP removal] Any external user of `/sse` or `.mcp.json` loses it, by the owner's decision.  The `@McpCommand` sweep touches many command files with import-only edits. → Do the sweep as one mechanical step, separate from the behaviour changes.  The REST endpoints are unchanged.
- [Tunnel never repaired] If another process stops the tunnel (`socks stop`, `down`), the stream reports failures until a tunnel is recorded again. → Intended (D12).  `TunnelProcessControl` gains an overload; existing callers are unchanged.
- [Gauge overlap] For up to 30 s after `cassandra use`, both builds' series can count in `compaction_pending` and p99 min/max. → Documented.
- [Rate staleness] Rate-based metrics include a stopped node's counter for up to the 1 m window. → Inherent to `rate()`; accepted.
- [Interrupts] The interrupt flag must be cleared when the loop ends.  An interrupted OkHttp call shows up as a failed query. → The stop flag is checked before recording a failure, and nothing is emitted after a stop.
- [Issue 935 overlap] Both plan a `metrics` group. → Whichever lands second adds to it.
- [`timestamp()` semantics] Parsing is proven by the dashboards; value semantics are not. → Value test on real Mimir with a stale and a fresh gauge sample.

## Migration Plan

No migration: clusters are ephemeral.  Users of the server's metrics stream switch to `metrics stream`.

## Open Questions

R1 (package rename), R2 (identity check rather than bare `kill -0`) and R3 (one query per p99 metric) are the architect's recommendations.  The owner has not ruled on them; they are adopted here and are confirmed or changed at the spec approval.
