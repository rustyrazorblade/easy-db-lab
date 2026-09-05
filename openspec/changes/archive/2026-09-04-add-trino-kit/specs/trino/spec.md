## ADDED Requirements

### Requirement: Kit Lifecycle
The system MUST support installing, starting, stopping, and uninstalling Trino via the kit mechanism. Trino deploys via the `trinodb/trino` Helm chart onto app nodes.

#### Scenario: Install and start Trino
- **WHEN** the user runs `kit install trino` and then `trino start`
- **THEN** Trino is deployed via Helm on app nodes with the requested worker count

#### Scenario: Stop Trino
- **WHEN** the user runs `trino stop`
- **THEN** the Trino coordinator and worker deployments are scaled to zero replicas

#### Scenario: Restart Trino after stop
- **WHEN** the user runs `trino start` after a prior stop
- **THEN** Trino is re-deployed without requiring re-installation

#### Scenario: No app nodes
- **WHEN** the user runs `kit install trino` on a cluster with no app nodes
- **THEN** an error is emitted and installation is aborted

### Requirement: Version Selection
The Trino kit MUST accept a `--version` flag at install time to select the Trino release to deploy.

#### Scenario: Custom version
- **WHEN** the user runs `kit install trino --version 469`
- **THEN** the deployed Trino image uses version 469

#### Scenario: Default version
- **WHEN** the user runs `kit install trino` without `--version`
- **THEN** the kit deploys a pinned default Trino version

### Requirement: Catalog Connector Management
The Trino kit MUST automatically update its catalog configuration when other database kits start or stop, so Trino can query those databases without manual reconfiguration.

#### Scenario: Cassandra catalog added on start
- **WHEN** a Cassandra workload is running and Trino starts (or Trino is running and Cassandra starts)
- **THEN** Trino's Helm release is upgraded with a Cassandra catalog entry using `connector.name=cassandra`

#### Scenario: ClickHouse catalog added on start
- **WHEN** a ClickHouse workload is running and Trino starts (or Trino is running and ClickHouse starts)
- **THEN** Trino's Helm release is upgraded with a ClickHouse catalog entry using `connector.name=clickhouse`

#### Scenario: Catalog removed on stop
- **WHEN** a database workload stops while Trino is running
- **THEN** Trino's Helm release is upgraded removing that workload's catalog entry

#### Scenario: Custom workload catalog
- **WHEN** a custom workload directory contains a `trino-catalog.properties` file
- **THEN** that file is included as a catalog entry in the next Helm upgrade

### Requirement: SQL Execution
The `trino sql` command SHALL execute SQL statements against a running Trino cluster. SQL execution is provided via the `sql` capability declared in `trino/kit.yaml`.

The Trino JDBC driver (`io.trino:trino-jdbc`) MUST be declared as a dependency and force-loaded via `driver-class: io.trino.jdbc.TrinoDriver` in the capability declaration.

#### Scenario: Inline SQL
- **WHEN** the user runs `trino sql "SELECT count(*) FROM cassandra.keyspace.table"`
- **THEN** the query is submitted via JDBC and results are displayed in tabular format

#### Scenario: SQL from file
- **WHEN** the user runs `trino sql --file query.sql`
- **THEN** the SQL from the file is executed against the Trino cluster

#### Scenario: No app nodes
- **WHEN** no app nodes exist in cluster state
- **THEN** an error is emitted before any connection is made

### Requirement: Pyroscope Profiling
Trino coordinator and worker deployments SHALL be patched after Helm install to attach the Pyroscope Java profiling agent, enabling continuous profiling in Grafana.

#### Scenario: Profiling agent attached
- **WHEN** Trino starts successfully
- **THEN** both coordinator and worker pods have the Pyroscope agent injected via `JAVA_TOOL_OPTIONS` and the `/usr/local/pyroscope` host-path volume is mounted
