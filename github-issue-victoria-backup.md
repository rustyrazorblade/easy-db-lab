# Add backup commands for VictoriaMetrics and VictoriaLogs

## Summary

Add backup functionality for VictoriaMetrics and VictoriaLogs running in K8s on control nodes. This includes:

1. Persistent storage for both services (currently using ephemeral `emptyDir`)
2. New `metrics backup` command using VictoriaMetrics' native `vmbackup` tool with direct S3 upload
3. New `logs backup` command using VictoriaLogs' partition snapshot API

## Design

### Persistent Storage (K8s Changes)

Update deployments to use `hostPath` instead of `emptyDir`:

**`44-victoriametrics-deployment.yaml`:**
```yaml
volumes:
  - name: data
    hostPath:
      path: /mnt/db1/victoriametrics
      type: DirectoryOrCreate
```

**`45-victorialogs-deployment.yaml`:**
```yaml
volumes:
  - name: data
    hostPath:
      path: /mnt/db1/victorialogs
      type: DirectoryOrCreate
```

### Command Structure

```bash
# Backup VictoriaMetrics to cluster's default S3 bucket
easy-db-lab metrics backup

# Backup VictoriaMetrics to custom bucket
easy-db-lab metrics backup --bucket my-custom-bucket

# Backup VictoriaLogs to cluster's default S3 bucket
easy-db-lab logs backup

# Backup VictoriaLogs to custom bucket
easy-db-lab logs backup --bucket my-custom-bucket
```

### S3 Path Structure

Backups stored with ISO timestamps:
```
s3://{cluster-bucket}/victoriametrics/2026-02-04T12-30-00/
s3://{cluster-bucket}/victorialogs/2026-02-04T12-30-00/
```

Extend `ClusterS3Path.kt` with:
```kotlin
companion object {
    const val VICTORIAMETRICS_DIR = "victoriametrics"
    const val VICTORIALOGS_DIR = "victorialogs"
}

fun victoriametrics(): ClusterS3Path = resolve(VICTORIAMETRICS_DIR)
fun victorialogs(): ClusterS3Path = resolve(VICTORIALOGS_DIR)
```

### Service Layer

```kotlin
interface VictoriaBackupService {
    // VictoriaMetrics - runs vmbackup container with S3 destination
    fun backupMetrics(bucket: String? = null): Result<BackupResult>

    // VictoriaLogs - creates partition snapshots, uploads to S3
    fun backupLogs(bucket: String? = null): Result<BackupResult>

    // List available partitions for VictoriaLogs
    fun listLogPartitions(): Result<List<String>>
}
```

### Backup Implementation Details

**VictoriaMetrics (via vmbackup):**

Run `vmbackup` as a Kubernetes Job that backs up directly to S3:
```bash
kubectl run vmbackup --image=victoriametrics/vmbackup \
  -- \
  -storageDataPath=/victoria-metrics-data \
  -snapshot.createURL=http://localhost:8428/snapshot/create \
  -dst=s3://<bucket>/victoriametrics/<timestamp>/
```

- Uses native `vmbackup` tool with direct S3 upload
- Supports incremental backups automatically
- No need to stop VictoriaMetrics during backup
- Pod inherits IAM permissions from EC2 instance profile (no credentials needed)

**VictoriaLogs (snapshot + upload):**

VictoriaLogs doesn't have native S3 support yet (on roadmap), so use partition snapshot API:

1. Call `GET /internal/partition/list` to get all partitions
2. For each partition: `POST /internal/partition/snapshot/create?name=YYYYMMDD`
3. Download snapshot directory from pod
4. Upload to S3 via `ObjectStore.uploadDirectory()`
5. Cleanup: `DELETE /internal/partition/snapshot/delete?path=<path>`

### IAM Permissions

No additional configuration needed:
- Control nodes already have `httpPutResponseHopLimit(2)` allowing containers to access EC2 metadata service
- Both Victoria deployments use `hostNetwork: true`
- IAM instance profile is already attached to instances
- AWS SDK automatically discovers credentials from IMDS

## Files to Create/Modify

**K8s Manifests (modify):**
- `src/main/resources/.../k8s/core/44-victoriametrics-deployment.yaml`
- `src/main/resources/.../k8s/core/45-victorialogs-deployment.yaml`

**Commands (create):**
- `commands/metrics/Metrics.kt` - Parent command
- `commands/metrics/MetricsBackup.kt` - Backup subcommand
- `commands/logs/LogsBackup.kt` - Backup subcommand

**Commands (modify):**
- `commands/logs/Logs.kt` - Add `LogsBackup` to subcommands

**Services (create):**
- `services/VictoriaBackupService.kt` - Interface
- `services/DefaultVictoriaBackupService.kt` - Implementation

**Configuration (modify):**
- `configuration/ClusterS3Path.kt` - Add `victoriametrics()` and `victorialogs()` methods

**DI (modify):**
- `modules/ServicesModule.kt` - Register new service

**CLI Registration (modify):**
- `CommandLineParser.kt` - Register `Metrics` parent command

## Out of Scope

- Restore commands (`metrics restore`, `logs restore`) - future work
- Partition selection flag for logs backup - backs up all partitions

## References

- [VictoriaMetrics vmbackup documentation](https://docs.victoriametrics.com/victoriametrics/vmbackup/)
- [vmbackup Docker image](https://hub.docker.com/r/victoriametrics/vmbackup)
- [VictoriaLogs documentation](https://docs.victoriametrics.com/victorialogs/)
