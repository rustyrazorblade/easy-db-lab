## 1. Template Variables

- [x] 1.1 Add `accountBucket: String` field to `TemplateVariables` data class, set from `state.s3Bucket`
- [x] 1.2 Add `"ACCOUNT_BUCKET" to accountBucket` entry to `TemplateVariables.toMap()`
- [x] 1.3 Update `TemplateVariables.from()` to populate `accountBucket`

## 2. ClickHouse Installation Template

- [x] 2.1 Add `s3_backup` disk block to `clickhouseinstallation.yaml.template` under `config.d/storage.xml` using `s3_plain` type, `use_environment_credentials>1`, and `__ACCOUNT_BUCKET__`/`__REGION__` placeholders
- [x] 2.2 Add `s3_backup` to the storage policy in the template so it's available for BACKUP SQL

## 3. Workload Arg Pass-Through (Framework)

- [x] 3.1 Add `@Parameters(arity = "0..*", hidden = true) var extraArgs: List<String> = emptyList()` to `WorkloadRunnerCommand`
- [x] 3.2 In `WorkloadRunnerCommand.execute()`, build an augmented env var map: if `extraArgs` is non-empty, add `"BACKUP_NAME" to extraArgs[0]`
- [x] 3.3 Pass the augmented map to both `executeTypedPhase()` and `executeScript()`
- [x] 3.4 In `WorkloadRunnerCommandFactory.buildPhaseCommand()`, switch from `CommandSpec.wrapWithoutInspection(command)` to `CommandSpec.forAnnotatedObject(command, CommandLine.defaultFactory())` so picocli scans `@Parameters`

## 4. WorkloadInstallConfig — backup/restore Phases

- [x] 4.1 Add `val backup: List<InstallStep> = emptyList()` and `val restore: List<InstallStep> = emptyList()` fields to `WorkloadInstallConfig`
- [x] 4.2 Update `stepsForPhase()` extension function to route `"backup"` → `backup` and `"restore"` → `restore`
- [x] 4.3 Update `collectTypedPhases()` in `WorkloadRunnerCommandFactory` to detect non-empty `backup` and `restore` lists

## 5. ClickHouse install.yaml — Declare Backup/Restore Phases

- [x] 5.1 Add a `backup` typed phase to `install/clickhouse/install.yaml` using a `shell` step that: discovers the primary ClickHouse pod via `kubectl get pods`, then runs `kubectl exec ... clickhouse-client --query "BACKUP DATABASE default ON CLUSTER clickhouse TO Disk('s3_backup', '$BACKUP_NAME/')"`
- [x] 5.2 Add a `restore` typed phase to `install/clickhouse/install.yaml` using a `shell` step that: discovers the primary ClickHouse pod, then runs `kubectl exec ... clickhouse-client --query "RESTORE DATABASE default ON CLUSTER clickhouse FROM Disk('s3_backup', '$BACKUP_NAME/')"`
- [x] 5.3 Validate both shell steps use `KUBECONFIG` env var for kubectl access

## 6. Tests

- [x] 6.1 Add unit test to `WorkloadRunnerCommandTest` (or new file): verify that `extraArgs[0]` is injected as `BACKUP_NAME` in the env var map
- [x] 6.2 Add unit test: verify that phases with no extra args do not inject `BACKUP_NAME`
- [x] 6.3 Add test to `WorkloadInstallConfigTest` (or existing): verify `backup` and `restore` round-trip through YAML deserialization
- [x] 6.4 Add test to `WorkloadRunnerCommandFactoryTest`: verify `backup` and `restore` typed phases are registered as subcommands when present in `install.yaml`
- [x] 6.5 Verify `TemplateVariablesTest` covers `ACCOUNT_BUCKET` in `toMap()` output

## 7. Documentation

- [x] 7.1 Update `docs/user-guide/clickhouse-backup-restore.md` to document `clickhouse backup <name>` and `clickhouse restore <name>` usage
- [x] 7.2 Note IAM role requirement: EC2 instance profile must have `s3:PutObject`/`s3:GetObject` on `<account-bucket>/clickhouse-backups/*`
