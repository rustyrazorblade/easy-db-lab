## 1. Configuration: S3 Backup Disk

- [x] 1.1 Add `s3_backup` disk (`type: s3_plain`, `endpoint from_env="CLICKHOUSE_BACKUP_S3_ENDPOINT"`, `use_environment_credentials`) and `<backups><allowed_disk>s3_backup</allowed_disk></backups>` to `config.xml`
- [x] 1.2 Add `clickhouseBackups()` extension to `ClusterS3PathExtensions.kt` returning a root-level (non-cluster-scoped) path on the account bucket: `s3://account-bucket/clickhouse-backups/`
- [x] 1.3 Add `CLICKHOUSE_BACKUP_S3_ENDPOINT` constant to `Constants.kt`
- [x] 1.4 Update `ClickHouseManifestBuilder` to inject `CLICKHOUSE_BACKUP_S3_ENDPOINT` into the ConfigMap env, pointing to `https://<account-bucket>.s3.<region>.amazonaws.com/clickhouse-backups/`

## 2. K8s Service: Pod Exec

- [x] 2.1 Add `execInPod(controlHost, namespace, podName, command): Result<String>` to `K8sService` interface and `DefaultK8sService` — executes a command in a running pod via kubectl exec and returns stdout

## 3. Events

- [x] 3.1 Add `Event.ClickHouse.Backup` sealed interface with typed events: `BackupStarting(name)`, `BackupComplete(name, s3Path)`, `BackupAlreadyExists(name)`, `RestoreStarting(name)`, `RestoreComplete(name)`, `BackupNotFound(name)`, `BackupListEmpty`, `BackupListEntry(name, timestamp, sourceCluster, sizeBytes)`

## 4. ClickHouseBackupService

- [x] 4.1 Define `ClickHouseBackupService` interface with methods: `backup(name)`, `restore(name)`, `listBackups()`
- [x] 4.2 Implement `DefaultClickHouseBackupService` — `backup()` checks for existing backup, executes BACKUP SQL via `K8sService.execInPod`, writes metadata sidecar to S3
- [x] 4.3 Implement `restore()` — checks backup exists, executes RESTORE SQL via `K8sService.execInPod`
- [x] 4.4 Implement `listBackups()` — scans `clickhouse-backups/` prefix in account bucket, reads each `backup-metadata.json` sidecar, returns sorted list
- [x] 4.5 Define `BackupMetadata` data class (kotlinx.serialization): `backupName`, `timestamp`, `sourceCluster`, `totalSizeBytes`
- [x] 4.6 Register `ClickHouseBackupService` in Koin `ServicesModule`

## 5. Commands

- [x] 5.1 Create `ClickHouseBackup` command — positional `name` param, delegates to `ClickHouseBackupService.backup(name)`
- [x] 5.2 Create `ClickHouseRestore` command — positional `name` param, delegates to `ClickHouseBackupService.restore(name)`
- [x] 5.3 Create `ClickHouseBackupsLs` command — delegates to `ClickHouseBackupService.listBackups()`, emits `BackupListEntry` per backup
- [x] 5.4 Create `ClickHouseBackups` parent command with `ClickHouseBackupsLs` as subcommand
- [x] 5.5 Update `ClickHouse.kt` to add `ClickHouseBackup`, `ClickHouseRestore`, `ClickHouseBackups` to subcommands list
- [x] 5.6 Add `--restore-from` option to `ClickHouseStart` — after cluster is ready, calls `ClickHouseBackupService.restore(restoreFrom)` if set
- [x] 5.7 Add `--clickhouse.backup` option to `Down` — runs backup before AWS teardown begins; fails teardown if backup fails

## 6. Tests

- [x] 6.1 Unit test `ClickHouseBackupService` — mock `K8sService.execInPod` and `ObjectStore`; test backup name collision check, metadata write, restore not-found error
- [x] 6.2 Unit test `ClickHouseManifestBuilder` — verify `CLICKHOUSE_BACKUP_S3_ENDPOINT` is present in generated ConfigMap with correct URL format
- [x] 6.3 Unit test `ClusterS3PathExtensions` — verify `clickhouseBackups()` produces a root-level path not prefixed with `clusters/`
- [x] 6.4 Unit test events — add `BackupStarting`, `BackupComplete`, `RestoreStarting`, `RestoreComplete` to `EventSerializationTest`

## 7. Documentation

- [x] 7.1 Add ClickHouse backup/restore section to `docs/` covering the full workflow: `clickhouse backup`, `clickhouse restore`, `list-backups`, `--restore-from`, and `down --clickhouse.backup`
