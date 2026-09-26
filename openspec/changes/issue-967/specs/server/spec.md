## MODIFIED Requirements

### Requirement: REST Status Endpoints

The server MUST expose REST HTTP endpoints for programmatic access to cluster status, independent of the MCP protocol.

#### Scenario: GET /status returns full JSON
- **GIVEN** a running server
- **WHEN** a client sends GET /status
- **THEN** the server returns JSON with cluster info, EC2 instances, K8s pods, networking, security groups, EMR, OpenSearch, S3, Cassandra version, and access URLs.

#### Scenario: Section filter returns single section
- **GIVEN** a running server
- **WHEN** a client sends GET /status?section=nodes
- **THEN** the server returns only the nodes section of the status response.

#### Scenario: Live query bypasses cache
- **GIVEN** a running server
- **WHEN** a client sends GET /status?live=true
- **THEN** the server bypasses the cache and fetches fresh data before responding.

#### Scenario: Observability object includes every backend
- **GIVEN** a running cluster with a reachable control node
- **WHEN** a client sends GET /status
- **THEN** the `accessInfo.observability` object SHALL include `grafana`, `mimir`, `loki`, `tempo` and `pyroscope` URL fields
- **AND** `mimir` is `http://<controlPrivateIp>:9009`, `loki` is `http://<controlPrivateIp>:3100`, `tempo` is `http://<controlPrivateIp>:3200` and `pyroscope` is `http://<controlPrivateIp>:4040`

### Requirement: Optional Metrics Collection

The server MUST optionally collect and publish live metrics when a Redis connection is configured.

#### Scenario: Metrics collected when Redis configured
- **GIVEN** the EASY_DB_LAB_REDIS_URL environment variable is set
- **WHEN** the server starts
- **THEN** the MetricsCollector polls Mimir with the cluster's tenant, scoped to the current cluster, and publishes metric events to Redis pub/sub.

#### Scenario: Server runs without Redis
- **GIVEN** the EASY_DB_LAB_REDIS_URL environment variable is not set
- **WHEN** the server starts
- **THEN** the server runs normally without metrics collection.
