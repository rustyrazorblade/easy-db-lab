# cqlite-flight

The `cqlite-flight` kit deploys the [`cqlite-flight`](https://github.com/pmcfadin/cqlite)
Arrow Flight server as a DaemonSet co-located with your database nodes — one pod per
`type=db` node, each reading that node's own local SSTables. It is the data plane that the
[cqlite-trino](cqlite-trino.md) connector talks to: Trino asks each Flight pod for the
SSTable data on its node, and cqlite-flight streams it back as Arrow record batches.

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
its pod. The Flight gRPC port is bound directly on the node (`hostNetwork`), reachable at
`<db-node-private-ip>:<flight-port>`.

## Flags

| Flag | Default | Description |
|---|---|---|
| `--tag` | `latest` | `cqlite-flight` image tag (`ghcr.io/pmcfadin/cqlite-flight:<tag>`) |
| `--flight-port` | `8815` | Arrow Flight gRPC listen port (host + container) |
| `--data-dir` | `/mnt/db1/cassandra/data` | Host path to the database data directory, mounted read-only. Serves **exactly one** directory |
| `--data-root` | `/mnt` | Host path under which per-disk data dirs live; an init container scans it and warns on multi-disk layouts |
| `--data-gid` | `999` | Host GID that owns the data directory; added as a pod `supplementalGroup` |
| `--otel-endpoint` | `http://localhost:4317` | OTLP gRPC endpoint for metrics/traces (defaults to the node-local OTel collector) |

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
easy-db-lab cqlite-flight stop        # remove the DaemonSet
easy-db-lab cqlite-flight uninstall   # remove all kit resources
```

## Related kits

- [cqlite-trino](cqlite-trino.md) — registers a Trino `cqlite` catalog that reads through
  these Flight pods, giving `SELECT * FROM cqlite.<keyspace>.<table>` addressing.
- [trino-loadtest](trino-loadtest.md) — drives concurrent read load against that catalog.
