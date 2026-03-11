# Live Stream Metrics via Redis

## Summary

When the MCP server is running with Redis configured, publish live cluster metrics to the Redis pub/sub channel every 5 seconds. Metrics are queried from VictoriaMetrics using the same PromQL expressions the Grafana dashboards use. Only events for running services are published — no Cassandra events when Cassandra isn't running.

## Motivation

External tools (live dashboards) need real-time cluster metrics without polling Grafana or VictoriaMetrics directly. The Redis event stream already carries command events; adding metrics events to the same channel gives consumers a single integration point.

## Scope

### In scope
- New `VictoriaMetricsQueryService` — queries VictoriaMetrics `/api/v1/query` via existing SOCKS proxy
- New `MetricsCollector` — 5-second timer, runs PromQL queries, emits granular events
- New granular event types under `Event.Metrics`:
  - `Event.Metrics.SystemSnapshot` — per-node CPU, memory, disk I/O, filesystem
  - `Event.Metrics.CassandraSnapshot` — latency p99, throughput, compaction stats
- Published to existing Redis pub/sub channel (`easydblab-events`)
- Conditional: only emit events for services that are actually running

### Out of scope (future)
- Stress tool metrics (`mutations`, `selects`, `errors_total`)
- ClickHouse metrics
- OpenSearch metrics
- Network I/O metrics
- JVM metrics

## Design Decisions

- **Granular events over combined snapshot** — matches the codebase convention of domain-specific typed events. Provides fault isolation (one failed query doesn't block other categories) and clean extensibility (adding stress metrics = add a new event type, no existing code changes).
- **Same Redis channel** — no need for a separate channel. Consumers filter by event type.
- **PromQL with server-side rate computation** — dashboard-ready values, matching what Grafana shows.
- **5-second interval** — faster than the 30s status cache, provides near-real-time feel for live dashboards.

## Example Events

These are the JSON messages published to the Redis pub/sub channel. Each is an independent `EventEnvelope`.

### Event.Metrics.SystemSnapshot

Always published when db nodes are running. Per-node system metrics with the node alias as the key.

```json
{
  "timestamp": "2026-03-08T14:22:05.123Z",
  "commandName": "server",
  "event": {
    "type": "Event.Metrics.SystemSnapshot",
    "nodes": {
      "db-0": {
        "cpuUsagePct": 34.2,
        "memoryUsedBytes": 17179869184,
        "diskReadBytesPerSec": 52428800.0,
        "diskWriteBytesPerSec": 104857600.0,
        "filesystemUsedPct": 45.2
      },
      "db-1": {
        "cpuUsagePct": 28.7,
        "memoryUsedBytes": 16106127360,
        "diskReadBytesPerSec": 41943040.0,
        "diskWriteBytesPerSec": 83886080.0,
        "filesystemUsedPct": 42.8
      },
      "db-2": {
        "cpuUsagePct": 31.5,
        "memoryUsedBytes": 17448304640,
        "diskReadBytesPerSec": 48234496.0,
        "diskWriteBytesPerSec": 96468992.0,
        "filesystemUsedPct": 44.1
      }
    }
  }
}
```

### Event.Metrics.CassandraSnapshot

Only published when the cluster is running Cassandra (db node type is Cassandra). Cluster-wide aggregated metrics.

```json
{
  "timestamp": "2026-03-08T14:22:05.187Z",
  "commandName": "server",
  "event": {
    "type": "Event.Metrics.CassandraSnapshot",
    "readP99Ms": 1.247,
    "writeP99Ms": 0.832,
    "readOpsPerSec": 15234.5,
    "writeOpsPerSec": 12087.3,
    "compactionPending": 3,
    "compactionCompletedPerSec": 1.5,
    "compactionBytesWrittenPerSec": 52428800.0
  }
}
```

### Example: 3-node Cassandra cluster under load

A consumer subscribed to `easydblab-events` receives these messages every 5 seconds (in addition to any command events):

```
Message 1: {"timestamp":"...","commandName":"server","event":{"type":"Event.Metrics.SystemSnapshot","nodes":{"db-0":{...},"db-1":{...},"db-2":{...}}}}
Message 2: {"timestamp":"...","commandName":"server","event":{"type":"Event.Metrics.CassandraSnapshot","readP99Ms":1.247,...}}
```

### Example: ClickHouse cluster (no Cassandra)

Only system metrics are published:

```
Message 1: {"timestamp":"...","commandName":"server","event":{"type":"Event.Metrics.SystemSnapshot","nodes":{"db-0":{...}}}}
```

No `CassandraSnapshot` is published.

## PromQL Queries

All queries match the existing Grafana dashboards.

### System (always run)
| Metric | PromQL |
|--------|--------|
| CPU usage % | `100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle"}[1m])) * 100)` |
| Memory used | `system_memory_usage_bytes{state="used"}` |
| Disk read bytes/sec | `rate(system_disk_io_bytes_total{direction="read"}[1m])` |
| Disk write bytes/sec | `rate(system_disk_io_bytes_total{direction="write"}[1m])` |
| Filesystem used % | `100 * system_filesystem_usage_bytes{state="used"} / (system_filesystem_usage_bytes{state="used"} + system_filesystem_usage_bytes{state="free"})` |

### Cassandra (only when db type is Cassandra)
| Metric | PromQL |
|--------|--------|
| Read p99 | `histogram_quantile(0.99, sum(rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_bucket"}[1m])) by (le))` |
| Write p99 | `histogram_quantile(0.99, sum(rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_bucket"}[1m])) by (le))` |
| Read ops/sec | `sum(irate({__name__=~"org_apache_cassandra_metrics_client_request_latency_read_.+_count"}[1m]))` |
| Write ops/sec | `sum(irate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_count"}[1m]))` |
| Compaction pending | `sum(org_apache_cassandra_metrics_table_pending_compactions)` |
| Compaction completed/sec | `sum(irate(org_apache_cassandra_metrics_compaction_completed_tasks[1m]))` |
| Compaction bytes/sec | `sum(irate(org_apache_cassandra_metrics_table_compaction_bytes_written[1m]))` |

## Architecture

```
McpServer
  └── MetricsCollector (5s Timer, only when Redis is configured)
        ├── VictoriaMetricsQueryService
        │     └── HttpClientFactory (SOCKS proxy) → VictoriaMetrics:8428/api/v1/query
        ├── eventBus.emit(Event.Metrics.SystemSnapshot(...))
        └── eventBus.emit(Event.Metrics.CassandraSnapshot(...))   # only if Cassandra
              └── RedisEventListener → PUBLISH easydblab-events
```

Reuses existing infrastructure:
- `SocksProxyService` / `HttpClientFactory` for proxied HTTP to VictoriaMetrics
- `EventBus` + `RedisEventListener` for pub/sub on the same channel
- `ClusterState` to determine which nodes exist and their types
