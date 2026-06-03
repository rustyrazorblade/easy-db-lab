# ClickHouse Backup and Restore (delta)

Updates the backup/restore spec to reflect the workload-system implementation. Backup and restore are now typed phases in `install.yaml`, not Kotlin PicoCLI commands. Out-of-scope requirements from the original spec are removed pending future re-implementation.

## MODIFIED Requirements

### Requirement: REQ-CHBR-001: User Can Back Up a Running ClickHouse Cluster

The system SHALL back up the full schema and data of a running ClickHouse cluster to a named location in the account S3 bucket using ClickHouse's native BACKUP command, invoked via `kubectl exec` into a running ClickHouse pod.

#### Scenario: Backup executes native SQL via kubectl exec
- **WHEN** the user runs `easy-db-lab clickhouse backup <name>`
- **THEN** the system runs `BACKUP DATABASE default ON CLUSTER clickhouse TO Disk('s3_backup', '<name>/')` inside the ClickHouse pod
- **AND** the command exits zero on success

#### Scenario: Backup uses IAM credentials only
- **WHEN** a backup command executes
- **THEN** no AWS credentials appear in any command, environment variable, or log output
- **AND** the `s3_backup` disk uses `use_environment_credentials` to obtain credentials from the EC2 IAM role

### Requirement: REQ-CHBR-002: User Can Restore a ClickHouse Cluster from a Named Backup

The system SHALL restore schema and data into a running ClickHouse cluster from a named backup in the account S3 bucket, invoked via `kubectl exec` into a running ClickHouse pod.

#### Scenario: Restore executes native SQL via kubectl exec
- **WHEN** the user runs `easy-db-lab clickhouse restore <name>`
- **THEN** the system runs `RESTORE DATABASE default ON CLUSTER clickhouse FROM Disk('s3_backup', '<name>/')` inside the ClickHouse pod
- **AND** the command exits zero on success

#### Scenario: Restore uses IAM credentials only
- **WHEN** a restore command executes
- **THEN** no AWS credentials appear in any command, environment variable, or log output

### Requirement: REQ-CHBR-006: Backup S3 Path Is Decoupled from Cluster Lifecycle

Backups SHALL be stored under `<account-bucket>/clickhouse-backups/<name>/`, where `account-bucket` is the account-level S3 bucket (`state.s3Bucket`), independent of any per-cluster prefix. Backups SHALL persist across cluster teardown.

#### Scenario: Backup survives cluster teardown
- **WHEN** a cluster is torn down after a successful backup
- **THEN** the backup data remains accessible in the account bucket under `clickhouse-backups/<name>/`

#### Scenario: ACCOUNT_BUCKET template variable used for s3_backup disk endpoint
- **WHEN** the ClickHouse installation template is rendered
- **THEN** the `s3_backup` disk endpoint uses `ACCOUNT_BUCKET` (account-level bucket) not `BUCKET_NAME` (per-cluster data bucket)

## REMOVED Requirements

### Requirement: REQ-CHBR-001 (partial): backup-already-exists check and metadata sidecar
**Reason**: The workload-system shell step does not perform S3 pre-flight checks or write metadata JSON. The native ClickHouse `BACKUP` command fails on its own if a backup already exists at the same path.
**Migration**: No migration needed — this is a lab tool with ephemeral clusters.

### Requirement: REQ-CHBR-003: User Can Restore on Cluster Startup
**Reason**: Out of scope for this change. `--restore-from` on `clickhouse start` requires parameterized start phase support not addressed here.
**Migration**: Run `easy-db-lab clickhouse restore <name>` manually after `clickhouse start`.

### Requirement: REQ-CHBR-004: User Can List Available Backups
**Reason**: Out of scope for this change. `list-backups` requires S3 listing and formatted output that does not map to a typed phase.
**Migration**: Use `aws s3 ls s3://<account-bucket>/clickhouse-backups/` directly.

### Requirement: REQ-CHBR-005: User Can Back Up Before Cluster Teardown
**Reason**: Out of scope for this change. The `--clickhouse.backup` flag on `down` was removed with the old Kotlin implementation.
**Migration**: Run `easy-db-lab clickhouse backup <name>` before running `easy-db-lab down`.
