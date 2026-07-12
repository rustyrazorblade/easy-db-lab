# ClickHouse Backup and Restore

## Purpose

Covers backup, restore, and list-backups commands for ClickHouse clusters using native BACKUP/RESTORE SQL and S3 storage.

## Requirements

### REQ-CHBR-001: User Can Back Up a Running ClickHouse Cluster

The system SHALL back up the full schema and data of a running ClickHouse cluster to a named location in the account S3 bucket using ClickHouse's native BACKUP command.

#### Scenario: Backup a running cluster

- **WHEN** the user runs `clickhouse backup <name>` against a running cluster
- **THEN** the system executes `BACKUP DATABASE default ON CLUSTER easy_db_lab TO Disk('s3_backup', '<name>/')` and emits a success event with the backup name and S3 location.

#### Scenario: Backup name already exists

- **WHEN** the user runs `clickhouse backup <name>` and a backup with that name already exists
- **THEN** the system fails fast with a clear error message before executing the BACKUP command.

#### Scenario: Backup writes metadata sidecar

- **WHEN** a backup completes successfully
- **THEN** the system writes a `backup-metadata.json` sidecar at `clickhouse-backups/<name>/backup-metadata.json` containing backupName, timestamp, sourceCluster, and totalSizeBytes.

### REQ-CHBR-002: User Can Restore a ClickHouse Cluster from a Named Backup

The system SHALL restore schema and data into a running ClickHouse cluster from a named backup in the account S3 bucket.

#### Scenario: Restore from a named backup

- **WHEN** the user runs `clickhouse restore <name>` against a running cluster
- **THEN** the system executes `RESTORE DATABASE default ON CLUSTER easy_db_lab FROM Disk('s3_backup', '<name>/')` and emits a success event.

#### Scenario: Restore from a missing backup

- **WHEN** the user runs `clickhouse restore <name>` and no backup with that name exists
- **THEN** the system fails with a clear error message.

### REQ-CHBR-003: User Can Restore on Cluster Startup

The system SHALL support restoring from a named backup immediately after `clickhouse start` completes.

#### Scenario: Restore after start

- **WHEN** the user runs `clickhouse start --restore-from <name>`
- **THEN** the system starts the ClickHouse cluster and, once pods are ready, automatically runs `clickhouse restore <name>`.

### REQ-CHBR-004: User Can List Available Backups

The system SHALL list all named backups in the account S3 bucket with their metadata.

#### Scenario: List existing backups

- **WHEN** the user runs `clickhouse list-backups`
- **THEN** the system scans `clickhouse-backups/` in the account bucket and displays each backup's name, timestamp, size, and source cluster.

#### Scenario: List when no backups exist

- **WHEN** the user runs `clickhouse list-backups` and no backups exist
- **THEN** the system emits an event indicating no backups were found.

### REQ-CHBR-005: User Can Back Up Before Cluster Teardown

The system SHALL support triggering a ClickHouse backup as part of the `down` command before AWS infrastructure is torn down.

#### Scenario: Backup during teardown

- **WHEN** the user runs `down --clickhouse.backup <name>`
- **THEN** the system performs the ClickHouse backup before beginning AWS teardown, and fails the teardown if the backup fails.

### REQ-CHBR-006: Backup S3 Path Is Decoupled from Cluster Lifecycle

Backups SHALL be stored at `s3://<account-bucket>/clickhouse-backups/<name>/`, independent of any cluster prefix, so they persist across cluster runs.

#### Scenario: Backup persists across cluster teardown

- **WHEN** a cluster is torn down after a backup
- **THEN** the backup remains accessible in the account bucket under `clickhouse-backups/<name>/`.
