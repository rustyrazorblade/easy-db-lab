# ClickHouse

Manages ClickHouse deployment on K3s with sharding, replication, and S3 storage integration.

## Requirements

### REQ-CH-001: K8s-Based Deployment

The system MUST deploy ClickHouse clusters via Kubernetes with configurable sharding and replication.

**Scenarios:**

- **GIVEN** a running cluster with K3s, **WHEN** the user initializes and starts ClickHouse, **THEN** a sharded ClickHouse cluster is deployed with distributed tables.
- **GIVEN** ClickHouse configuration, **WHEN** the user specifies a replica count per shard, **THEN** the deployment creates the requested number of replicas.

### REQ-CH-002: S3 Storage Integration

The system MUST use the per-cluster data bucket for ClickHouse S3 storage.

**Scenarios:**

- **GIVEN** a provisioned cluster, **WHEN** ClickHouse S3 storage is configured, **THEN** the S3 endpoint points to the per-cluster data bucket.
- **GIVEN** S3 cache options, **WHEN** the user enables S3 caching, **THEN** ClickHouse caches S3 data locally for faster reads.

### REQ-CH-003: Lifecycle Management

The system MUST support installing, starting, stopping, and uninstalling ClickHouse deployments. Stopping MUST preserve data; data is only deleted on uninstall. Starting after a stop MUST resume the existing dataset without any additional user steps.

**Scenarios:**

- **WHEN** the user runs `clickhouse stop`, **THEN** the ClickHouseInstallation and NodePort service are deleted, but PVs and on-disk data remain.
- **WHEN** data is written to ClickHouse, `clickhouse stop` is run, and then `clickhouse start` is run again, **THEN** the previously written data is accessible without any restore step.
- **WHEN** `clickhouse start` is run after a fresh install with no prior starts, **THEN** PVs are created and the ClickHouseInstallation is deployed successfully.
- **GIVEN** a running ClickHouse cluster, **WHEN** the user checks status, **THEN** the deployment state is displayed.

### REQ-CH-004: S3 Backup Disk Configured at Startup

The system SHALL configure an `s3_backup` disk of type `s3_plain` in the ClickHouse config at `clickhouse start` time, pointing to the account bucket's `clickhouse-backups/` prefix, using IAM role credentials.

**Scenarios:**

- **WHEN** the user runs `clickhouse start`, **THEN** the system injects `CLICKHOUSE_BACKUP_S3_ENDPOINT` into the ClickHouse ConfigMap, pointing to `https://<account-bucket>.s3.<region>.amazonaws.com/clickhouse-backups/`
- **WHEN** a ClickHouse pod has the `s3_backup` disk configured, **THEN** `BACKUP DATABASE default TO Disk('s3_backup', 'name/')` executes without explicit credentials, using the EC2 IAM role

### REQ-CH-005: SQL Execution

The `clickhouse sql` command SHALL execute SQL statements against a running ClickHouse cluster.
SQL execution is provided via the `sql` capability declared in `clickhouse/kit.yaml` — see REQ-KCAP-002.

**Scenarios:**

- **WHEN** the user runs `clickhouse sql "SELECT count(*) FROM default.events"`,
  **THEN** the query is submitted via JDBC and results are displayed in tabular format.
- **WHEN** the user runs `clickhouse sql --file query.sql`, **THEN** the SQL from the file is executed.
- **WHEN** no db nodes exist in cluster state, **THEN** an error is emitted before any connection is made.

### REQ-CH-006: Version Selection

The ClickHouse kit MUST accept a `--version` flag at install time to select the ClickHouse server image version. The flag MUST NOT use the prefix form `--clickhouse-version`.

**Scenarios:**

- **WHEN** the user runs `kit install clickhouse --version 25.4`, **THEN** the deployed ClickHouse uses image version 25.4.
- **WHEN** the user runs `kit install clickhouse` without `--version`, **THEN** the kit deploys with the default version (`latest`).
