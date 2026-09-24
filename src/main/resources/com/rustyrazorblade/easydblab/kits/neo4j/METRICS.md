# Neo4j Metrics Catalog

**Neo4j Community Edition exposes no database-level metrics.** It registers no `org.neo4j`
MBeans and has no metrics endpoint: transaction, page cache, Bolt, query and store metrics are
Enterprise-only. The only metrics available are the JVM, HTTP-server and log metrics the
OpenTelemetry Java agent and the node collector produce, listed below.

The agent (`/usr/local/otel/opentelemetry-javaagent.jar`, mounted from the host) is attached to the
Neo4j JVM and pushes OTLP to the collector on the pod's own node. `OTEL_SERVICE_NAME=neo4j` makes
every series arrive with `job="neo4j"` and `service_name="neo4j"`. Every series also carries
`cluster`, `host_name` (the db node, e.g. `db0`), and `instance` / `service_instance_id` (a UUID
the agent generates per JVM start, so it changes on every pod restart).

This list matches the live cluster's `metrics-catalog.json` (`bin/export-workload-metrics neo4j`).

---

## JVM memory

| Metric | Labels | Description |
|--------|--------|-------------|
| `jvm_memory_used_bytes` | `jvm_memory_type`, `jvm_memory_pool_name` | Bytes used, per pool (`heap`: G1 Eden, Survivor, Old Gen; `non_heap`: Metaspace, Compressed Class Space, code heaps) |
| `jvm_memory_committed_bytes` | `jvm_memory_type`, `jvm_memory_pool_name` | Bytes committed by the JVM, per pool |
| `jvm_memory_limit_bytes` | `jvm_memory_type`, `jvm_memory_pool_name` | Maximum bytes, only for pools that have one (G1 Old Gen for heap; code heaps and Compressed Class Space for non-heap; not Metaspace, Eden or Survivor) |
| `jvm_memory_used_after_last_gc_bytes` | `jvm_memory_type`, `jvm_memory_pool_name` | Bytes in use right after the most recent GC, per heap pool |

## Garbage collection

| Metric | Labels | Description |
|--------|--------|-------------|
| `jvm_gc_duration_seconds_bucket` | `jvm_gc_name`, `jvm_gc_action`, `le` | GC duration histogram; buckets 0.01, 0.1, 1, 10 s |
| `jvm_gc_duration_seconds_count` | `jvm_gc_name`, `jvm_gc_action` | GC events |
| `jvm_gc_duration_seconds_sum` | `jvm_gc_name`, `jvm_gc_action` | Seconds spent in GC |

`jvm_gc_name` is `G1 Young Generation` or `G1 Concurrent GC`; `jvm_gc_action` is `end of minor GC`
or `end of concurrent GC pause`.

## CPU, threads and classes

| Metric | Labels | Description |
|--------|--------|-------------|
| `jvm_cpu_time_seconds_total` | | CPU seconds used by the JVM |
| `jvm_cpu_recent_utilization_ratio` | | Recent JVM CPU use as a fraction (0..1) of all available cores |
| `jvm_cpu_count` | | Processors available to the JVM |
| `jvm_thread_count` | `jvm_thread_state`, `jvm_thread_daemon` | Live threads |
| `jvm_class_count` | | Classes currently loaded |
| `jvm_class_loaded_total` | | Classes loaded since start |
| `jvm_class_unloaded_total` | | Classes unloaded since start |

## HTTP API (Jetty)

The agent's Jetty instrumentation covers Neo4j's HTTP API on port 7474 (NodePort 30474). Bolt is
not instrumented, so Bolt traffic produces no request metrics. These series only exist once the
HTTP API has served a request.

| Metric | Labels | Description |
|--------|--------|-------------|
| `http_server_request_duration_seconds_bucket` | `http_request_method`, `http_route`, `http_response_status_code`, `url_scheme`, `network_protocol_version`, `le` | Request latency histogram (seconds) |
| `http_server_request_duration_seconds_count` | same, without `le` | Requests served |
| `http_server_request_duration_seconds_sum` | same, without `le` | Seconds spent serving requests |

`http_route` is templated, e.g. `/db/{databaseName}/query/v2` and `/db/{databaseName}/tx/commit`.

The collector's spanmetrics connector derives the same requests from the agent's server spans:

| Metric | Labels | Description |
|--------|--------|-------------|
| `traces_spanmetrics_calls_total` | `span_name`, `span_kind`, `status_code`, `http_route` | Server spans (one per HTTP request) |
| `traces_spanmetrics_duration_milliseconds_bucket` | same, plus `le` | Span duration histogram (**milliseconds**) |
| `traces_spanmetrics_duration_milliseconds_count` | same | Spans counted in the histogram |
| `traces_spanmetrics_duration_milliseconds_sum` | same | Total span duration (milliseconds) |

## Logs

| Metric | Labels | Description |
|--------|--------|-------------|
| `cassandra_log_records_total` | `severity`, `logger` | Log records at WARN and above. Despite the name, this is the collector's count connector applied to every service's logs, not only Cassandra's |

## Agent self-telemetry

| Metric | Labels | Description |
|--------|--------|-------------|
| `otlp_exporter_seen_total` | `type` | Items (`metric`, `span`, `log`) handed to the agent's OTLP exporter |
| `otlp_exporter_exported_total` | `type`, `success` | Items the OTLP exporter sent |
| `processedSpans_total` | `processorType`, `dropped` | Spans processed by the agent's batch span processor |
| `processedLogs_total` | `processorType`, `dropped` | Log records processed by the agent's batch log processor |
| `queueSize_ratio` | `processorType` | Fill ratio of the agent's batch processor queues |
| `target_info` | resource attributes | Constant 1, carrying the agent's resource attributes |
