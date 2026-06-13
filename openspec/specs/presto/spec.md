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

### REQ-PRS-003: Catalog Sources

The system SHALL support automatic catalog injection for the following database kits when they are running alongside Presto: Cassandra, ClickHouse, and PostgreSQL.

A catalog properties file MUST exist in `kits/presto/catalogs/<kit-name>.properties.template` for each supported database kit. The `update-catalogs.sh` hook reads `RUNNING_KITS` and injects catalogs for any kit that has a matching properties file.

**Scenarios:**

- **GIVEN** both `cassandra` and `presto` are running, **WHEN** `update-catalogs.sh` executes, **THEN** a `cassandra` catalog is present in Presto.
- **GIVEN** both `clickhouse` and `presto` are running, **WHEN** `update-catalogs.sh` executes, **THEN** a `clickhouse` catalog is present in Presto.
- **GIVEN** both `postgres` and `presto` are running, **WHEN** `update-catalogs.sh` executes, **THEN** a `postgres` catalog is present in Presto using the `postgresql` connector pointed at `postgres-rw.default.svc.cluster.local:5432`.
- **WHEN** a database kit is not running, **THEN** its catalog is not injected into Presto.
