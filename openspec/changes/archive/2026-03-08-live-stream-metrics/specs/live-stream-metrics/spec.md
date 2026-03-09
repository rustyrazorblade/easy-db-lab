## ADDED Requirements

### Requirement: VictoriaMetrics PromQL query capability

The system SHALL support querying VictoriaMetrics via the Prometheus HTTP API (`/api/v1/query`) through the existing SOCKS proxy infrastructure.

#### Scenario: Instant query returns metric values

- **WHEN** a PromQL instant query is executed against VictoriaMetrics
- **THEN** the system returns parsed metric results with label sets and numeric values

#### Scenario: Query fails gracefully

- **WHEN** a PromQL query fails (network error, invalid query, VictoriaMetrics unreachable)
- **THEN** the system returns a failure result without throwing an exception
- **AND** the caller can handle the failure independently of other queries

#### Scenario: Query uses SOCKS proxy

- **WHEN** a PromQL query is executed
- **THEN** the HTTP request is routed through the existing SOCKS proxy to reach the cluster-internal VictoriaMetrics instance

### Requirement: System metrics streaming

The system SHALL publish per-node system metrics to the Redis pub/sub channel every 5 seconds when the MCP server is running with Redis configured.

#### Scenario: System metrics published for all db nodes

- **WHEN** the MCP server is running with Redis configured and db nodes are active
- **THEN** a `Metrics.SystemSnapshot` event is published every 5 seconds containing CPU usage, memory used, disk read/write throughput, and filesystem usage percentage for each db node

#### Scenario: Per-node metrics keyed by node alias

- **WHEN** a `Metrics.SystemSnapshot` event is published
- **THEN** each node's metrics are keyed by the node alias (e.g., `db-0`, `db-1`)

#### Scenario: No system event when no data available

- **WHEN** VictoriaMetrics returns empty results for system metrics (e.g., cluster just started)
- **THEN** no `Metrics.SystemSnapshot` event is published for that cycle

#### Scenario: Metrics collection does not start without Redis

- **WHEN** the MCP server is running without Redis configured
- **THEN** no metrics collection timer is started and no metrics events are emitted

### Requirement: Cassandra metrics streaming

The system SHALL publish cluster-wide Cassandra metrics to the Redis pub/sub channel every 5 seconds when the cluster is running Cassandra.

#### Scenario: Cassandra metrics published when Cassandra is the db type

- **WHEN** the MCP server is running with Redis configured and the cluster db type is Cassandra
- **THEN** a `Metrics.CassandraSnapshot` event is published every 5 seconds containing read/write p99 latency, read/write ops/sec, compaction pending count, compaction completed rate, and compaction bytes written rate

#### Scenario: No Cassandra event when db type is not Cassandra

- **WHEN** the cluster db type is ClickHouse, OpenSearch, or any non-Cassandra type
- **THEN** no `Metrics.CassandraSnapshot` event is published

#### Scenario: No Cassandra event when query returns empty results

- **WHEN** the Cassandra PromQL queries return empty results
- **THEN** no `Metrics.CassandraSnapshot` event is published for that cycle

#### Scenario: Cassandra query failure does not block system metrics

- **WHEN** the Cassandra PromQL query fails but system metrics queries succeed
- **THEN** the `Metrics.SystemSnapshot` event is still published for that cycle

### Requirement: Metrics use dashboard-consistent queries

The system SHALL use the same PromQL expressions as the Grafana dashboards to ensure metric values are consistent.

#### Scenario: CPU usage matches Grafana system overview

- **WHEN** the system collects CPU usage
- **THEN** it uses the same PromQL as the System Overview dashboard: `100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle"}[1m])) * 100)`

#### Scenario: Cassandra latency uses histogram quantile

- **WHEN** the system collects Cassandra read/write latency
- **THEN** it uses `histogram_quantile(0.99, ...)` over the `_bucket` series, matching the Cassandra dashboard
