# ClickHouse

easy-db-lab supports deploying ClickHouse clusters on Kubernetes for analytics workloads alongside your database cluster.

## Overview

The `clickhouse` kit deploys ClickHouse with the [Altinity ClickHouse operator](https://docs.altinity.com/clickhouse-operator/). The operator runs the servers from a ClickHouseInstallation (CHI) named `clickhouse` and the coordination service from a ClickHouseKeeperInstallation (CHK) named `clickhouse-keeper`. Both run only on db nodes.

## Quick Start

Create a 3-node cluster and deploy ClickHouse as one shard with 3 replicas:

```bash
# Initialize and start a 3-node cluster
easy-db-lab init my-cluster --db 3 --up

# Install the kit: the operator, and the manifests rendered with your settings
easy-db-lab kit install clickhouse

# Deploy ClickHouse (1 shard x 3 replicas, one per db node)
easy-db-lab clickhouse start
```

## Configuring ClickHouse

All ClickHouse settings are options of `kit install clickhouse`. They are applied when the kit is
installed: the manifests in the workspace's `clickhouse/` directory are rendered with them, and
`clickhouse start` deploys those rendered manifests.

```bash
# Pin the server version and give each replica a 100Gi volume
easy-db-lab kit install clickhouse --version 25.4 --size 100Gi

# Larger S3 cache, no caching on write
easy-db-lab kit install clickhouse --s3-cache 50Gi --s3-cache-on-write false
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `--version` | string | `latest` | ClickHouse server image tag (`clickhouse/clickhouse-server:<version>`), e.g. `25.4`, `24.8` |
| `--size` | string | `10Ti` | Storage size per node (e.g. `100Gi`) |
| `--replicas` | int | number of db nodes | Number of ClickHouse replicas; also the number of Keeper replicas |
| `--s3-cache` | string | `10Gi` | Local cache size for the S3-backed storage policies |
| `--s3-cache-on-write` | boolean | `true` | Populate the S3 disk cache on write |
| `--s3-tier-move-factor` | float | `0.2` | `move_factor` of the `s3_tier` policy: data moves to S3 when local free space falls below this fraction |
| `--force` | boolean | `false` | Overwrite the `clickhouse/` directory if the kit is already installed |

To change a setting after installing, run `kit install clickhouse --force` with the new options,
then `clickhouse stop` and `clickhouse start`.

## Starting ClickHouse

```bash
easy-db-lab clickhouse start
```

`start` creates the Local PersistentVolumes on the db nodes, applies the ClickHouseKeeperInstallation
and waits for the Keeper pods to be Ready (up to 300 seconds), then applies the ClickHouseInstallation
and waits for its status to reach `Completed` (up to 900 seconds) and for every server pod to be
Ready (up to 300 seconds). Finally it creates the `clickhouse-nodeport` service. The phase takes no
options of its own.

### Example with Custom Settings

```bash
# 6 db nodes, run 3 replicas
easy-db-lab kit install clickhouse --replicas 3
easy-db-lab clickhouse start
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

```bash
easy-db-lab clickhouse status
```

This prints whether ClickHouse is running (with the count of Ready server pods) and the connection
endpoints on each db node: HTTP and JDBC (30123), Native (30900), MySQL wire (30904) and PostgreSQL
wire (30905).

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

The kit also adds a `sql` command that runs a statement over JDBC as the `default` user:

```bash
easy-db-lab clickhouse sql "SELECT version()"
easy-db-lab clickhouse sql --file schema.sql
```

## Creating Tables

The kit deploys one shard, so every replica holds all of the data. Use `ReplicatedMergeTree` to
keep the replicas in sync through ClickHouse Keeper, and create tables `ON CLUSTER clickhouse` so
the DDL runs on every replica:

```sql
CREATE TABLE events ON CLUSTER clickhouse (
    id UInt64,
    timestamp DateTime,
    event_type String,
    data String
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/events', '{replica}')
ORDER BY (timestamp, id);
```

**Key points:**

- `clickhouse` is the cluster name defined in the ClickHouseInstallation
- `{shard}` and `{replica}` are macros the operator sets on each server
- An insert on any replica is replicated to the others
- A query on any replica sees the whole table

### Distributed Tables

A `Distributed` table routes queries and inserts across shards. With one shard it adds nothing
over querying the replicated table directly, but the pattern works unchanged:

```sql
CREATE TABLE events_dist ON CLUSTER clickhouse AS events
ENGINE = Distributed(clickhouse, default, events, rand());

INSERT INTO events_dist VALUES (1, now(), 'click', '{"page": "/home"}');
SELECT count(*) FROM events_dist WHERE event_type = 'click';
```

### Table Engine Comparison

| Engine | Use Case | Replication | Sharding |
|--------|----------|-------------|----------|
| `MergeTree` | Single-node, no replication | No | No |
| `ReplicatedMergeTree` | Replicated within shard | Yes | No |
| `Distributed` | Query/insert across shards | Via underlying table | Yes |

## Storage Policies

ClickHouse is configured with three storage policies. You select the policy when creating a table using the `SETTINGS storage_policy` clause.

### Policy Comparison

| Aspect | `default` | `s3_main` | `s3_tier` |
|--------|-----------|-----------|-----------|
| **Storage Location** | Local disk (`local_disk`) | Cluster's S3 data bucket, through a local cache | Hybrid: starts local, moves to S3 when disk fills |
| **Performance** | Best latency, highest throughput | Higher latency, cache-dependent | Good initially, degrades as data moves to S3 |
| **Capacity** | Limited by disk size | Virtually unlimited | Virtually unlimited |
| **Cost** | Included in instance cost | S3 storage + request costs | S3 storage + request costs |
| **Data Persistence** | Lost when cluster is destroyed | Expires with the data bucket after `down` | Expires with the data bucket after `down` |
| **Best For** | Benchmarks, low-latency queries | Large datasets, cost-sensitive workloads | Mixed hot/cold workloads with automatic tiering |

### Local Storage (`default`)

The `default` policy stores data on the local disk of the db node, in the replica's Local
PersistentVolume. This provides the best performance for latency-sensitive workloads.

```sql
CREATE TABLE my_table (...)
ENGINE = MergeTree()
ORDER BY id
SETTINGS storage_policy = 'default';
```

If you omit the `storage_policy` setting, tables use this policy.

**When to use local storage:**

- Performance benchmarking where latency matters
- Temporary or experimental datasets
- Workloads with predictable data sizes that fit on local disks
- When you don't need data to persist after cluster teardown

### S3 Storage (`s3_main`)

The S3 policy stores data in the cluster's S3 data bucket (under `clickhouse/`), which `up` creates,
with a local cache for frequently accessed data. The cache size defaults to 10Gi and is set with
`kit install clickhouse --s3-cache`. Caching on write is enabled by default
(`--s3-cache-on-write true`), so data written is cached and subsequent reads can be served from
cache immediately. This is ideal for large datasets where storage cost matters more than latency.
The servers reach S3 with the instance profile credentials; no keys are configured.

```sql
CREATE TABLE my_table (...)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/default/my_table', '{replica}')
ORDER BY id
SETTINGS storage_policy = 's3_main';
```

**When to use S3 storage:**

- Large analytical datasets (terabytes+)
- Datasets larger than the local disks
- Cost-sensitive workloads where storage cost > compute cost

`down` sets a lifecycle expiration on the data bucket (`--retention-days`, default 1), so data in
it does not outlive the cluster. Use [Backup and Restore](#backup-and-restore) to keep a dataset.

**How the cache works:**

- Hot (frequently accessed) data is cached locally for fast reads
- Cold data is fetched from S3 on demand
- Cache is automatically managed by ClickHouse
- First query on cold data will be slower; subsequent queries use cache

### S3 Tiered Storage (`s3_tier`)

The S3 tiered policy moves data from local disk to S3 based on disk space. It has a `hot` volume on
the local disk and a `cold` volume on the cached S3 disk: data is written locally and moved to S3
when local free space runs low, giving local performance for hot data and S3 capacity for cold data.

Configure the tiering behavior when installing the kit:

```bash
# Move data to S3 when local disk free space falls below 20% (default)
easy-db-lab kit install clickhouse --s3-tier-move-factor 0.2

# More aggressive tiering - move when free space < 50%
easy-db-lab kit install clickhouse --s3-tier-move-factor 0.5
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
- When local disk free space falls below the configured threshold (default: 20%), ClickHouse automatically moves data to S3
- Data on S3 is still queryable but with higher latency
- The local cache (configured with `--s3-cache`) helps performance for frequently accessed S3 data
- Manual moves are also possible: `ALTER TABLE my_table MOVE PARTITION tuple() TO DISK 's3'`

## Backup and Restore

The kit provides `backup` and `restore` commands. Backups are stored in your account-level S3 bucket, outside the per-cluster prefix, so they survive cluster teardown and can be restored into a new cluster.

ClickHouse's native `BACKUP`/`RESTORE` SQL is used. The backup destination is a named disk (`s3_backup`) configured in the ClickHouseInstallation, which points to:

```
s3://<account-bucket>/clickhouse-backups/<backup-name>/
```

The S3 disk uses IAM instance profile credentials — no AWS keys are stored anywhere in the cluster configuration. The required S3 permissions on the account-level bucket are handled automatically by the easy-db-lab IAM setup — no manual configuration is needed for standard clusters.

### Creating a Backup

```bash
easy-db-lab clickhouse backup --name <backup-name>
```

Without `--name`, the backup is named `backup-<yyyyMMdd-HHmmss>`. The command fails if a backup with
that name already exists in S3, and prints the `aws s3 rm` command that deletes it. Otherwise it
runs, in the first ClickHouse server pod:

```sql
BACKUP DATABASE default TO Disk('s3_backup', '<backup-name>/');
```

### Restoring a Backup

```bash
easy-db-lab clickhouse restore --name <backup-name>
```

In the first ClickHouse server pod, this first drops stale replica entries in Keeper for replicated
tables that no longer exist locally (left behind by a `DROP TABLE` without `SYNC`, they make the
restore fail with `REPLICA_ALREADY_EXISTS`), then runs:

```sql
RESTORE DATABASE default FROM Disk('s3_backup', '<backup-name>/');
```

> **Note:** The restore command does not drop existing tables first. If tables with conflicting names exist, the restore will fail. Drop or truncate the conflicting tables before restoring.

### Common Workflows

**Snapshot before a destructive operation:**

```bash
# Take a named snapshot before running a migration
easy-db-lab clickhouse backup --name pre-migration-snapshot

# Run the migration
# ...

# If something goes wrong, restore
easy-db-lab clickhouse restore --name pre-migration-snapshot
```

**Persist data across cluster rebuilds:**

```bash
# Back up before tearing down
easy-db-lab clickhouse backup --name my-dataset
easy-db-lab down

# In a new cluster workspace: create the cluster, install, start, and restore
easy-db-lab init my-cluster --db 3 --up
easy-db-lab kit install clickhouse --size 100Gi
easy-db-lab clickhouse start
easy-db-lab clickhouse restore --name my-dataset
```

## Stopping ClickHouse

```bash
easy-db-lab clickhouse stop
```

This deletes the ClickHouseInstallation `clickhouse` (and with it the server pods) and the
`clickhouse-nodeport` service. Keeper, the operator, and the PersistentVolumes stay in place, so
`clickhouse start` redeploys onto the same volumes.

To remove everything the kit created:

```bash
easy-db-lab clickhouse uninstall
```

This deletes the ClickHouseKeeperInstallation `clickhouse-keeper`, the kit's PersistentVolumes, and
the `clickhouse-operator` Helm release.

## Monitoring

ClickHouse metrics are automatically integrated with the observability stack. On `clickhouse start`
the kit registers two Prometheus scrape jobs. Both use pod discovery, so each pod is scraped once,
by the collector on its own node, with `instance` set to the pod name:

| Job | Pods | Port | Path |
|-----|------|------|------|
| `clickhouse` | server pods (`clickhouse.altinity.com/chi=clickhouse`) | 9363 | `/metrics` |
| `clickhouse-keeper` | Keeper pods (`clickhouse-keeper.altinity.com/chk=clickhouse-keeper`) | 7000 | `/metrics` |

`start` also installs the kit's Grafana dashboards, one for metrics and one for logs, into a Grafana
folder named `clickhouse`.

## Architecture

The ClickHouse deployment includes:

- **Altinity ClickHouse operator**: Helm release `clickhouse-operator` in `kube-system`, installed by `kit install clickhouse`
- **ClickHouse Server**: ClickHouseInstallation `clickhouse`, one cluster named `clickhouse` with 1 shard and `--replicas` replicas, on db nodes only
- **ClickHouse Keeper**: ClickHouseKeeperInstallation `clickhouse-keeper` with `--replicas` replicas on db nodes, used for replication and `ON CLUSTER` DDL (ZooKeeper-compatible); the CHI references it by name
- **Service**: `clickhouse-nodeport`, a NodePort service in front of the server pods
- **Local PersistentVolumes**: One PV per db node for data locality

### Storage Architecture

ClickHouse uses Local PersistentVolumes to guarantee pod-to-node pinning:

1. During cluster creation, each `db` node is labeled with its ordinal (`easydblab.com/node-ordinal=0`, etc.)
2. Local PVs are created with node affinity matching these ordinals and the `type=db` label
3. Each PV is labelled `app.kubernetes.io/name=clickhouse`, and the claims select that label, so they never bind another kit's PV
4. The installation's volumeClaimTemplate requests `--size` of storage from these PVs

Once a replica's claim binds a PV, that replica always runs on the PV's db node, providing:

- Data locality (no network storage overhead)
- No data movement when pods restart

The binding is made when the replica first starts, so replica numbers do not map to node numbers;
see [Pod-to-Node Placement](#pod-to-node-placement).

### Ports

Clients connect to the NodePorts on any db node. The container ports are what the pods listen on
inside the cluster; the metrics scrapes use them directly.

| Container port | NodePort | Purpose |
|----------------|----------|---------|
| 8123 | 30123 | HTTP interface (also Play UI and JDBC) |
| 9000 | 30900 | Native protocol |
| 9004 | 30904 | MySQL wire protocol |
| 9005 | 30905 | PostgreSQL wire protocol |
| 9363 | 30936 | Server metrics (scraped on the container port) |
| 7000 | — | Keeper metrics |
| 2181 | — | Keeper client |
| 9444 | — | Keeper Raft |
