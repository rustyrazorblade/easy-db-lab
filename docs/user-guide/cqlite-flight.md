# cqlite-flight

The `cqlite-flight` kit deploys the [`cqlite-flight`](https://github.com/pmcfadin/cqlite)
Arrow Flight server as a DaemonSet co-located with your database nodes — one pod per
`type=db` node, each reading that node's own local SSTables. It is the data plane that the
Trino kit's planned [`cqlite` catalog](install-trino.md#the-cqlite-catalog-offline-sstable-reads)
will talk to: Trino will ask each Flight pod for the SSTable data on its node, and
cqlite-flight streams it back as Arrow record batches. That Trino catalog is not shipped yet
— its connector-plugin delivery is deferred to `pmcfadin/cqlite#2869` — but the cqlite-flight
data plane described here is fully functional today.

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
easy-db-lab kit install cqlite-flight
easy-db-lab cqlite-flight start
easy-db-lab cqlite-flight status
```

`cqlite-flight start` rolls out one Arrow Flight pod per db node (a DaemonSet with
`nodeSelector: type=db`) and mounts each node's Cassandra data directory read-only into
its pod. The pods run on the pod network. Clients reach the Flight gRPC port through the
`cqlite-flight-nodeport` Service at `<db-node-private-ip>:30815`. That Service is node-local
(`externalTrafficPolicy: Local`): a db node's IP always reaches that node's own pod, because
that pod is the only one holding the node's SSTables.

```admonish warning title="Needs the database on the node itself"
cqlite-flight reads the data directory from the db node's filesystem through a read-only
`hostPath` mount. It works only where the database runs directly on the node (the VM path,
e.g. Cassandra installed on the db nodes), and the cluster must allow `hostPath` volumes. It
cannot read a database that runs in Kubernetes pods with its own volumes.
```

## Flags

| Flag | Default | Description |
|---|---|---|
| `--tag` | `latest` | `cqlite-flight` image tag (`ghcr.io/pmcfadin/cqlite-flight:<tag>`) |
| `--flight-port` | `8815` | Arrow Flight gRPC container port; clients connect to NodePort 30815, which forwards to it |
| `--data-dir` | `/mnt/db1/cassandra/data` | Host path to the database data directory, mounted read-only. Serves **exactly one** directory |
| `--data-root` | `/mnt` | Host path under which per-disk data dirs live; an init container scans it and warns on multi-disk layouts |
| `--data-gid` | `999` | Host GID that owns the data directory; added as a pod `supplementalGroup` |
| `--otel-endpoint` | `http://$(HOST_IP):4317` | OTLP gRPC endpoint for metrics/traces (defaults to the OTel collector on the pod's node) |

## Multi-disk nodes

cqlite-flight serves exactly one data directory. On a multi-disk node where the database
spreads its data across `/mnt/db1`, `/mnt/db2`, … the SSTables on every disk other than
the served `--data-dir` are invisible, and reads that touch that data return partial or
empty results with no error. The `--data-root` init container scans for extra data dirs
and prints a loud (non-fatal) warning so this misconfiguration is visible rather than
silent. Every lab run to date uses single-disk nodes.

## Lifecycle

```bash
easy-db-lab cqlite-flight start       # roll out the DaemonSet
easy-db-lab cqlite-flight status      # show running pods and endpoints
easy-db-lab cqlite-flight stop        # remove the DaemonSet and its NodePort Service
easy-db-lab cqlite-flight uninstall   # remove all kit resources
```

## Related kits

- The [Trino](install-trino.md) kit's planned `cqlite` catalog — will read through these
  Flight pods, giving `SELECT * FROM cqlite.<keyspace>.<table>` addressing. It is planned as
  a catalog property file of the trino kit and is **not shipped yet**; its connector-plugin
  delivery is deferred to `pmcfadin/cqlite#2869`.
- [trino-loadtest](trino-loadtest.md) — drives concurrent read load against that catalog.
