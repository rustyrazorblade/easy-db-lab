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
```

| Option | Description | Default |
|--------|-------------|---------|
| `--s3-cache` | Size of the local S3 cache | 10Gi |

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

ClickHouse is deployed with a sharded, replicated architecture. The total number of replicas must be divisible by `--replicas-per-shard`.

### Shard and Replica Assignment

The cluster named `easy_db_lab` is automatically configured based on your replica count:

| Configuration | Shards | Replicas/Shard | Total Nodes |
|--------------|--------|----------------|-------------|
| Default (3 nodes) | 1 | 3 | 3 |
| 6 nodes, 3/shard | 2 | 3 | 6 |
| 9 nodes, 3/shard | 3 | 3 | 9 |
| 6 nodes, 2/shard | 3 | 2 | 6 |

### Pod-to-Node Pinning

Each ClickHouse pod is pinned to a specific database node using Local PersistentVolumes with node affinity:

- `clickhouse-0` always runs on `db0`
- `clickhouse-1` always runs on `db1`
- `clickhouse-N` always runs on `dbN`

This guarantees:

1. **Consistent shard assignment** - A pod's shard is calculated from its ordinal: `shard = (ordinal / replicas_per_shard) + 1`
2. **Data locality** - Data stored on a node stays with that node across pod restarts
3. **Predictable performance** - No data movement when pods restart

### Shard Calculation Example

With 6 replicas and 3 replicas per shard:

| Pod | Ordinal | Shard | Node |
|-----|---------|-------|------|
| clickhouse-0 | 0 | 1 | db0 |
| clickhouse-1 | 1 | 1 | db1 |
| clickhouse-2 | 2 | 1 | db2 |
| clickhouse-3 | 3 | 2 | db3 |
| clickhouse-4 | 4 | 2 | db4 |
| clickhouse-5 | 5 | 2 | db5 |

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

After deployment, ClickHouse is accessible via:

| Interface | URL/Port | Description |
|-----------|----------|-------------|
| Play UI | `http://<db-node-ip>:8123/play` | Interactive web query interface |
| HTTP API | `http://<db-node-ip>:8123` | REST API for queries |
| Native Protocol | `<db-node-ip>:9000` | High-performance binary protocol |

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

| Aspect | `local` | `s3_main` |
|--------|---------|-----------|
| **Storage Location** | Local NVMe disks | S3 bucket with configurable local cache |
| **Performance** | Best latency, highest throughput | Higher latency, cache-dependent |
| **Capacity** | Limited by disk size | Virtually unlimited |
| **Cost** | Included in instance cost | S3 storage + request costs |
| **Data Persistence** | Lost when cluster is destroyed | Persists independently |
| **Best For** | Benchmarks, low-latency queries | Large datasets, cost-sensitive workloads |

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

The S3 policy stores data in your configured S3 bucket with a local cache for frequently accessed data. The cache size defaults to 10Gi and can be configured with `clickhouse init --s3-cache`. This is ideal for large datasets where storage cost matters more than latency.

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

## Stopping ClickHouse

To remove the ClickHouse cluster:

```bash
easy-db-lab clickhouse stop
```

This removes all ClickHouse pods, services, and associated resources from Kubernetes.

## Monitoring

ClickHouse metrics are automatically integrated with the observability stack:

- **Grafana Dashboard**: Pre-configured dashboard for ClickHouse metrics
- **Metrics Port**: `9363` for Prometheus-compatible metrics
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
2. Local PVs are created with node affinity matching these ordinals
3. PVs are pre-bound to specific PVCs (e.g., `data-clickhouse-0` binds to the PV on `db0`)
4. The StatefulSet's volumeClaimTemplate requests storage from these pre-bound PVs

This ensures `clickhouse-X` always runs on `dbX`, providing:

- Consistent shard assignments across restarts
- Data locality (no network storage overhead)
- Predictable failover behavior

### Ports

| Port | Purpose |
|------|---------|
| 8123 | HTTP interface |
| 9000 | Native protocol |
| 9009 | Inter-server communication |
| 9363 | Metrics |
| 2181 | Keeper client |
| 9234 | Keeper Raft |
