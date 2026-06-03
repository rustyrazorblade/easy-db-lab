## Context

The workload skill framework (`WorkloadRunnerCommand` + `WorkloadInstallConfig`) currently supports four lifecycle phases: `install`, `start`, `stop`, `uninstall`. Typed phases are declared in `install.yaml`; shell scripts in `bin/` are auto-discovered. Neither path supports positional arguments — scripts receive only env vars.

ClickHouse backup and restore require a backup name argument (e.g., `easy-db-lab clickhouse backup my-snapshot`). Backup uses ClickHouse's native SQL `BACKUP DATABASE default ON CLUSTER clickhouse TO Disk('s3_backup', '<name>/')`, which references an `s3_backup` disk pre-configured with IAM role credentials. The current `clickhouseinstallation.yaml.template` does not declare this disk.

Backups are stored at `s3://<account-bucket>/clickhouse-backups/<name>/`. The account bucket (`state.s3Bucket`) is used — not the per-cluster data bucket — so backups outlive cluster teardown.

## Goals / Non-Goals

**Goals:**
- Restore backup and restore commands for ClickHouse with no credential regression
- Generalize the workload framework to support positional args for any workload phase
- Declare `backup` and `restore` as standard phase slots in `WorkloadInstallConfig`
- Configure the `s3_backup` disk in the ClickHouse installation template using IAM credentials only

**Non-Goals:**
- `list-backups` command (requires S3 listing + formatted output; not a typed-phase fit)
- `--restore-from` flag on `clickhouse start` (was REQ-CHBR-003; out of scope)
- Backup metadata JSON sidecar (was written by old Kotlin service; not reproduced here)
- Making arg pass-through available to typed steps other than `shell` (not needed now)

## Decisions

### 1. Positional args injected as env vars, not forwarded as shell `$@`

**Decision**: `WorkloadRunnerCommand` captures extra positional args via `@Parameters`. The first arg is injected as `BACKUP_NAME` into the env var map passed to both `executeScript()` and `executeTypedPhase()`. Shell steps in `install.yaml` use `$BACKUP_NAME`.

**Why not forward as `$@` to scripts?** Typed phase `Shell` steps execute their `script` string via a shell subprocess (`bash -c`), not as a named file. There's no clean way to pass `$@` to an inline script. Env var injection works uniformly for both typed phases and shell script files.

**Alternative considered**: A named `--backup-name` option on `WorkloadRunnerCommand`. Rejected — positional is ergonomically cleaner for a required argument, consistent with how CLI tools like `git` and `kubectl` work.

### 2. `forAnnotatedObject` instead of `wrapWithoutInspection` for phase commands

**Decision**: `WorkloadRunnerCommandFactory.buildPhaseCommand()` switches from `CommandSpec.wrapWithoutInspection(command)` to `CommandSpec.forAnnotatedObject(command, CommandLine.defaultFactory())`.

**Why**: `wrapWithoutInspection` intentionally skips annotation scanning, so `@Parameters` on `WorkloadRunnerCommand` is invisible to picocli. `forAnnotatedObject` enables annotation scanning while still allowing manual spec customization afterward.

**Risk**: `PicoBaseCommand` must not have picocli field annotations that conflict. Verified: it uses Koin `by inject()` — no picocli annotations on fields.

### 3. `s3_backup` disk declared in `clickhouseinstallation.yaml.template` using `use_environment_credentials`

**Decision**: The `s3_backup` disk is defined directly in the CHI template under `config.d/storage.xml`, using `use_environment_credentials>1</use_environment_credentials` and `__ACCOUNT_BUCKET__` / `__REGION__` template variables.

**Why not a separate ConfigMap step at start time?** The old approach required a dedicated `createClickHouseS3ConfigMap` K8s call. With the new Altinity operator CHI model, embedding the disk config in the CHI spec is simpler, declarative, and doesn't require a separate install step. The template variables are already substituted at `start` time.

**Why `s3_plain` type?** `s3_plain` is the correct ClickHouse disk type for `BACKUP/RESTORE` operations. The regular `s3` type is for storage tiering, not backup destinations.

### 4. `ACCOUNT_BUCKET` as a distinct template variable

**Decision**: Add `accountBucket = state.s3Bucket` to `TemplateVariables`, exposed as `ACCOUNT_BUCKET` in the env var map.

**Why not reuse `BUCKET_NAME`?** `BUCKET_NAME` resolves to `dataBucket` (per-cluster) when available. Backups must use the account-level bucket to survive cluster teardown (REQ-CHBR-006). Conflating the two would silently break backup persistence.

### 5. Backup/restore declared as typed phases with a `shell` step

**Decision**: `install/clickhouse/install.yaml` declares `backup` and `restore` as typed phases, each containing a single `shell` step that runs `kubectl exec ... clickhouse-client --query "BACKUP/RESTORE ..."`.

**Why typed phases over `bin/` shell script files?** The user explicitly asked for typed phases to standardize the convention across workloads. Any future workload can declare backup/restore in its `install.yaml` without adding scripts to `bin/`.

## Risks / Trade-offs

**[Risk] Pod name is dynamic**: The `kubectl exec` target pod (`chi-clickhouse-clickhouse-0-0-0`) must be discovered at runtime. → Mitigation: use `kubectl get pods -l clickhouse.altinity.com/chi=clickhouse -o name | head -1` in the shell step.

**[Risk] `@Parameters` may interfere with existing phase commands**: Adding `@Parameters(arity = "0..*")` means all phase commands (including `start`, `stop`) now accept extra args without error. → Acceptable — extra args are silently available as env vars; existing phases ignore them.

**[Risk] IAM role must have S3 write access to the account bucket**: The EC2 instance profile needs `s3:PutObject` on `<account-bucket>/clickhouse-backups/*`. This is an infrastructure concern, not a code concern. → Document in user-facing docs.
