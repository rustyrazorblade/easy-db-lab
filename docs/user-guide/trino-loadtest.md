# trino-loadtest

The `trino-loadtest` kit is a bench kit that drives concurrent read load against a running
[Trino](install-trino.md) kit's `cqlite` catalog and reports throughput and latency. It
runs a fixed-size pool of persistent Trino connections, each issuing queries against
`cqlite.<keyspace>.<table>` (or a custom query file) for a configured duration, and pushes
per-interval stats (qps, rows/s, p50/p99 latency, error rate) to VictoriaMetrics.

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
- A [Trino](install-trino.md) kit is installed and running, with the
  [cqlite-trino](cqlite-trino.md) overlay registering the `cqlite` catalog
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

## Metrics

During a run, per-interval stats are pushed to VictoriaMetrics, labelled by instance
(`kit`):

| Metric | Description |
|--------|-------------|
| `trino_loadtest_qps` | Queries per second |
| `trino_loadtest_rows_per_sec` | Rows returned per second |
| `trino_loadtest_lat_p50_ms` | 50th percentile latency (ms) |
| `trino_loadtest_lat_p99_ms` | 99th percentile latency (ms) |
| `trino_loadtest_errors_per_second` | Errors per second |

## Related kits

- [cqlite-trino](cqlite-trino.md) — registers the `cqlite` catalog this kit queries.
- [cqlite-flight](cqlite-flight.md) — the Arrow Flight data plane behind that catalog.
