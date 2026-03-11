## RENAMED Requirements

### Requirement: MCP Server Lifecycle
**FROM:** MCP Server Lifecycle (in `mcp-server` spec)
**TO:** Server Lifecycle (in `server` spec)

### Requirement: Structured Event Streaming
**FROM:** Structured Event Streaming (in `mcp-server` spec)
**TO:** Structured Event Streaming (in `server` spec)

## ADDED Requirements

### Requirement: REST Status Endpoints
The server SHALL expose REST HTTP endpoints for programmatic access to cluster status, independent of the MCP protocol.

#### Scenario: Full status query
- **WHEN** a client sends GET /status to the server
- **THEN** the server returns JSON with cluster info, EC2 instances, K8s pods, networking, security groups, EMR, OpenSearch, S3, Cassandra version, and access URLs

#### Scenario: Filtered status query
- **WHEN** a client sends GET /status?section=nodes to the server
- **THEN** the server returns only the nodes section of the status response

#### Scenario: Force refresh
- **WHEN** a client sends GET /status?live=true to the server
- **THEN** the server bypasses the cache and fetches fresh data before responding

### Requirement: Background Status Cache
The server SHALL maintain a background cache of cluster status that refreshes periodically.

#### Scenario: Periodic refresh
- **WHEN** the server is running
- **THEN** the StatusCache refreshes cluster data at the configured interval (default 30 seconds)

#### Scenario: Instant response from cache
- **WHEN** a client queries /status without ?live=true
- **THEN** the server responds immediately from the in-memory cache without blocking on external calls

### Requirement: Optional Metrics Collection
The server SHALL optionally collect and publish live metrics when a Redis connection is configured.

#### Scenario: Metrics publishing with Redis
- **WHEN** the EASY_DB_LAB_REDIS_URL environment variable is set
- **THEN** the MetricsCollector polls VictoriaMetrics and publishes metric events to Redis pub/sub

#### Scenario: No Redis configured
- **WHEN** the EASY_DB_LAB_REDIS_URL environment variable is not set
- **THEN** the server starts normally without metrics collection
