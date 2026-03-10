# Server

The server command (`easy-db-lab server`) provides a hybrid HTTP server exposing cluster management capabilities via MCP (Model Context Protocol) for AI assistants, REST endpoints for programmatic access, and background services for status caching and metrics collection.

## Requirements

### REQ-SERVER-001: Server Lifecycle

The system MUST provide a server that AI assistants and HTTP clients can connect to for cluster management.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user starts the server on a specified port, **THEN** the server accepts SSE connections from MCP clients and HTTP requests on REST endpoints.
- **GIVEN** a running server, **WHEN** an AI assistant connects via MCP, **THEN** cluster management tools are available for invocation.

### REQ-SERVER-002: Structured Event Streaming

The system MUST stream structured events to connected MCP clients.

**Scenarios:**

- **GIVEN** a connected MCP client, **WHEN** cluster operations produce events, **THEN** the client receives structured event data with type information and metadata.

### REQ-SERVER-003: REST Status Endpoints

The server MUST expose REST HTTP endpoints for programmatic access to cluster status, independent of the MCP protocol.

**Scenarios:**

- **GIVEN** a running server, **WHEN** a client sends GET /status, **THEN** the server returns JSON with cluster info, EC2 instances, K8s pods, networking, security groups, EMR, OpenSearch, S3, Cassandra version, and access URLs.
- **GIVEN** a running server, **WHEN** a client sends GET /status?section=nodes, **THEN** the server returns only the nodes section of the status response.
- **GIVEN** a running server, **WHEN** a client sends GET /status?live=true, **THEN** the server bypasses the cache and fetches fresh data before responding.

### REQ-SERVER-004: Background Status Cache

The server MUST maintain a background cache of cluster status that refreshes periodically.

**Scenarios:**

- **GIVEN** a running server, **WHEN** the refresh interval elapses, **THEN** the StatusCache refreshes cluster data from EC2, K3s, EMR, and other sources.
- **GIVEN** a running server, **WHEN** a client queries /status without ?live=true, **THEN** the server responds immediately from the in-memory cache.

### REQ-SERVER-005: Optional Metrics Collection

The server MUST optionally collect and publish live metrics when a Redis connection is configured.

**Scenarios:**

- **GIVEN** the EASY_DB_LAB_REDIS_URL environment variable is set, **WHEN** the server starts, **THEN** the MetricsCollector polls VictoriaMetrics and publishes metric events to Redis pub/sub.
- **GIVEN** the EASY_DB_LAB_REDIS_URL environment variable is not set, **WHEN** the server starts, **THEN** the server runs normally without metrics collection.
