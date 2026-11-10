# Server

## Purpose

The server command (`easy-db-lab server`) provides a hybrid HTTP server exposing cluster management capabilities via MCP (Model Context Protocol) for AI assistants, REST endpoints for programmatic access, and background services for status caching and metrics collection.
## Requirements
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

### Requirement: Background Status Cache

The server MUST maintain a background cache of cluster status that refreshes periodically.

#### Scenario: Cache refreshes on interval
- **GIVEN** a running server
- **WHEN** the refresh interval elapses
- **THEN** the StatusCache refreshes cluster data from EC2, K3s, EMR, and other sources.

#### Scenario: Cached response served immediately
- **GIVEN** a running server
- **WHEN** a client queries /status without ?live=true
- **THEN** the server responds immediately from the in-memory cache.

### Requirement: Auto-shutdown CLI option
The server command SHALL accept an `--auto-shutdown` flag that enables infrastructure watchdog behavior.

#### Scenario: Flag not provided
- **WHEN** the user starts the server without `--auto-shutdown`
- **THEN** no watchdog is started and the server runs indefinitely

#### Scenario: Flag provided
- **WHEN** the user starts the server with `--auto-shutdown`
- **THEN** the infrastructure watchdog service is started as a background service

### Requirement: Status lists the endpoints of each running kit

The `status` command and the server's status (REST `GET /status` and the MCP status) SHALL list, for each installed kit recorded as running, the endpoints that the kit's `kit.yaml` declares.  Each endpoint SHALL be its NodePort on each host of the endpoint's node type, at the host's private IP.  A stopped kit SHALL show no endpoints, even when other pods of the kit still run.  No kit SHALL have a fixed section of its own.  In the server's status, `accessInfo.kits` SHALL hold these endpoints, one entry per running kit, and SHALL replace the fixed `clickhouse` entry.  If a running kit's `kit.yaml` cannot be read, `status` SHALL print `(endpoints unavailable: cannot read kit.yaml)` under the kit and SHALL render the later sections, and the kit's `accessInfo.kits` entry SHALL carry the same reason in `endpointsUnavailable` with an empty endpoint list.

#### Scenario: A running kit shows its NodePort endpoints

- **WHEN** the clickhouse kit is running and the user runs `status`
- **THEN** the `=== KITS ===` section marks clickhouse as running
- **AND** under it, `status` prints the NodePort endpoints that its `kit.yaml` declares, on each host of the endpoint's node type

#### Scenario: A stopped kit shows no endpoints

- **WHEN** the clickhouse kit is installed but not running, and its Keeper pods still run
- **THEN** `status` lists clickhouse as not running, with no endpoints
- **AND** `status` prints no ClickHouse section

#### Scenario: The server's status lists running kits

- **WHEN** a client sends `GET /status` while one kit is running and another is stopped
- **THEN** `accessInfo.kits` holds one entry, for the running kit, with its name and each endpoint's name, type and address
- **AND** `accessInfo` has no `clickhouse` entry

#### Scenario: A running kit's kit.yaml cannot be read

- **WHEN** a kit is recorded as running and its `kit.yaml` cannot be read
- **THEN** `status` prints `(endpoints unavailable: cannot read kit.yaml)` under the kit
- **AND** `status` renders the sections after the kits
- **AND** the kit's `accessInfo.kits` entry has an empty endpoint list and `endpointsUnavailable` set to `cannot read kit.yaml`

