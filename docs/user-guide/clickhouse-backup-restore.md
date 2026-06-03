# ClickHouse Backup and Restore

easy-db-lab provides `backup` and `restore` commands for ClickHouse workloads. Backups are stored in your account-level S3 bucket, outside the per-cluster prefix, so they survive cluster teardown and can be restored into a new cluster.

## How It Works

ClickHouse's native `BACKUP`/`RESTORE` SQL is used. The backup destination is a named disk (`s3_backup`) configured in the ClickHouseInstallation CR, which points to:

```
s3://<account-bucket>/clickhouse-backups/<backup-name>/
```

The S3 disk uses IAM instance profile credentials — no AWS keys are stored anywhere in the cluster configuration.

## IAM Requirements

The EC2 instance profile used by your cluster nodes must have the following permissions on the account-level S3 bucket:

```json
{
  "Effect": "Allow",
  "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:ListBucket"],
  "Resource": [
    "arn:aws:s3:::<account-bucket>/clickhouse-backups/*",
    "arn:aws:s3:::<account-bucket>"
  ]
}
```

This is handled automatically by the easy-db-lab IAM setup — no manual configuration is needed for standard clusters.

## Creating a Backup

```bash
easy-db-lab clickhouse backup <backup-name>
```

This discovers the primary ClickHouse pod and runs:

```sql
BACKUP DATABASE default ON CLUSTER clickhouse TO Disk('s3_backup', '<backup-name>/');
```

**Example:**

```bash
easy-db-lab clickhouse backup pre-migration-snapshot
```

## Restoring a Backup

```bash
easy-db-lab clickhouse restore <backup-name>
```

This discovers the primary ClickHouse pod and runs:

```sql
RESTORE DATABASE default ON CLUSTER clickhouse FROM Disk('s3_backup', '<backup-name>/');
```

**Example:**

```bash
easy-db-lab clickhouse restore pre-migration-snapshot
```

> **Note:** The restore command does not drop existing tables first. If tables with conflicting names exist, the restore will fail. Drop or truncate the conflicting tables before restoring.

## Common Workflows

### Snapshot Before a Destructive Operation

```bash
# Take a named snapshot before running a migration
easy-db-lab clickhouse backup pre-migration-snapshot

# Run the migration
# ...

# If something goes wrong, restore
easy-db-lab clickhouse restore pre-migration-snapshot
```

### Persist Data Across Cluster Rebuilds

```bash
# Back up before tearing down
easy-db-lab clickhouse backup my-dataset
easy-db-lab down

# Create a new cluster and restore
easy-db-lab up
easy-db-lab clickhouse install --size 100Gi
easy-db-lab clickhouse start
easy-db-lab clickhouse restore my-dataset
```
