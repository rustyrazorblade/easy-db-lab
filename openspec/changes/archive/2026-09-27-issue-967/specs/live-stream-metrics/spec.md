## RENAMED Requirements

- FROM: `### Requirement: VictoriaMetrics PromQL query capability`
- TO: `### Requirement: Mimir PromQL query capability`

## MODIFIED Requirements

### Requirement: Mimir PromQL query capability

The system SHALL support querying Mimir via its Prometheus HTTP API (`/prometheus/api/v1/query`) through the existing SOCKS proxy infrastructure, sending the cluster's tenant in the `X-Scope-OrgID` header.

#### Scenario: Instant query returns metric values

- **WHEN** a PromQL instant query is executed against Mimir
- **THEN** the system returns parsed metric results with label sets and numeric values

#### Scenario: Query fails gracefully

- **WHEN** a PromQL query fails (network error, invalid query, Mimir unreachable)
- **THEN** the system returns a failure result without throwing an exception
- **AND** the caller can handle the failure independently of other queries

#### Scenario: Query uses SOCKS proxy

- **WHEN** a PromQL query is executed
- **THEN** the HTTP request is routed through the existing SOCKS proxy to reach the cluster-internal Mimir instance

#### Scenario: Query carries the tenant

- **WHEN** a PromQL query is executed for a cluster in tenant `acme`
- **THEN** the request carries `X-Scope-OrgID: acme`

### Requirement: System metrics streaming

The system SHALL publish per-node system metrics to the Redis pub/sub channel every 5 seconds when the server is running with Redis configured.  The queries SHALL be scoped to the current cluster.

#### Scenario: System metrics published for all db nodes

- **WHEN** the server is running with Redis configured and db nodes are active
- **THEN** a `Metrics.System` event is published every 5 seconds containing CPU usage, memory used, disk read/write throughput, and filesystem usage percentage for each db node

#### Scenario: Per-node metrics keyed by node alias

- **WHEN** a `Metrics.System` event is published
- **THEN** each node's metrics are keyed by the node alias (e.g., `db-0`, `db-1`)

#### Scenario: No system event when no data available

- **WHEN** Mimir returns empty results for system metrics (e.g., cluster just started)
- **THEN** no `Metrics.System` event is published for that cycle

#### Scenario: Metrics collection does not start without Redis

- **WHEN** the server is running without Redis configured
- **THEN** no metrics collection timer is started and no metrics events are emitted

#### Scenario: Only the current cluster is reported

- **WHEN** the tenant also holds series from another cluster
- **THEN** the published metrics come only from the current cluster
