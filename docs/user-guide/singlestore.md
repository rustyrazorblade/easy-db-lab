# SingleStore

The `singlestore` kit runs a distributed [SingleStore](https://www.singlestore.com/) cluster on the
db nodes. SingleStore is a distributed SQL database that speaks the MySQL wire protocol. The kit
installs the SingleStore Kubernetes Operator on the control node, and each `start` deploys a fresh
cluster: one master aggregator, optional child aggregators, and one leaf per db node.

## Prerequisites

- Cluster is up (`easy-db-lab up`) with at least one db node.
- Each db node is an `i4i.2xlarge` (8 vCPU, 64 GiB) or larger. With the default sizes, a 4 vCPU
  node such as `i4i.xlarge` leaves too little for a leaf, and `start` fails fast with the size it
  computed and the minimum.
- A SingleStore license key.

### Getting a license key

A distributed SingleStore cluster needs a license; there is no free path around it. With no key,
or an invalid one, the server refuses to bootstrap the cluster. Get a key from the SingleStore
Customer Portal and pass it to `kit install` with `--license`. The kit
strips a trailing newline, so `--license "$(cat key.txt)"` is safe.

The license key is written to the kit's directory in the workspace and to a Kubernetes Secret.
Keep the workspace private. `stop` and `uninstall` delete the license and root password Secrets.

## Quick Start

```bash
easy-db-lab kit install singlestore --license "$(cat key.txt)"
easy-db-lab singlestore start
easy-db-lab singlestore sql "SELECT 1"
easy-db-lab singlestore stop
```

## Args

All args are named options of `kit install singlestore`. `easy-db-lab kit info singlestore` lists
them with their defaults.

| Flag | Default | Description |
|------|---------|-------------|
| `--license` | (required) | SingleStore license key |
| `--version` | `alma-9.0.44-1322a9e639` | `singlestore/node` image tag |
| `--operator-version` | `4.252.0-3adf6c3d` | `singlestore/operator` image tag |
| `--aggregators` | `0` | Child aggregators, besides the master aggregator |
| `--leaves` | the db node count | Leaves, at most one per db node |
| `--redundancy` | computed | Redundancy level, 1 or 2 (see below) |
| `--size` | `10Ti` | Storage per leaf |
| `--leaf-cores` | computed | Cores per leaf |
| `--leaf-memory` | computed | Memory per leaf, in GB |
| `--aggregator-cores` | `2` | Cores per aggregator (master and child) |
| `--aggregator-memory` | `8` | Memory per aggregator in GB (master and child) |
| `--password` | `easydblab` | The `root` password |
| `--database` | `test` | The database `start` creates |
| `--unlimited-storage` | `false` | Create the database on S3 (see Unlimited storage) |
| `--blob-cache` | none | Blob cache size in MB; required with `--unlimited-storage` |

`kit install` checks two values before it writes anything:

- `--database` takes letters, digits and `_` only.
- `--password` may not hold `"`, `'`, `\` or a control character, and may not be empty. The
  operator needs a password, so a blank one is not offered. The `singlestore/node` image sets the
  root password at startup in a way that breaks on a `'`, and every pod would crash-loop.

The message names the flag, never the value. To change an arg, re-run `kit install singlestore
--force` with the new value, then `singlestore start`.

## Topology

- One **master aggregator**, which takes client queries and holds the cluster's metadata. It shares
  a db node with a leaf.
- `--aggregators` **child aggregators**, which also take client queries.
- `--leaves` **leaves**, which hold the data. Each leaf runs on its own db node.

The master and the child aggregators each run on a db node of their own too, so a db node holds at
most one aggregator and one leaf. `start` fails before anything is created when `--leaves` is more
than the db node count, or when `--aggregators` plus the master is, naming the flag and the db node
count.

Every SingleStore pod runs on a db node; none runs on an app node or the control node. The
operator runs on the control node.

### Redundancy

With redundancy 2, the leaves form two availability groups, and every partition has a replica in
the other group. When `--redundancy` is not set, the kit uses 2 for an even leaf count and 1 for an
odd one. `--redundancy` takes 1 or 2 only; any other value fails before anything is created,
naming `--redundancy`. The leaf count must be a multiple of the redundancy, because each availability group holds
the same number of leaves: 3 leaves with `--redundancy 2` fails before anything is created, naming
`--leaves` and `--redundancy`. The operator's default pairing of availability groups is used.

### Leaf size

When `--leaf-cores` and `--leaf-memory` are not set, `start` reads the smallest db node's
allocatable CPU and memory and gives each leaf that, less:

- a fixed reserve for the collectors on the node (1 core and 4096 MB),
- one aggregator size, `--aggregator-cores` and `--aggregator-memory`: a db node holds at most one
  aggregator, so one is reserved however many aggregators there are per leaf,
- the operator's exporter sidecar in the master pod (100m CPU and 200 MB).

Cores are rounded down to whole cores. A leaf below 4 cores or 8192 MB fails `start` before
anything is created, with the computed size and the minimum. On `i4i.2xlarge` db nodes with the
defaults, each leaf gets 4 cores and about 50 GB.

### License units

The license caps the cluster's size in license units. Each leaf costs

    max(host vCPU / 8, host RAM in GB / 32)

units, measured on the host the leaf runs on. A leaf sees the whole host, so `--leaf-cores` and
`--leaf-memory` do not change what it costs; only fewer leaves (`--leaves`) or smaller db nodes do.
Aggregators, master and child, cost nothing. On `i4i.2xlarge` db nodes (8 vCPU, 64 GiB) each leaf
costs 2 units, so a 2-unit license covers one leaf.

A cluster over its license's capacity keeps working: SingleStore only writes a warning to its
tracelog (`CheckCapacity: Used cluster capacity (N units) has exceeded the maximum cluster capacity
of your license`). The `Used_cluster_capacity` and `Maximum_cluster_capacity` status variables show
the units in use and allowed. When the server does refuse the license (an invalid or missing key,
for example), `start` fails at once with SingleStore's error, removes the failed cluster, and does
not fall back to a smaller one.

## Storage

Leaves keep their data in `local-path` volumes on the db node's NVMe mount, `/mnt/db1`. The data
directory, SingleStore's tracelogs and the pods' logs are all on `/mnt/db1`; none is on the root
volume.

`--size` is the storage each leaf declares. The `local-path` provisioner does not enforce it, so
the default `10Ti` only sets the cluster's declared size; the real limit is the disk.

## Connecting

`start` creates a NodePort Service on port **30306** of every node, which forwards to port 3306 on
every aggregator. `easy-db-lab singlestore status` shows the two endpoints, MySQL wire and JDBC,
both with the installed database:

```bash
easy-db-lab singlestore status
```

From an app node, any MySQL client works:

```bash
mysql -h <db-node-ip> -P 30306 -u root -p test
```

The `sql` command connects with the installed password, which it never prints:

```bash
easy-db-lab singlestore sql "SELECT 1"
easy-db-lab singlestore sql --file query.sql
```

## Benchmarking with sysbench

The [sysbench](sysbench.md) kit can target SingleStore, and authenticates with the installed
password in every phase. `prepare` loads its tables into the `--database` database (`test` by
default). The kit's MySQL endpoint tells sysbench to ignore error 1062: concurrent
`oltp_read_write` transactions on the same id hit duplicate-key errors on SingleStore, and the run
counts them as `ignored errors` in its summary (see [Ignored errors](sysbench.md#ignored-errors)).
sysbench still ignores its own default errors too, including lock wait timeout (1205).

`oltp_read_write` at the default 8 threads can hit deadlocks between transactions that SingleStore
does not detect as deadlocks, so it raises no error 1213. Each one stalls the run at zero
throughput until `@@lock_wait_timeout` passes (60 seconds by default). The waiting transaction then
fails with error 1205, which sysbench ignores and retries, so the run continues. sysbench prints
the count of ignored errors in its summary. The kit leaves `lock_wait_timeout` at SingleStore's
default. For shorter stalls, set it yourself:

```bash
easy-db-lab singlestore sql "SET GLOBAL lock_wait_timeout = <seconds>"
```

```bash
easy-db-lab kit install sysbench --target singlestore
easy-db-lab sysbench-singlestore prepare
easy-db-lab sysbench-singlestore start --threads 16 --duration 300
easy-db-lab sysbench-singlestore stop
```

## Unlimited storage

With `--unlimited-storage`, `start` creates the database on S3, in the cluster's data bucket, with
SingleStore's unlimited storage. It sets the blob cache to `--blob-cache` MB and runs:

```sql
SET GLOBAL maximum_blob_cache_size_mb = <--blob-cache>;
CREATE DATABASE <db> ON S3 "<data bucket>/singlestore/<db>-<timestamp>"
  CONFIG '{"region":"<region>"}' CREDENTIALS '{}';
```

The node's IAM role gives the access; no access keys are used. Each `start` uses a new path and
prints it. Nothing deletes an old path, so the objects of earlier starts stay in S3.

```bash
easy-db-lab kit install singlestore --license "$(cat key.txt)" --unlimited-storage --blob-cache 20000 --force
easy-db-lab singlestore start
```

If the license does not allow unlimited storage, `start` fails with SingleStore's error and does
not create a local database instead.

## Lifecycle

- `start` sizes the cluster, clears any cluster, volumes and Secrets an earlier start left, writes
  the license and password Secrets, applies the cluster, waits for every pod and leaf, opens the
  NodePort and creates the database.
- When the wait fails, `start` prints why and the pods' state, then deletes the cluster, its
  volumes and the kit's Secrets before it exits non-zero, so the next `start` can run without a
  `stop` first. The wait fails at once, not after its 20-minute limit, when the license is refused,
  when the operator refuses the cluster spec (quoting the operator's error), or when a SingleStore
  pod is in `CrashLoopBackOff` (quoting the pod's last log lines, with the password masked).
- `stop` deletes the cluster, the NodePort, the cluster's volumes and the license and password
  Secrets. Every `start` is a fresh,
  empty cluster.
- `uninstall` stops a running cluster first, then removes the operator, its RBAC, the license and
  password Secrets and the CRD.

## Metrics and dashboards

The operator's exporter sidecar in the master pod reports every node of the cluster. The kit
scrapes it on `start` and removes the scrape on `stop`. Series carry `job="singlestore"` and the
labels `host`, `node_id` and `role` (`MA`, `CA`, `LEAF`).

`start` installs two dashboards into a `singlestore` Grafana folder:

- **SingleStore Cluster Overview**: queries, connections, memory, disk and replication.
- **SingleStore Leaves and Partitions**: work, memory and partitions per leaf.

The kit's `METRICS.md` lists the metrics.
