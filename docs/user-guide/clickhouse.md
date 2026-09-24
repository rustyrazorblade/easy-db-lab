# ClickHouse

easy-db-lab supports deploying ClickHouse clusters on Kubernetes for analytics workloads alongside your Cassandra cluster.

## Overview

ClickHouse is deployed as a StatefulSet on K3s with ClickHouse Keeper for distributed coordination. The deployment requires a minimum of 3 nodes.

## Quick Start

Create a 6-node cluster and deploy ClickHouse with 2 shards:

```bash
# Initialize and start a 6-node cluster
easy-db-lab init my-cluster --db 6 --up

# Deploy ClickHouse (2 shards x 3 replicas)
easy-db-lab clickhouse start
```

## Configuring ClickHouse

Use `clickhouse init` to configure ClickHouse settings before starting the cluster:

```bash
# Configure S3 cache size (default: 10Gi)
easy-db-lab clickhouse init --s3-cache 50Gi

# Disable write-through caching
easy-db-lab clickhouse init --s3-cache-on-write false
```

| Option | Description | Default |
|--------|-------------|---------|
| `--s3-cache` | Size of the local S3 cache | 10Gi |
| `--s3-cache-on-write` | Cache data during write operations | true |
| `--s3-tier-move-factor` | Move data to S3 tier when local disk free space falls below this fraction (0.0-1.0) | 0.2 |
| `--replicas-per-shard` | Number of replicas per shard | 3 |

Configuration is saved to the cluster state and applied when you run `clickhouse start`.

## Starting ClickHouse

To deploy ClickHouse on an existing cluster:

```bash
easy-db-lab clickhouse start
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--timeout` | Seconds to wait for pods to be ready | 300 |
| `--skip-wait` | Skip waiting for pods to be ready | false |
| `--replicas` | Number of ClickHouse server replicas | Number of db nodes |
| `--replicas-per-shard` | Number of replicas per shard | 3 |

### Example with Custom Settings

```bash
# 6 nodes with 3 replicas per shard = 2 shards
easy-db-lab clickhouse start --replicas 6 --replicas-per-shard 3

# 9 nodes with 3 replicas per shard = 3 shards
easy-db-lab clickhouse start --replicas 9 --replicas-per-shard 3
```

## Cluster Topology

ClickHouse is deployed by the Altinity ClickHouse operator as a ClickHouseInstallation named
`clickhouse`, with one shard and `--replicas` replicas (default: one per db node). The ClickHouse
cluster is also named `clickhouse`, which is the name to use in `ON CLUSTER` DDL.

### Pod Names

The operator names each server pod `chi-clickhouse-clickhouse-<shard>-<replica>-0`, with shard
and replica counted from 0. A 3-replica deployment runs:

| Pod | Shard | Replica |
|-----|-------|---------|
| `chi-clickhouse-clickhouse-0-0-0` | 0 | 0 |
| `chi-clickhouse-clickhouse-0-1-0` | 0 | 1 |
| `chi-clickhouse-clickhouse-0-2-0` | 0 | 2 |

List them with:

```bash
kubectl get pods -l clickhouse.altinity.com/chi=clickhouse -o wide
```

### Pod-to-Node Placement

Server pods run only on db nodes, each on a Local PersistentVolume. A replica is placed on a db
node when it first starts, and its volume keeps it on that node across pod restarts, so data stays
local and does not move. Which db node a given replica lands on is chosen by the scheduler, not by
its replica number; use `kubectl get pods -o wide` (above) to see the mapping.

### Shell Helpers

After `source env.sh`, two helpers connect to ClickHouse without looking up pod names or IPs:

```bash
# Interactive clickhouse-client in the first running server pod; extra args are passed through
clickhouse-client
clickhouse-client --query "SELECT version()"

# Send one query over HTTP to db0 (NodePort 30123)
clickhouse-query "SELECT 1"
clickhouse-query <<< "SELECT count() FROM system.tables"
```

## Checking Status

To check the status of your ClickHouse cluster:

```bash
easy-db-lab clickhouse status
```

This displays:

- Pod status and health
- Access URLs for the Play UI and HTTP interface
- Native protocol connection details

## Accessing ClickHouse

After deployment, ClickHouse is accessible on NodePorts of every db node. The `default` user has
no password.

| Interface | URL/Port | Description |
|-----------|----------|-------------|
| Play UI | `http://<db-node-ip>:30123/play` | Interactive web query interface |
| HTTP API | `http://<db-node-ip>:30123` | REST API for queries |
| Native Protocol | `<db-node-ip>:30900` | High-performance binary protocol |
| MySQL wire | `<db-node-ip>:30904` | MySQL-compatible protocol (`mysql -h <ip> -P 30904 -u default`) |
| PostgreSQL wire | `<db-node-ip>:30905` | PostgreSQL-compatible protocol (`psql -h <ip> -p 30905 -U default`) |

The MySQL and PostgreSQL interfaces are protocol-compatible, not dialect-compatible:
queries sent over them are parsed as ClickHouse SQL. They are handy for connecting
standard clients and drivers, but tools that emit MySQL- or PostgreSQL-specific DDL
will not work unmodified.

## Creating Tables

ClickHouse supports distributed, replicated tables that span multiple shards. The recommended pattern uses `ReplicatedMergeTree` for local replicated storage and `Distributed` for querying across shards.

### Distributed Replicated Tables

Create a local replicated table on all nodes, then a distributed table for queries:

```sql
-- Step 1: Create local replicated table on all nodes
CREATE TABLE events_local ON CLUSTER easy_db_lab (
    id UInt64,
    timestamp DateTime,
    event_type String,
    data String
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/events', '{replica}')
ORDER BY (timestamp, id)
SETTINGS storage_policy = 's3_main';

-- Step 2: Create distributed table for querying across all shards
CREATE TABLE events ON CLUSTER easy_db_lab AS events_local
ENGINE = Distributed(easy_db_lab, default, events_local, rand());
```

**Key points:**

- `ON CLUSTER easy_db_lab` runs the DDL on all nodes
- `{shard}` and `{replica}` are ClickHouse macros automatically set per node
- `ReplicatedMergeTree` replicates data within a shard using ClickHouse Keeper
- `Distributed` routes queries and inserts across shards
- `rand()` distributes inserts randomly; use a column for deterministic sharding

### Querying and Inserting

```sql
-- Insert through distributed table (auto-sharded)
INSERT INTO events VALUES (1, now(), 'click', '{"page": "/home"}');

-- Query across all shards
SELECT count(*) FROM events WHERE event_type = 'click';

-- Query a specific shard (via local table)
SELECT count(*) FROM events_local WHERE event_type = 'click';
```

### Table Engine Comparison

| Engine | Use Case | Replication | Sharding |
|--------|----------|-------------|----------|
| `MergeTree` | Single-node, no replication | No | No |
| `ReplicatedMergeTree` | Replicated within shard | Yes | No |
| `Distributed` | Query/insert across shards | Via underlying table | Yes |

## Storage Policies

ClickHouse is configured with two storage policies. You select the policy when creating a table using the `SETTINGS storage_policy` clause.

### Policy Comparison

| Aspect | `local` | `s3_main` | `s3_tier` |
|--------|---------|-----------|-----------|
| **Storage Location** | Local NVMe disks | S3 bucket with configurable local cache | Hybrid: starts local, moves to S3 when disk fills |
| **Performance** | Best latency, highest throughput | Higher latency, cache-dependent | Good initially, degrades as data moves to S3 |
| **Capacity** | Limited by disk size | Virtually unlimited | Virtually unlimited |
| **Cost** | Included in instance cost | S3 storage + request costs | S3 storage + request costs |
| **Data Persistence** | Lost when cluster is destroyed | Persists independently | Persists independently |
| **Best For** | Benchmarks, low-latency queries | Large datasets, cost-sensitive workloads | Mixed hot/cold workloads with automatic tiering |

### Local Storage (`local`)

The default policy stores data on local NVMe disks attached to the database nodes. This provides the best performance for latency-sensitive workloads.

```sql
CREATE TABLE my_table (...)
ENGINE = MergeTree()
ORDER BY id
SETTINGS storage_policy = 'local';
```

If you omit the `storage_policy` setting, tables use local storage by default.

**When to use local storage:**

- Performance benchmarking where latency matters
- Temporary or experimental datasets
- Workloads with predictable data sizes that fit on local disks
- When you don't need data to persist after cluster teardown

### S3 Storage (`s3_main`)

The S3 policy stores data in your configured S3 bucket with a local cache for frequently accessed data. The cache size defaults to 10Gi and can be configured with `clickhouse init --s3-cache`. Write-through caching is enabled by default (`--s3-cache-on-write true`), which caches data during writes so subsequent reads can be served from cache immediately. This is ideal for large datasets where storage cost matters more than latency.

**Prerequisite**: Your cluster must be initialized with an S3 bucket. Set this during `init`:

```bash
easy-db-lab init my-cluster --s3-bucket my-clickhouse-data
```

Then create tables with S3 storage:

```sql
CREATE TABLE my_table (...)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/default/my_table', '{replica}')
ORDER BY id
SETTINGS storage_policy = 's3_main';
```

**When to use S3 storage:**

- Large analytical datasets (terabytes+)
- Data that should persist across cluster restarts
- Cost-sensitive workloads where storage cost > compute cost
- Sharing data between multiple clusters

**How the cache works:**

- Hot (frequently accessed) data is cached locally for fast reads
- Cold data is fetched from S3 on demand
- Cache is automatically managed by ClickHouse
- First query on cold data will be slower; subsequent queries use cache

### S3 Tiered Storage (`s3_tier`)

The S3 tiered policy provides automatic data movement from local disks to S3 based on disk space availability. This policy starts with local storage and automatically moves data to S3 when local disk space runs low, providing the best of both worlds: fast local performance for hot data and unlimited S3 capacity for cold data.

**Prerequisite**: Your cluster must be initialized with an S3 bucket. Set this during `init`:

```bash
easy-db-lab init my-cluster --s3-bucket my-clickhouse-data
```

Configure the tiering behavior before starting ClickHouse:

```bash
# Move data to S3 when local disk free space falls below 20% (default)
easy-db-lab clickhouse init --s3-tier-move-factor 0.2

# More aggressive tiering - move when free space < 50%
easy-db-lab clickhouse init --s3-tier-move-factor 0.5
```

Then create tables with S3 tiered storage:

```sql
CREATE TABLE my_table (...)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/default/my_table', '{replica}')
ORDER BY id
SETTINGS storage_policy = 's3_tier';
```

**When to use S3 tiered storage:**

- Workloads with mixed hot/cold data access patterns
- Growing datasets that may outgrow local disk capacity
- Want automatic cost optimization without manual intervention
- Need local performance for recent data with S3 capacity for historical data

**How automatic tiering works:**

- New data is written to local disks first (fast writes)
- When local disk free space falls below the configured threshold (default: 20%), ClickHouse automatically moves the oldest data to S3
- Data on S3 is still queryable but with higher latency
- The local cache (configured with `--s3-cache`) helps performance for frequently accessed S3 data
- Manual moves are also possible: `ALTER TABLE my_table MOVE PARTITION tuple() TO DISK 's3'`

## Backup and Restore

easy-db-lab provides `backup` and `restore` commands for ClickHouse workloads. Backups are stored in your account-level S3 bucket, outside the per-cluster prefix, so they survive cluster teardown and can be restored into a new cluster.

ClickHouse's native `BACKUP`/`RESTORE` SQL is used. The backup destination is a named disk (`s3_backup`) configured in the ClickHouseInstallation CR, which points to:

```
s3://<account-bucket>/clickhouse-backups/<backup-name>/
```

The S3 disk uses IAM instance profile credentials — no AWS keys are stored anywhere in the cluster configuration. The required S3 permissions on the account-level bucket are handled automatically by the easy-db-lab IAM setup — no manual configuration is needed for standard clusters.

### Creating a Backup

```bash
easy-db-lab clickhouse backup <backup-name>
```

This discovers the primary ClickHouse pod and runs:

```sql
BACKUP DATABASE default ON CLUSTER clickhouse TO Disk('s3_backup', '<backup-name>/');
```

### Restoring a Backup

```bash
easy-db-lab clickhouse restore <backup-name>
```

This discovers the primary ClickHouse pod and runs:

```sql
RESTORE DATABASE default ON CLUSTER clickhouse FROM Disk('s3_backup', '<backup-name>/');
```

> **Note:** The restore command does not drop existing tables first. If tables with conflicting names exist, the restore will fail. Drop or truncate the conflicting tables before restoring.

### Common Workflows

**Snapshot before a destructive operation:**

```bash
# Take a named snapshot before running a migration
easy-db-lab clickhouse backup pre-migration-snapshot

# Run the migration
# ...

# If something goes wrong, restore
easy-db-lab clickhouse restore pre-migration-snapshot
```

**Persist data across cluster rebuilds:**

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

## Stopping ClickHouse

To remove the ClickHouse cluster:

```bash
easy-db-lab clickhouse stop
```

This removes all ClickHouse pods, services, and associated resources from Kubernetes.

## Monitoring

ClickHouse metrics are automatically integrated with the observability stack:

- **Grafana Dashboard**: Pre-configured dashboard for ClickHouse metrics
- **Metrics Port**: `9363` for Prometheus-compatible metrics; Keeper serves its own on `7000`
- **Logs Dashboard**: Dedicated dashboard for ClickHouse logs

## Architecture

The ClickHouse deployment includes:

- **ClickHouse Server**: StatefulSet with configurable replicas
- **ClickHouse Keeper**: 3-node cluster for distributed coordination (ZooKeeper-compatible)
- **Services**: Headless services for internal communication
- **ConfigMaps**: Server and Keeper configuration
- **Local PersistentVolumes**: One PV per node for data locality

### Storage Architecture

ClickHouse uses Local PersistentVolumes to guarantee pod-to-node pinning:

1. During cluster creation, each `db` node is labeled with its ordinal (`easydblab.com/node-ordinal=0`, etc.)
2. Local PVs are created with node affinity matching these ordinals and the `type=db` label
3. Each PV is labelled `app.kubernetes.io/name=clickhouse`, and the claims select that label, so they never bind another kit's PV
4. The installation's volumeClaimTemplate requests storage from these PVs

Once a replica's claim binds a PV, that replica always runs on the PV's db node, providing:

- Data locality (no network storage overhead)
- No data movement when pods restart

The binding is made when the replica first starts, so replica numbers do not map to node numbers;
see [Pod-to-Node Placement](#pod-to-node-placement).

### Ports

| Port | Purpose |
|------|---------|
| 8123 | HTTP interface |
| 9000 | Native protocol |
| 9004 | MySQL wire protocol |
| 9005 | PostgreSQL wire protocol |
| 9009 | Inter-server communication |
| 9363 | Metrics |
| 2181 | Keeper client |
| 9444 | Keeper Raft |
| 7000 | Keeper metrics |
