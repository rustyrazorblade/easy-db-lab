## Context

ClickHouse clusters in easy-db-lab are ephemeral: spun up, used for testing, torn down. Users want to save cluster state (schema + data) between runs without reloading from scratch. The account S3 bucket is already provisioned for every lab environment and EC2 nodes have IAM roles granting S3 access. ClickHouse's native `BACKUP/RESTORE` SQL commands provide a first-class mechanism for this.

The existing S3 storage integration (s3_main/s3_tier) uses the per-cluster data bucket. Backups are different: they must outlive the cluster and be reusable across cluster runs, so they go into the account bucket at a top-level `clickhouse-backups/` prefix — not under `clusters/{name}-{id}/`.

## Goals / Non-Goals

**Goals:**
- Full schema + data backup/restore via ClickHouse native commands
- Backups identified by user-provided names, stored in account bucket
- List backups with metadata (name, timestamp, size, source cluster)
- Backup before teardown (`down --clickhouse.backup <name>`)
- Restore on startup (`clickhouse start --restore-from <name>`)
- Use IAM role credentials — no explicit AWS keys anywhere

**Non-Goals:**
- Incremental or differential backups
- Per-table or per-database scoping
- Backup to arbitrary buckets (always uses account bucket)
- Scheduled automatic backups
- Backup encryption beyond what S3 provides

## Decisions

### Decision 1: ClickHouse Native BACKUP/RESTORE over file-level backup

**Chosen:** Use ClickHouse's `BACKUP DATABASE default ON CLUSTER easy_db_lab TO/FROM Disk('s3_backup', 'name/')` SQL command.

**Alternatives considered:**
- File-level: snapshot PV data and copy to S3. Fragile, requires stopping ClickHouse, and doesn't handle distributed coordination.
- clickhouse-backup tool (altinity): third-party binary to install and manage; the native command is sufficient.

**Rationale:** Native backup handles distributed cluster coordination (`ON CLUSTER`), is transactionally consistent, and maps directly to S3 with zero extra tooling.

### Decision 2: s3_plain disk type named `s3_backup` with `use_environment_credentials`

**Chosen:** Configure an `s3_backup` disk of type `s3_plain` in `config.xml`. The endpoint is injected via `CLICKHOUSE_BACKUP_S3_ENDPOINT` env var at `clickhouse start` time, following the existing `CLICKHOUSE_S3_ENDPOINT` ConfigMap pattern.

```xml
<s3_backup>
    <type>s3_plain</type>
    <endpoint from_env="CLICKHOUSE_BACKUP_S3_ENDPOINT"/>
    <use_environment_credentials>true</use_environment_credentials>
</s3_backup>
<backups>
    <allowed_disk>s3_backup</allowed_disk>
</backups>
```

**Alternatives considered:**
- `BACKUP TO S3(url, key, secret)` direct: requires explicit credentials; IAM role not usable.
- Dynamic disk reconfiguration + SIGHUP per backup: complex, potentially disruptive.

**Rationale:** `s3_plain` + environment credentials is the documented ClickHouse pattern for IAM-authenticated backups. Injecting the endpoint at startup is consistent with how we already configure S3 storage.

### Decision 3: Execute SQL via kubectl exec

**Chosen:** `kubectl exec` into `clickhouse-0` pod, run `clickhouse-client --query "..."` via K8sService.

**Alternatives considered:**
- HTTP API (port 8123) via SSH tunnel: requires tunnel setup, more moving parts.
- Direct TCP (port 9000) from CLI: requires ClickHouse native protocol client dependency.

**Rationale:** kubectl exec is already the execution model used throughout easy-db-lab for K8s operations. Adds one method to K8sService; no new dependencies.

### Decision 4: Metadata sidecar file per backup

**Chosen:** Write a `backup-metadata.json` alongside each backup at `clickhouse-backups/{name}/backup-metadata.json` containing: `backupName`, `timestamp`, `sourceCluster`, `totalSizeBytes`.

**Rationale:** ClickHouse's own backup directory structure is opaque for listing purposes. A sidecar is the simplest way to surface human-readable metadata without scanning backup internals. Written by `ClickHouseBackupService` after backup completes.

### Decision 5: S3 path outside cluster prefix

**Chosen:** `s3://account-bucket/clickhouse-backups/{name}/` — top-level in the account bucket, not under `clusters/{name}-{id}/`.

**Rationale:** Backups must survive cluster teardown and be usable by future clusters. Placing them under a cluster prefix would make them invisible to other cluster runs. The `ClusterS3Path` abstraction is extended with a new root-level `clickhouseBackups()` method on the account bucket.

## Risks / Trade-offs

- **BACKUP blocks on large datasets** → ClickHouse BACKUP is async by default; the CLI waits for completion via polling `system.backups`. Long backups may appear to hang without progress output. Mitigation: emit periodic progress events during polling.
- **`ON CLUSTER` requires all nodes healthy** → If any ClickHouse node is down, `BACKUP ON CLUSTER` will fail. Mitigation: fail fast with a clear error; user must ensure cluster is healthy before backup.
- **Backup name collisions** → If a backup named `foo` already exists, ClickHouse returns `BACKUP_ALREADY_EXISTS`. Mitigation: check for existence before running backup; emit a clear error.
- **Metadata file written after backup** → If the process is interrupted after backup but before metadata write, `list-backups` won't show the backup. Mitigation: acceptable edge case for a test tool; metadata is best-effort.

## Migration Plan

No migration needed — all clusters are ephemeral. Existing clusters gain the `s3_backup` disk on next `clickhouse start`.
