## 1. Event Types

- [x] 1.1 Add `Event.Metrics.SystemSnapshot` with `nodes: Map<String, NodeMetrics>` where `NodeMetrics` has `cpuUsagePct`, `memoryUsedBytes`, `diskReadBytesPerSec`, `diskWriteBytesPerSec`, `filesystemUsedPct`
- [x] 1.2 Add `Event.Metrics.CassandraSnapshot` with `readP99Ms`, `writeP99Ms`, `readOpsPerSec`, `writeOpsPerSec`, `compactionPending`, `compactionCompletedPerSec`, `compactionBytesWrittenPerSec`
- [x] 1.3 Register both event types in the kotlinx.serialization polymorphic hierarchy
- [x] 1.4 Add serialization round-trip tests for both event types

## 2. VictoriaMetrics Query Service

- [x] 2.1 Create `VictoriaMetricsQueryService` that executes PromQL instant queries against `/api/v1/query` via the SOCKS-proxied HTTP client
- [x] 2.2 Create kotlinx.serialization data classes for the Prometheus HTTP API response format (`status`, `data.resultType`, `data.result[].metric`, `data.result[].value`)
- [x] 2.3 Return parsed results as a `Result` type so callers handle failures without exceptions
- [x] 2.4 Register `VictoriaMetricsQueryService` as a Koin singleton in `ServicesModule`
- [x] 2.5 Add unit tests for response parsing (success, empty results, error responses)

## 3. Metrics Collector

- [x] 3.1 Create `MetricsCollector` that runs a daemon `Timer` at 5-second intervals
- [x] 3.2 Implement system metrics collection: execute the 5 system PromQL queries, group results by `host_name`, build `NodeMetrics` per node, emit `Event.Metrics.SystemSnapshot`
- [x] 3.3 Implement Cassandra metrics collection: check `ClusterState` for Cassandra db type, execute the 7 Cassandra PromQL queries, build and emit `Event.Metrics.CassandraSnapshot`
- [x] 3.4 Handle partial failures: catch errors per category so a failed Cassandra query does not prevent system metrics from being emitted
- [x] 3.5 Skip emitting events when query results are empty (no data yet)
- [x] 3.6 Add `start()` and `stop()` lifecycle methods

## 4. MCP Server Integration

- [x] 4.1 Wire `MetricsCollector` into `McpServer`: start the collector when Redis is configured, stop on shutdown
- [x] 4.2 Add the 5-second interval constant to `Constants`

## 5. Testing

- [x] 5.1 Add integration test verifying `MetricsCollector` emits `SystemSnapshot` events when given mock query results
- [x] 5.2 Add integration test verifying `CassandraSnapshot` is not emitted when cluster db type is not Cassandra
- [x] 5.3 Add integration test verifying partial failure isolation (Cassandra query fails, system metrics still emitted)

## 6. Documentation

- [x] 6.1 Update MCP server docs (`docs/`) to describe the live metrics streaming feature and the event JSON format
