# trino-loadtest

The `trino-loadtest` kit is a bench kit that drives concurrent read load against a running
[Trino](install-trino.md) kit's `cqlite` catalog and reports throughput and latency. It
runs a fixed-size pool of persistent Trino connections, each issuing queries against
`cqlite.<keyspace>.<table>` (or a custom query file) for a configured duration, and pushes
per-interval stats (qps, rows/s, p50/p99 latency, error rate) to the metrics store (Mimir).

```admonish note title="Waiting on the cqlite catalog"
The Trino kit's `cqlite` catalog is planned, not shipped: its connector plugin delivery is
deferred to `pmcfadin/cqlite#2869`. Until it lands, this kit cannot run end to end.
```

```admonish warning title="Offline, read-only, flushed-SSTables-only load"
This kit only reads. It issues `SELECT` queries against the `cqlite` catalog, which reads
**flushed SSTable files** through [cqlite-flight](cqlite-flight.md), not the live
database:

- **Read-only / offline.** No writes are issued and no live database read path is
  exercised.
- **Flushed-only.** Only data already flushed to SSTables is queried; unflushed writes are
  invisible. Run `nodetool flush` on the target keyspace before load-testing if you need
  recent data.
- **Eventually stale.** Results reflect whatever SSTables existed on disk during the run,
  changed by compaction and new flushes over time — not a single consistent view.
```

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- A [Trino](install-trino.md) kit is installed and running, with its
  [`cqlite` catalog](install-trino.md#the-cqlite-catalog-offline-sstable-reads) wired.
- The target keyspace/table has flushed SSTables to read

## Quick Start

```bash
# Install pointed at your running trino kit
easy-db-lab kit install trino-loadtest --target trino

# Drive read load against a cqlite table
easy-db-lab trino-loadtest-trino start --ks my_keyspace --tbl my_table
```

Like other bench kits, `trino-loadtest` installs as `trino-loadtest-<target>`, so multiple
instances can run against different Trino kits at once. See
[Bench kits](kits.md#bench-kits--benchmarking-a-database).

## Flags

Install-time:

| Flag | Default | Description |
|---|---|---|
| `--target` | (required) | Name of the running trino kit to load-test |

`start` flags:

| Flag | Default | Description |
|---|---|---|
| `--ks` | (required unless `--queries-file`) | cqlite keyspace to query |
| `--tbl` | (required unless `--queries-file`) | cqlite table to query |
| `--queries-file` | (unset) | Path to a file with one SQL query per line; overrides the built-in scan+aggregate default set |
| `--threads` | `4` | Number of concurrent persistent Trino connections |
| `--duration` | `60` | Load duration in seconds |
| `--interval` | `10` | Interval stats reporting period in seconds |
| `--traceparent` | `false` | Attach a random W3C traceparent header to every query, for end-to-end Tempo traces |
| `--snapshot-check-cmd` | (unset) | Command run with `bash -c` on your machine after the run, printing the raw `nodetool listsnapshots` listing of every db node; the run fails if any `cqlite-` snapshot remains |
| `--snapshot-check-timeout-s` | `60` | Whole seconds `--snapshot-check-cmd` may run before the check fails |

## Snapshot-leak check

A cqlite snapshot-mode read takes a per-query snapshot on the db node and must remove it.
`--snapshot-check-cmd` checks that none is left. The command runs on your machine, not in
the driver pod, after the load finishes. Your machine reaches the db nodes through the
workspace `ssh` wrapper, so a loop over the node aliases works:

```bash
easy-db-lab trino-loadtest-trino start --ks my_keyspace --tbl my_table \
  --snapshot-check-cmd 'for h in db0 db1 db2; do ssh "$h" nodetool listsnapshots; done'
```

Print the raw listing and do not pipe it through `grep`. The kit matches `cqlite-` names
itself, and a command that exits non-zero fails the check. That includes `grep` on a clean
cluster. If the check is unset, the run reports `snapshot-leak check: SKIPPED`.

## Metrics

During a run, per-interval stats are pushed to the OpenTelemetry Collector on the control
node and stored in Mimir, labelled by instance (`kit`) and `cluster`. A run that exits
non-zero (bad arguments, a failed run, a snapshot leak) fails `start`. `stop` deletes the
instance's driver pods and ConfigMaps.

| Metric | Description |
|--------|-------------|
| `trino_loadtest_qps` | Queries per second |
| `trino_loadtest_rows_per_sec` | Rows returned per second |
| `trino_loadtest_lat_p50_ms` | 50th percentile latency (ms) |
| `trino_loadtest_lat_p99_ms` | 99th percentile latency (ms) |
| `trino_loadtest_errors_per_second` | Errors per second |

## Related kits

- The [Trino](install-trino.md) kit's `cqlite` catalog — the catalog this kit queries.
- [cqlite-flight](cqlite-flight.md) — the Arrow Flight data plane behind that catalog.
