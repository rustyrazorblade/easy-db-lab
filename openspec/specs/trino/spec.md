# Trino

Manages the Trino kit lifecycle, catalog connector management, and SQL execution against a running Trino cluster.

## Requirements

### REQ-TRN-001: Kit Lifecycle

The system MUST support installing, starting, stopping, and uninstalling Trino via the kit mechanism. Trino deploys via the `trinodb/trino` Helm chart onto app nodes.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user runs `kit install trino` and then `trino start`, **THEN** Trino is deployed via Helm on app nodes with the requested worker count.
- **WHEN** the user runs `trino stop`, **THEN** the Trino coordinator and worker deployments are scaled to zero replicas.
- **WHEN** the user runs `trino start` after a prior stop, **THEN** Trino is re-deployed without requiring re-installation.
- **WHEN** the user runs `kit install trino` on a cluster with no app nodes, **THEN** an error is emitted and installation is aborted.

### REQ-TRN-002: Version Selection

The Trino kit MUST accept a `--version` flag at install time to select the Trino release to deploy.

**Scenarios:**

- **WHEN** the user runs `kit install trino --version 469`, **THEN** the deployed Trino image uses version 469.
- **WHEN** the user runs `kit install trino` without `--version`, **THEN** the kit deploys the default pinned Trino version.

### REQ-TRN-003: Catalog Connector Management

The Trino kit MUST automatically update its catalog configuration when other database kits start or stop, so Trino can query those databases without manual reconfiguration.

**Scenarios:**

- **WHEN** a Cassandra workload is running and Trino starts (or Trino is running and Cassandra starts), **THEN** Trino's Helm release is upgraded with a Cassandra catalog using `connector.name=cassandra`.
- **WHEN** a ClickHouse workload is running and Trino starts (or Trino is running and ClickHouse starts), **THEN** Trino's Helm release is upgraded with a ClickHouse catalog using `connector.name=clickhouse`.
- **WHEN** a database workload stops while Trino is running, **THEN** Trino's Helm release is upgraded removing that workload's catalog entry.
- **WHEN** a custom workload directory contains a `trino-catalog.properties` file, **THEN** that file is included as a catalog entry in the next Helm upgrade.

### REQ-TRN-004: SQL Execution

The `trino sql` command SHALL execute SQL statements against a running Trino cluster. SQL execution is provided via the `sql` capability declared in `trino/kit.yaml`.

The Trino JDBC driver (`io.trino:trino-jdbc`) MUST be declared as a Gradle dependency and force-loaded via `driver-class: io.trino.jdbc.TrinoDriver` in the capability declaration.

**Scenarios:**

- **WHEN** the user runs `trino sql "SELECT count(*) FROM cassandra.keyspace.table"`, **THEN** the query is submitted via JDBC and results are displayed in tabular format.
- **WHEN** the user runs `trino sql --file query.sql`, **THEN** the SQL from the file is executed against the Trino cluster.
- **WHEN** no app nodes exist in cluster state, **THEN** an error is emitted before any connection is made.

### REQ-TRN-005: Pyroscope Profiling

Trino coordinator and worker deployments SHALL be patched after Helm install to attach the Pyroscope Java profiling agent.

**Scenarios:**

- **WHEN** Trino starts successfully, **THEN** both coordinator and worker pods have the Pyroscope agent injected via `JAVA_TOOL_OPTIONS` and the `/usr/local/pyroscope` host-path volume is mounted.
