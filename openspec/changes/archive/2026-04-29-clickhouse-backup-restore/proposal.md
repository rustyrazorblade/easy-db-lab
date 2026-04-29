## Why

ClickHouse clusters are ephemeral, but the data and schema loaded into them has value across runs. Without backup/restore, users must reload data from scratch every time they spin up a new cluster.

## What Changes

- New `clickhouse backup <name>` command — backs up full schema and data to the account S3 bucket
- New `clickhouse restore <name>` command — restores schema and data into a running ClickHouse cluster
- New `clickhouse list-backups` command — lists available backups with name, timestamp, size, and source cluster
- `clickhouse start` gains `--restore-from <name>` to restore immediately after cluster startup
- `down` gains `--clickhouse.backup <name>` to automatically backup before cluster teardown
- `config.xml` gains an `s3_backup` disk (`type: s3_plain`, `use_environment_credentials`) for IAM-authenticated backup operations
- `ClickHouseManifestBuilder` injects `CLICKHOUSE_BACKUP_S3_ENDPOINT` env var into the ConfigMap at `clickhouse start` time

## Capabilities

### New Capabilities

- `clickhouse-backup-restore`: Backup and restore ClickHouse cluster data (schema + data) to/from named locations in the account S3 bucket, decoupled from cluster lifecycle.

### Modified Capabilities

- `clickhouse`: Adds backup/restore lifecycle commands and `--restore-from` flag to `start`; adds backup disk configuration at startup.

## Impact

**Code:**
- `src/main/resources/.../clickhouse/config.xml` — add `s3_backup` disk and `<backups><allowed_disk>` section
- `src/main/kotlin/.../configuration/clickhouse/ClickHouseManifestBuilder.kt` — inject `CLICKHOUSE_BACKUP_S3_ENDPOINT`
- `src/main/kotlin/.../commands/clickhouse/` — new commands: `ClickHouseBackup`, `ClickHouseRestore`, `ClickHouseListBackups`; update `ClickHouse.kt`, `ClickHouseStart.kt`
- `src/main/kotlin/.../commands/Down.kt` — add `--clickhouse.backup` option
- `src/main/kotlin/.../services/ClickHouseBackupService.kt` — new service for backup SQL execution and S3 metadata
- `src/main/kotlin/.../services/K8sService.kt` — add pod exec method
- `src/main/kotlin/.../events/Event.kt` — new `Event.ClickHouse.Backup.*` events
- `src/main/kotlin/.../configuration/ClusterS3PathExtensions.kt` — add `clickhouseBackups()` root-level path

**Dependencies:** None new — uses existing AWS SDK, K8s client, and S3 infrastructure.

**Documentation:** New section in ClickHouse docs covering backup/restore workflow.
