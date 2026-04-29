# ClickHouse Backup and Restore

easy-db-lab provides commands to back up ClickHouse data to S3 and restore it to a new cluster. Backups are stored at the account level — independent of any individual cluster — so you can restore data into a freshly created cluster after the original cluster is torn down.

## How Backups Work

Backups are stored under `s3://<account-bucket>/clickhouse-backups/<backup-name>/` in your account-level S3 bucket. Because this path is outside the cluster prefix, backups survive cluster teardown.

Each backup contains:

- All tables in the `default` database, exported using the ClickHouse `BACKUP DATABASE default` command
- A `backup-metadata.json` file recording the backup name, creation timestamp, source cluster name, and total size

## Creating a Backup

To back up the running ClickHouse cluster:

```bash
easy-db-lab clickhouse backup <backup-name>
```

The backup name must be unique. If a backup with that name already exists, the command fails immediately without overwriting the existing data.

**Example:**

```bash
easy-db-lab clickhouse backup my-experiment-2024-01
```

## Listing Available Backups

To see all available backups in your account bucket:

```bash
easy-db-lab clickhouse list-backups
```

This displays a table of backups sorted by creation time (newest first), including the backup name, size, and timestamp.

## Restoring a Backup

To restore a backup to a running ClickHouse cluster:

```bash
easy-db-lab clickhouse restore <backup-name>
```

**Example:**

```bash
easy-db-lab clickhouse restore my-experiment-2024-01
```

The restore command runs `RESTORE DATABASE default` inside the ClickHouse pod. Existing tables with conflicting names will cause the restore to fail; drop or rename them first if needed.

## Restoring When Starting a Cluster

You can combine cluster startup and restore in a single step using `--restore-from`:

```bash
easy-db-lab clickhouse start --restore-from <backup-name>
```

This starts ClickHouse and immediately restores the named backup once the pods are ready. This is the typical workflow for recreating a cluster with existing data:

```bash
# 1. Tear down the old cluster (backup already exists from a previous run)
easy-db-lab down

# 2. Create a fresh cluster
easy-db-lab up

# 3. Deploy ClickHouse and restore from the backup in one step
easy-db-lab clickhouse start --restore-from my-experiment-2024-01
```

## Backing Up Before Cluster Teardown

To automatically create a backup when tearing down a cluster, use the `--clickhouse.backup` option with `down`:

```bash
easy-db-lab down --clickhouse.backup <backup-name>
```

**Example:**

```bash
easy-db-lab down --clickhouse.backup my-experiment-2024-01
```

This creates the backup before shutting down the cluster so you can restore it later.

## Common Workflows

### Persist Data Across Cluster Rebuilds

```bash
# Back up before tearing down
easy-db-lab down --clickhouse.backup my-dataset

# Later, create a new cluster and restore
easy-db-lab up
easy-db-lab clickhouse start --restore-from my-dataset
```

### Snapshot Before a Destructive Operation

```bash
# Take a named snapshot before running a migration
easy-db-lab clickhouse backup pre-migration-snapshot

# Run the migration
# ...

# If something goes wrong, restore
easy-db-lab clickhouse restore pre-migration-snapshot
```

### Check What Backups Are Available

```bash
easy-db-lab clickhouse list-backups
```
