## MODIFIED Requirements

### Requirement: REQ-SERVER-003: REST Status Endpoints

The server MUST expose REST HTTP endpoints for programmatic access to cluster status, independent of the MCP protocol.

**Scenarios:**

- **GIVEN** a running server, **WHEN** a client sends GET /status, **THEN** the server returns JSON with cluster info, EC2 instances, K8s pods, networking, security groups, EMR, OpenSearch, S3, Cassandra version, and access URLs.
- **GIVEN** a running server, **WHEN** a client sends GET /status?section=nodes, **THEN** the server returns only the nodes section of the status response.
- **GIVEN** a running server, **WHEN** a client sends GET /status?live=true, **THEN** the server bypasses the cache and fetches fresh data before responding.
- **GIVEN** a running cluster with a reachable control node, **WHEN** a client sends GET /status, **THEN** the `accessInfo.observability` object SHALL include `tempo` and `pyroscope` URL fields alongside `grafana`, `victoriaMetrics`, and `victoriaLogs`.

#### Scenario: Observability URLs include Tempo and Pyroscope
- **WHEN** a client requests GET /status on a cluster with a reachable control node
- **THEN** the response `accessInfo.observability` object contains `tempo` at `http://<controlPrivateIp>:3200` and `pyroscope` at `http://<controlPrivateIp>:4040`
