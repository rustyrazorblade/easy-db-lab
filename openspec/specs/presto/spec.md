# Presto

Manages the Presto kit lifecycle and provides SQL execution against a running Presto cluster.

## Requirements

### REQ-PRS-001: Kit Lifecycle

The system MUST support installing, starting, stopping, and uninstalling Presto via the kit mechanism.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user runs `kit install presto` and `presto start`, **THEN** Presto is deployed via Helm on app nodes with the requested worker count.
- **WHEN** the user runs `presto stop`, **THEN** the Presto Helm release is removed.
- **WHEN** the user runs `presto start` after a stop, **THEN** Presto is re-deployed without requiring re-installation.

### REQ-PRS-002: SQL Execution

The `presto sql` command SHALL execute SQL statements against a running Presto cluster.
SQL execution is provided via the `sql` capability declared in `presto/kit.yaml` — see REQ-KCAP-002.

The Presto JDBC driver (`com.facebook.presto:presto-jdbc`) does not auto-register via
ServiceLoader in fat-JAR environments. The `driver-class` field in the `sql` capability
SHALL be set to `com.facebook.presto.jdbc.PrestoDriver` to force-load it.

**Scenarios:**

- **WHEN** the user runs `presto sql "SELECT count(*) FROM cassandra.keyspace.table"`,
  **THEN** the query is submitted via JDBC and results are displayed in tabular format.
- **WHEN** the user runs `presto sql --file query.sql`, **THEN** the SQL from the file is executed.
- **WHEN** no app nodes exist in cluster state, **THEN** an error is emitted before any connection is made.
