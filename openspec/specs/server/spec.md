# Server

## Purpose

The server command (`easy-db-lab server`) provides a hybrid HTTP server exposing cluster management capabilities via MCP (Model Context Protocol) for AI assistants, REST endpoints for programmatic access, and background services for status caching and metrics collection.

## Requirements

### REQ-SERVER-001: Server Lifecycle

The system MUST provide a server that AI assistants and HTTP clients can connect to for cluster management.

#### Scenario: Server accepts MCP and REST connections
- **GIVEN** a running cluster
- **WHEN** the user starts the server on a specified port
- **THEN** the server accepts SSE connections from MCP clients and HTTP requests on REST endpoints.

#### Scenario: MCP tools available on connect
- **GIVEN** a running server
- **WHEN** an AI assistant connects via MCP
- **THEN** cluster management tools are available for invocation.

### REQ-SERVER-002: Structured Event Streaming

The system MUST stream structured events to connected MCP clients.

#### Scenario: Structured events streamed to client
- **GIVEN** a connected MCP client
- **WHEN** cluster operations produce events
- **THEN** the client receives structured event data with type information and metadata.

### REQ-SERVER-003: REST Status Endpoints

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

#### Scenario: Observability object includes Tempo and Pyroscope fields
- **GIVEN** a running cluster with a reachable control node
- **WHEN** a client sends GET /status
- **THEN** the `accessInfo.observability` object SHALL include `tempo` and `pyroscope` URL fields alongside `grafana`, `victoriaMetrics`, and `victoriaLogs`.

#### Scenario: Observability URLs include Tempo and Pyroscope
- **WHEN** a client requests GET /status on a cluster with a reachable control node
- **THEN** the response `accessInfo.observability` object contains `tempo` at `http://<controlPrivateIp>:3200` and `pyroscope` at `http://<controlPrivateIp>:4040`

### REQ-SERVER-004: Background Status Cache

The server MUST maintain a background cache of cluster status that refreshes periodically.

#### Scenario: Cache refreshes on interval
- **GIVEN** a running server
- **WHEN** the refresh interval elapses
- **THEN** the StatusCache refreshes cluster data from EC2, K3s, EMR, and other sources.

#### Scenario: Cached response served immediately
- **GIVEN** a running server
- **WHEN** a client queries /status without ?live=true
- **THEN** the server responds immediately from the in-memory cache.

### REQ-SERVER-005: Optional Metrics Collection

The server MUST optionally collect and publish live metrics when a Redis connection is configured.

#### Scenario: Metrics collected when Redis configured
- **GIVEN** the EASY_DB_LAB_REDIS_URL environment variable is set
- **WHEN** the server starts
- **THEN** the MetricsCollector polls VictoriaMetrics and publishes metric events to Redis pub/sub.

#### Scenario: Server runs without Redis
- **GIVEN** the EASY_DB_LAB_REDIS_URL environment variable is not set
- **WHEN** the server starts
- **THEN** the server runs normally without metrics collection.

### Requirement: Auto-shutdown CLI option
The server command SHALL accept an `--auto-shutdown` flag that enables infrastructure watchdog behavior.

#### Scenario: Flag not provided
- **WHEN** the user starts the server without `--auto-shutdown`
- **THEN** no watchdog is started and the server runs indefinitely

#### Scenario: Flag provided
- **WHEN** the user starts the server with `--auto-shutdown`
- **THEN** the infrastructure watchdog service is started as a background service
