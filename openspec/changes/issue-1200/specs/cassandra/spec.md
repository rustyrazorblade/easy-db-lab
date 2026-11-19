## MODIFIED Requirements

### Requirement: CQL Access

The system MUST provide CQL query execution against the cluster via the Java driver, routed through the network access layer (SOCKS proxy or Tailscale).  The rows a query returns MUST be printed as plain text with no tag, and MUST NOT be emitted as an event.

#### Scenario: Execute a CQL query

- **GIVEN** a running Cassandra cluster
- **WHEN** the user executes a CQL query
- **THEN** the query is routed through the available network path and results are displayed.

#### Scenario: CQL session reuse

- **GIVEN** the REPL or server is running
- **WHEN** multiple CQL queries are executed
- **THEN** the CQL session is reused across queries.

#### Scenario: Query rows are not an event

- **GIVEN** `EASY_DB_LAB_REDIS_URL` is set
- **WHEN** the user runs `cassandra cql "SELECT * FROM system.local"`
- **THEN** the rows are printed, and Redis receives no event holding them
