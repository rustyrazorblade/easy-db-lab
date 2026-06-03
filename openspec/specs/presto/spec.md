# Presto

Manages the Presto kit lifecycle and provides SQL execution against a running Presto cluster.

## Requirements

### REQ-PRS-001: Kit Lifecycle

The system MUST support installing, starting, stopping, and uninstalling Presto via the kit mechanism.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user runs `kit install presto` and `presto start`, **THEN** Presto is deployed via Helm on app nodes with the requested worker count.
- **WHEN** the user runs `presto stop`, **THEN** the Presto Helm release is removed.
- **WHEN** the user runs `presto start` after a stop, **THEN** Presto is re-deployed without requiring re-installation.

### REQ-PRS-002: SQL Execution via HTTP API

The `presto sql` command SHALL execute SQL statements against a running Presto cluster over the Presto HTTP API, using the app node's private IP.

**Scenarios:**

- **WHEN** the user runs `presto sql "SELECT count(*) FROM cassandra.keyspace.table"`, **THEN** the query is submitted to the Presto HTTP API and the results are displayed in a tabular format.
- **WHEN** the user runs `presto sql --file query.sql`, **THEN** the SQL from the specified local file is executed.
- **WHEN** the query returns multiple pages of results, **THEN** all pages are fetched via `nextUri` and combined before display.
- **WHEN** the query fails, **THEN** the Presto error message and error code are displayed.
- **WHEN** no app nodes exist in cluster state, **THEN** an error is emitted before any HTTP call is made.

### REQ-PRS-003: Connection Routing

The Presto SQL service SHALL route HTTP connections to the app node's private IP using the same proxy/direct mechanism as other cluster HTTP services.

**Scenarios:**

- **WHEN** Tailscale is not active, **THEN** the request is routed through the SOCKS5 proxy established via SSH tunnel to the control node.
- **WHEN** Tailscale is active (`tailscaleActive = true` in cluster state), **THEN** the request connects directly to the app node's private IP without a proxy.
