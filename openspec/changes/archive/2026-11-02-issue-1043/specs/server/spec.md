## MODIFIED Requirements

### Requirement: Server Lifecycle

The system MUST provide a REST status server that HTTP clients can connect to for cluster status.  The server MUST NOT expose an MCP endpoint, register MCP tools, or write `.mcp.json`.  No command carries `@McpCommand`.  When it is ready, the server MUST emit one ready event that names only the status URL and the API docs (swagger) URL.

#### Scenario: Server serves REST status endpoints
- **GIVEN** a running cluster
- **WHEN** the user starts the server on a specified port
- **THEN** the server answers HTTP requests on `/status` and `/swagger`.

#### Scenario: No MCP endpoint
- **GIVEN** a running server
- **WHEN** a client requests `/sse`
- **THEN** the server does not serve an MCP endpoint there.

#### Scenario: No .mcp.json and no /sse URL
- **WHEN** `easy-db-lab server` starts
- **THEN** it writes no `.mcp.json`
- **AND** its ready output names the `/status` and `/swagger` URLs and no `/sse` URL.

#### Scenario: No command carries the MCP annotation
- **WHEN** the CLI commands are listed
- **THEN** no command carries `@McpCommand`.

#### Scenario: Help text describes the REST server
- **WHEN** the user runs `easy-db-lab server --help`
- **THEN** the description names the status and API docs endpoints
- **AND** it does not tell the user to add an MCP server.

### Requirement: REST Status Endpoints

The server MUST expose REST HTTP endpoints for programmatic access to cluster status.

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

## REMOVED Requirements

### Requirement: Structured Event Streaming

**Reason**: The MCP part of `server` is removed.  The owner does not use it, and keeping each change compatible with it costs time.

**Migration**: Subscribe to the Redis channel that `EASY_DB_LAB_REDIS_URL` names.  Every command publishes its events there.

### Requirement: Optional Metrics Collection

**Reason**: The server's metrics collector is removed with the MCP part of `server`.  Its queries were wrong.

**Migration**: Run `easy-db-lab metrics stream` with `EASY_DB_LAB_REDIS_URL` set.
