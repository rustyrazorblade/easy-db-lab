## Context

The MCP server already runs a 30-second `StatusCache` timer that fetches infrastructure state (EC2, K3s pods, EMR, etc.). The cluster runs VictoriaMetrics on the control node (port 8428) collecting all metrics from OTel collectors, MAAC agent, stress tools, and eBPF exporters. The existing `SocksProxyService` + `HttpClientFactory` provides HTTP access to cluster-internal services via SSH SOCKS5 proxy.

Redis pub/sub is already wired: `RedisEventListener` subscribes to the `EventBus` and publishes `EventEnvelope` JSON to a configurable channel. There is no existing PromQL query code in the codebase — all VictoriaMetrics interaction today is export/import of native format or LogsQL queries.

## Goals / Non-Goals

**Goals:**
- Publish system and Cassandra metrics to Redis pub/sub every 5 seconds when the MCP server is running with Redis configured
- Use the same PromQL expressions as the Grafana dashboards for consistency
- Only publish metrics for services that are actually running
- Reuse existing SOCKS proxy and event bus infrastructure

**Non-Goals:**
- Stress, ClickHouse, OpenSearch, network, or JVM metrics (future work)
- Changing the Redis transport (stays pub/sub, same channel)
- Exposing metrics via the MCP HTTP endpoints
- Historical metrics or buffering — this is a live stream

## Decisions

### 1. VictoriaMetricsQueryService as a new service

A new `VictoriaMetricsQueryService` encapsulates PromQL queries against VictoriaMetrics `/api/v1/query`. It uses the existing `HttpClientFactory` (SOCKS-proxied OkHttp client) to reach the cluster-internal VictoriaMetrics instance.

**Why a dedicated service:** Separates the HTTP/PromQL concern from the collection logic. Other features (alerts, ad-hoc queries, MCP tools) can reuse it later. Follows the existing pattern of `VictoriaLogsService`, `VictoriaStreamService`, etc.

**Alternative considered:** Inline HTTP calls in the collector. Rejected because it couples query mechanics to collection scheduling.

### 2. MetricsCollector owns the timer and orchestration

A new `MetricsCollector` class runs a daemon `Timer` at 5-second intervals. Each tick:
1. Queries VictoriaMetrics for system metrics (always)
2. Queries VictoriaMetrics for Cassandra metrics (only if `ClusterState` has Cassandra db nodes)
3. Emits separate events for each category that returned data
4. Catches and logs errors per category — a failed Cassandra query does not block system metrics

**Why not in McpServer:** The collector is a self-contained concern. `McpServer` already manages the status cache, tool execution, SSE transport, and message buffering. Adding metrics collection directly would violate single responsibility.

**Why not always start:** The collector only starts when Redis is configured. Without Redis, there's no consumer for the events — the console listener would just print metrics to stdout every 5 seconds, which is not useful.

### 3. Granular domain events over combined snapshot

Two separate event types: `Event.Metrics.SystemSnapshot` and `Event.Metrics.CassandraSnapshot`. Each is emitted independently per collection cycle.

**Why:** Matches the codebase convention (REQ-ES-008 bans generic catch-all types). Provides fault isolation — if one PromQL query fails, the other category still publishes. Clean extensibility — adding stress metrics later is a new event type with no changes to existing code.

### 4. PromQL queries match Grafana dashboards exactly

The collector uses the same PromQL expressions found in the dashboard JSON files. This ensures the numbers on the live dashboard match what Grafana shows.

**Queries for system metrics** (per node, keyed by `host_name`):
- CPU: `100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle"}[1m])) * 100)`
- Memory: `system_memory_usage_bytes{state="used"}`
- Disk read: `rate(system_disk_io_bytes_total{direction="read"}[1m])`
- Disk write: `rate(system_disk_io_bytes_total{direction="write"}[1m])`
- Filesystem: `100 * system_filesystem_usage_bytes{state="used"} / (system_filesystem_usage_bytes{state="used"} + system_filesystem_usage_bytes{state="free"})`

**Queries for Cassandra metrics** (cluster-wide aggregates):
- Read p99: `histogram_quantile(0.99, sum(rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_bucket"}[1m])) by (le))`
- Write p99: `histogram_quantile(0.99, sum(rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_bucket"}[1m])) by (le))`
- Read ops/sec: `sum(irate({__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_count"}[1m]))`
- Write ops/sec: `sum(irate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_count"}[1m]))`
- Compaction pending: `sum(org_apache_cassandra_metrics_table_pending_compactions)`
- Compaction completed/sec: `sum(irate(org_apache_cassandra_metrics_compaction_completed_tasks[1m]))`
- Compaction bytes/sec: `sum(irate(org_apache_cassandra_metrics_table_compaction_bytes_written[1m]))`

### 5. Koin registration and lifecycle

`VictoriaMetricsQueryService` is registered as a Koin singleton in `ServicesModule`. `MetricsCollector` is created and started by `McpServer` when Redis is configured, and stopped on shutdown.

### 6. Response parsing

VictoriaMetrics `/api/v1/query` returns JSON in the Prometheus HTTP API format. The response contains `result` arrays with `metric` labels and `value` pairs. The service parses this with kotlinx.serialization data classes — no Jackson.

## Risks / Trade-offs

**[Scrape interval mismatch]** → OTel collectors scrape every 15s by default. Querying VictoriaMetrics every 5s means ~2 out of 3 queries return the same underlying data. This is acceptable — the dashboard sees consistent updates, and VictoriaMetrics handles repeated instant queries efficiently.

**[SOCKS proxy dependency]** → The collector needs the SOCKS proxy running to reach VictoriaMetrics. The proxy is started on-demand by `SocksProxyService.ensureRunning()` which is already used by the status cache. If the SSH connection drops, queries fail and the collector logs the error and retries next cycle.

**[Empty results on cold start]** → When VictoriaMetrics has no data yet (cluster just started), queries return empty results. The collector skips emitting events when there's no data — the dashboard simply shows no metrics until data arrives.

**[Query cost]** → 5-12 PromQL queries every 5 seconds against VictoriaMetrics. VictoriaMetrics is designed for this workload — Grafana does the same thing with auto-refresh. Not a concern.
