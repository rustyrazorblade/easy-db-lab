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

The system MUST support starting, stopping, and checking status of ClickHouse deployments.

**Scenarios:**

- **GIVEN** a deployed ClickHouse cluster, **WHEN** the user stops it, **THEN** the K8s resources are removed.
- **GIVEN** a running ClickHouse cluster, **WHEN** the user checks status, **THEN** the deployment state is displayed.

### REQ-CH-004: S3 Backup Disk Configured at Startup

The system SHALL configure an `s3_backup` disk of type `s3_plain` in the ClickHouse config at `clickhouse start` time, pointing to the account bucket's `clickhouse-backups/` prefix, using IAM role credentials.

**Scenarios:**

- **WHEN** the user runs `clickhouse start`, **THEN** the system injects `CLICKHOUSE_BACKUP_S3_ENDPOINT` into the ClickHouse ConfigMap, pointing to `https://<account-bucket>.s3.<region>.amazonaws.com/clickhouse-backups/`
- **WHEN** a ClickHouse pod has the `s3_backup` disk configured, **THEN** `BACKUP DATABASE default TO Disk('s3_backup', 'name/')` executes without explicit credentials, using the EC2 IAM role
