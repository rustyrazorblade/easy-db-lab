## ADDED Requirements

### Requirement: K8s-Based Deployment via CloudNativePG

The system MUST deploy PostgreSQL clusters on K8s db nodes via the CloudNativePG (CNPG) operator. The operator SHALL be installed via Helm (`cloudnative-pg/cloudnative-pg` chart into the `cnpg-system` namespace). The PostgreSQL cluster SHALL be defined as a CNPG `Cluster` custom resource named `postgres`.

#### Scenario: Fresh install and start

- **GIVEN** a running cluster with K3s, **WHEN** the user runs `kit install postgres` and `postgres start`, **THEN** the CNPG operator is installed and a PostgreSQL Cluster CR is deployed on db nodes.

#### Scenario: Operator already present

- **WHEN** the user runs `kit install postgres` on a cluster where CNPG is already installed, **THEN** the install succeeds without error.

### Requirement: Configurable Instance Count

The `postgres start` command SHALL accept a `--instances` argument (default: `1`) controlling how many PostgreSQL instances CNPG deploys. When `--instances` is greater than 1, CNPG deploys a primary and read replicas.

#### Scenario: Single instance default

- **WHEN** the user runs `postgres start` with no flags, **THEN** a single PostgreSQL instance is deployed.

#### Scenario: Multi-instance replication

- **WHEN** the user runs `postgres start --instances 3`, **THEN** CNPG deploys 1 primary and 2 read replicas.

### Requirement: Data Lifecycle

Stop MUST preserve data; data is only deleted on uninstall. Starting after a stop MUST resume the existing dataset without any additional user steps.

#### Scenario: Stop preserves data

- **WHEN** data is written to PostgreSQL, `postgres stop` is run, and then `postgres start` is run again, **THEN** the previously written data is accessible without any restore step.

#### Scenario: Uninstall cleans up

- **WHEN** the user runs `postgres uninstall`, **THEN** the CNPG operator is removed and all PersistentVolumes for db nodes are deleted.

#### Scenario: Fresh start creates storage

- **WHEN** `postgres start` is run after a fresh install with no prior starts, **THEN** PVs are created via `platform-pvs` and the Cluster CR is deployed successfully.

### Requirement: SQL Execution

The `postgres sql` command SHALL execute SQL statements against a running PostgreSQL cluster. SQL execution is provided via the `sql` capability declared in `postgres/kit.yaml` — see REQ-KCAP-002.

The PostgreSQL JDBC driver (`org.postgresql:postgresql`) auto-registers via ServiceLoader. The `driver-class` field in the `sql` capability SHALL be left empty.

#### Scenario: Query execution

- **WHEN** the user runs `postgres sql "SELECT version()"`, **THEN** the query is submitted via JDBC and results are displayed in tabular format.

#### Scenario: File-based query

- **WHEN** the user runs `postgres sql --file query.sql`, **THEN** the SQL from the file is executed.

#### Scenario: No db nodes

- **WHEN** no db nodes exist in cluster state, **THEN** an error is emitted before any connection is made.

### Requirement: Presto Integration

When both the `postgres` and `presto` kits are running, Presto SHALL automatically expose a `postgres` catalog using the `postgresql` connector, pointing to the CNPG primary service (`postgres-rw.default.svc.cluster.local:5432`).

#### Scenario: Catalog injection

- **GIVEN** both `postgres start` and `presto start` have been run, **WHEN** Presto's `update-catalogs.sh` hook executes, **THEN** a `postgres` catalog is present in Presto and `SHOW CATALOGS` includes `postgres`.

#### Scenario: Catalog absent without postgres

- **WHEN** only presto is running (no postgres kit), **THEN** no `postgres` catalog appears in Presto.
