# cqlite-flight

The `cqlite-flight` kit deploys the [`cqlite-flight`](https://github.com/pmcfadin/cqlite)
Arrow Flight server as a DaemonSet co-located with your database nodes — one pod per
`type=db` node, each reading that node's own local SSTables. It is the data plane behind
the Trino kit's [`cqlite` catalog](install-trino.md#the-cqlite-catalog-offline-sstable-reads):
Trino asks each Flight pod for the SSTable data on its node, and cqlite-flight streams it
back as Arrow record batches.

```admonish note title="The Trino catalog is not shipped yet"
The data plane on this page works today. The Trino `cqlite` catalog that queries it is
planned: its connector plugin delivery is deferred to `pmcfadin/cqlite#2869`.
```

```admonish warning title="Offline, read-only, flushed-SSTables-only view"
cqlite-flight reads **SSTable files on disk**, not the live database. This has three
consequences you must keep in mind when interpreting results:

- **Read-only / offline.** It never touches the live read/write path. It cannot see or
  serve writes that have not yet been flushed from memtables to SSTables.
- **Flushed-only.** Rows that are still in memtables (or the commit log) are invisible
  until a flush lands them in an SSTable. Run `nodetool flush` on the target keyspace
  first if you need recent writes to appear.
- **Eventually stale.** SSTables are rewritten by compaction and augmented by new
  flushes over time, so a query reflects whatever files were on disk when it ran — not a
  single consistent cluster snapshot. Do not treat cqlite-flight results as a live,
  consistent view of the database.
```

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- At least one db node with a database (e.g. Cassandra) whose data directory holds
  flushed SSTables

## Quick Start

```bash
easy-db-lab kit install cqlite-flight --tag v0.18.0
easy-db-lab cqlite-flight start
easy-db-lab cqlite-flight status
```

`cqlite-flight start` rolls out one Arrow Flight pod per db node (a DaemonSet with
`nodeSelector: type=db`) and mounts each node's Cassandra data directory read-only into
its pod. The pods run on the pod network. Clients reach the Flight gRPC port through the
`cqlite-flight-nodeport` Service at `<db-node-private-ip>:30816`. That Service is node-local
(`externalTrafficPolicy: Local`): a db node's IP always reaches that node's own pod, because
that pod is the only one holding the node's SSTables.
NodePort 30816 has no authentication. The cluster's security group allows it, like every port
other than SSH, only from the cluster's VPC CIDR, so it is not open to the internet. Anything in
the VPC can read the served SSTables through it, as can your machine through the SOCKS tunnel and,
on a Tailscale cluster, any tailnet device your ACL lets use the control node's subnet route (see
[Port Reference](../reference/ports.md#kit-ports)). Both containers run with no
privilege escalation, all capabilities dropped and a read-only root filesystem.

```admonish warning title="Needs the database on the node itself"
cqlite-flight reads the data directory from the db node's filesystem through a read-only
`hostPath` mount. It works only where the database runs directly on the node (the VM path,
e.g. Cassandra installed on the db nodes), and the cluster must allow `hostPath` volumes. It
cannot read a database that runs in Kubernetes pods with its own volumes.
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--tag` | (required) | `cqlite-flight` image tag (`ghcr.io/pmcfadin/cqlite-flight:<tag>`), for example `v0.18.0`. Required, so every run names the build it tests; pass `dev` or `latest` only to follow a moving tag on purpose. Without it, `kit install` fails with `Missing required option: --tag` |
| `--flight-port` | `8815` | Arrow Flight gRPC container port; clients connect to NodePort 30816, which forwards to it |
| `--data-dir` | `/mnt/db1/cassandra/data` | Host path to the database data directory, mounted read-only. Serves **exactly one** directory |
| `--data-root` | `/mnt` | Host path under which per-disk data dirs live; an init container scans it and warns on multi-disk layouts. It must exist: a missing one fails the pod at start |
| `--data-gid` | `999` | Host GID that owns the data directory; added as a pod `supplementalGroup`. The init container fails the pod at start, naming `--data-gid`, when the data directory, a keyspace dir or a table dir is not readable with it |
| `--otel-endpoint` | `http://$(HOST_IP):4317` | OTLP gRPC endpoint for metrics/traces (defaults to the OTel collector on the pod's node) |
| `--rollout-timeout` | `180` | Seconds `start` waits for the DaemonSet to roll out on every db node. The first start on a node pulls the image; raise this when the pull is slow |

## Multi-disk nodes

cqlite-flight serves exactly one data directory. On a multi-disk node where the database
spreads its data across `/mnt/db1`, `/mnt/db2`, … the SSTables on every disk other than
the served `--data-dir` are invisible, and reads that touch that data return partial or
empty results with no error. Each pod's `detect-multidisk` init container scans
`--data-root`; this layout check only warns, never blocking the rollout. It prints `OK` when the served dir is the only
data dir there (paths compared after dropping repeated slashes and resolving symlinks). It
warns when it finds more than one, naming the dirs that are not served,
and it warns that it cannot verify the layout when the root holds no data dir, or holds a
single one that is not the served dir. Read it with
`kubectl logs -l app.kubernetes.io/name=cqlite-flight -c detect-multidisk --prefix`.

```admonish warning title="Check the detect-multidisk log before you trust row counts"
A multi-disk layout does not fail `start`: the pods roll out and queries succeed, with rows
missing. Before you trust a row count, a result set or a throughput figure from a node with
more than one data disk, read every pod's `detect-multidisk` log and confirm each one says `OK`:

    kubectl logs -l app.kubernetes.io/name=cqlite-flight -c detect-multidisk --prefix

A `WARNING` line on any node means its results are partial.
```

When the rollout fails, `start` prints this note with a hint naming `--data-dir`, `--data-root`
and `--data-gid`; a healthy start does not print it.
Every lab run to date uses single-disk nodes.

## Lifecycle

```bash
easy-db-lab cqlite-flight start       # roll out the DaemonSet
easy-db-lab cqlite-flight status      # show running pods and endpoints
easy-db-lab cqlite-flight stop        # remove the DaemonSet and its NodePort Service
easy-db-lab cqlite-flight uninstall   # remove all kit resources
```

## Related kits

- The [Trino](install-trino.md) kit's `cqlite` catalog — reads through these Flight pods, giving
  `SELECT * FROM cqlite.<keyspace>.<table>` addressing.
- [trino-loadtest](trino-loadtest.md) — drives concurrent read load against that catalog.
