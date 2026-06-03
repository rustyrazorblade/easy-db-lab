## Why

The old Kotlin-based ClickHouse implementation included backup, restore, and list-backups commands backed by native ClickHouse SQL (`BACKUP/RESTORE DATABASE ... TO/FROM Disk('s3_backup', ...)`). That implementation was removed when ClickHouse was migrated to the installer-based workload system. The functionality needs to be restored using the new workload model — and doing so requires the workload skill framework to formally support `backup` and `restore` as standard typed phases with positional argument pass-through.

## What Changes

- **`clickhouseinstallation.yaml.template`**: Add `s3_backup` disk configuration (`s3_plain` type, `use_environment_credentials`, pointing to the account bucket's `clickhouse-backups/` prefix via `__ACCOUNT_BUCKET__` and `__REGION__` template variables)
- **`TemplateVariables`**: Add `ACCOUNT_BUCKET` = `state.s3Bucket` (the account-level bucket that persists across cluster teardown, distinct from the per-cluster `BUCKET_NAME`)
- **`WorkloadInstallConfig`**: Add `backup` and `restore` as optional phase fields
- **`stepsForPhase()` / `collectTypedPhases()`**: Route and detect the two new phases
- **`WorkloadRunnerCommand`**: Add `@Parameters extraArgs` to accept positional arguments; inject first arg as `BACKUP_NAME` env var when phase is `backup` or `restore`
- **`WorkloadRunnerCommandFactory`**: Switch phase commands from `wrapWithoutInspection` to `forAnnotatedObject` so picocli sees `@Parameters`
- **`install/clickhouse/install.yaml`**: Declare `backup` and `restore` typed phases using `shell` steps that use `$BACKUP_NAME` and IAM-role-authenticated `Disk('s3_backup', ...)` SQL

## Capabilities

### New Capabilities

- `workload-arg-passthrough`: Workload phase commands accept positional arguments that are injected as env vars into step execution and shell scripts

### Modified Capabilities

- `clickhouse-backup-restore`: Requirements unchanged, but implementation moves from Kotlin service + PicoCLI commands to workload-system typed phases (`backup`, `restore`) in `install.yaml`
- `typed-install-steps`: Framework gains two new phase names (`backup`, `restore`) as standard lifecycle slots alongside `install`, `start`, `stop`, `uninstall`
- `clickhouse`: `clickhouseinstallation.yaml.template` gains the `s3_backup` disk that the backup/restore SQL depends on

## Impact

- `WorkloadRunnerCommand.kt` — adds `@Parameters`; logic to inject positional args as env vars
- `WorkloadRunnerCommandFactory.kt` — changes how `CommandSpec` is constructed for phase commands
- `WorkloadInstallConfig.kt` — two new fields
- `TemplateVariables.kt` — one new field and env var
- `clickhouseinstallation.yaml.template` — storage config section extended
- `install/clickhouse/install.yaml` — two new phases
- Tests: `WorkloadRunnerCommandTest` (new), `OtelManifestBuilderTest` (unchanged), `InstallTemplateResolverTest` (unchanged)
- `list-backups` is **out of scope** for this change — it requires S3 listing and formatted output that doesn't map to a simple typed phase
