# Sysbench

The `sysbench` kit runs [sysbench](https://github.com/akopytov/sysbench) OLTP benchmarks
against a running database kit. It is a bench kit: it does not deploy a database itself,
but targets one you've already started, connecting over the MySQL or PostgreSQL wire
protocol. Benchmark pods run inside the Kubernetes cluster, and per-interval results are
pushed to VictoriaMetrics so you can watch throughput and latency live in Grafana.

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- A database kit with the `sql` capability is installed and running (e.g. [TiDB](tidb.md))
- The target kit exposes a MySQL or PostgreSQL wire protocol endpoint — sysbench connects
  over the wire protocol, not JDBC

## Quick Start

```bash
# Start a database to benchmark
easy-db-lab kit install tidb
easy-db-lab tidb start

# Install sysbench pointed at it
easy-db-lab kit install sysbench --target tidb

# Load data, run the benchmark, clean up
easy-db-lab sysbench-tidb prepare
easy-db-lab sysbench-tidb start
easy-db-lab sysbench-tidb stop
```

The kit installs as `sysbench-<target>` — the instance name and the CLI subcommand both
include the target, so multiple sysbench instances can run against different databases
at the same time. See [Bench kits](kits.md#bench-kits--benchmarking-a-database) for how
cross-kit targeting works.

## Flags

| Flag | Default | Description |
|---|---|---|
| `--target` | (required) | Name of the running database kit to benchmark |
| `--threads` | `4` | Number of concurrent threads |
| `--duration` | `60` | Benchmark duration in seconds |
| `--workload` | `oltp_read_write` | sysbench built-in workload (`oltp_read_write`, `oltp_read_only`, `oltp_write_only`) |
| `--scale` | `10` | Number of rows per table, in thousands |
| `--tables` | `10` | Number of tables |
| `--rate` | `0` | Target transactions/sec (`0` = unlimited, thread-bound). See [Rate limiting and overload testing](#rate-limiting-and-overload-testing) |
| `--skip-trx` | `off` | Run statements in autocommit instead of `BEGIN`/`COMMIT` transactions (`on`/`off`) |
| `--rand-type` | `special` | Key access distribution: `uniform`, `gaussian`, `special`, or `pareto` |

Flags other than `--target` are baked in at install time and apply to every subsequent
`prepare`/`start`/`stop`. To change them, reinstall the kit.

## Lifecycle

### prepare

```bash
easy-db-lab sysbench-tidb prepare
```

Creates the `sbtest` database on the target if it doesn't exist, then loads the test
tables (`--tables` tables with `--scale` thousand rows each). Run this once before the
first benchmark run.

### start

```bash
easy-db-lab sysbench-tidb start
```

Runs the benchmark for `--duration` seconds, streaming sysbench's interval output to
your terminal. Each 10-second interval report (TPS, QPS, p99 latency, errors/s) is also
pushed to VictoriaMetrics.

When the run finishes, the final sysbench summary (SQL statistics, throughput, latency
percentiles, and errors) is written to `last-run.txt` in the kit's workspace directory
(e.g. `sysbench-tidb/last-run.txt`), prefixed with the run's parameters. Each run
overwrites the file, so a completed run's numbers survive after the terminal output
scrolls away.

### stop

```bash
easy-db-lab sysbench-tidb stop
```

Kills any running benchmark pod and runs sysbench cleanup, dropping the test tables from
the target database.

## Rate limiting and overload testing

By default (`--rate=0`) sysbench is thread-bound: each of the `--threads` worker threads
issues transactions as fast as the target will answer them, so throughput settles at
whatever the database can sustain. Setting `--rate` to a non-zero value switches sysbench
to a fixed target rate — it generates events on a schedule of that many transactions per
second and hands them to the worker threads, regardless of how fast the target is
actually responding.

That distinction matters when the requested rate exceeds what the target can sustain. The
generated events queue up faster than the workers can drain them, sysbench's internal
event queue fills, and the run hard-aborts with:

```
FATAL: event queue is full
```

This typically happens within the first few seconds, so a `--rate` set well above capacity
does not produce a sustained high-latency window — it produces a run that dies almost
immediately with no useful results.

For overload and latency testing, drive the target past its limit with concurrency instead
of with a target rate: leave `--rate=0` and raise `--threads` until latency climbs. A
thread-bound run applies backpressure naturally — slower responses mean fewer transactions
issued — so it degrades into a high-latency window rather than aborting. If you do want a
fixed rate, first measure the target's sustainable throughput with a thread-bound run, then
set `--rate` at or just above that measured number rather than far above it.

## Comparing Databases

Because each install is a separate named instance, you can benchmark several databases
simultaneously and compare them side by side in Grafana:

```bash
easy-db-lab kit install sysbench --target tidb
easy-db-lab kit install sysbench --target my-custom-db

easy-db-lab sysbench-tidb prepare && easy-db-lab sysbench-tidb start
easy-db-lab sysbench-my-custom-db prepare && easy-db-lab sysbench-my-custom-db start
```

Of the built-in kits, TiDB and ClickHouse expose MySQL and PostgreSQL wire endpoints.
Any [custom kit](kits.md#installing-a-custom-kit) that declares a `mysql` or
`postgresql` endpoint and the `sql` capability in its `kit.yaml` can be targeted the
same way.

Note that ClickHouse's wire interfaces parse queries as ClickHouse SQL, and sysbench's
built-in `oltp_*` workloads issue MySQL-specific DDL during `prepare` — running them
against ClickHouse unmodified will fail at table creation. Benchmarking ClickHouse with
sysbench requires a custom Lua workload with ClickHouse-compatible schemas.

## Metrics & Dashboard

The kit ships a **Sysbench Benchmark** Grafana dashboard, installed automatically. During
a run, these metrics are pushed to VictoriaMetrics, labelled by instance (`kit`):

| Metric | Description |
|--------|-------------|
| `sysbench_tps` | Transactions per second |
| `sysbench_qps` | Queries per second |
| `sysbench_lat_p99_ms` | 99th percentile latency (ms) — pushed per interval, plus a whole-run value at completion |
| `sysbench_lat_p95_ms` | 95th percentile latency (ms), whole-run |
| `sysbench_lat_p50_ms` | 50th percentile (median) latency (ms), whole-run |
| `sysbench_errors_per_second` | Errors per second |

The `kit` label carries the instance name (e.g. `sysbench-tidb`), so runs against
different targets plot as separate series on the same panel.

sysbench's interval reports only emit the single configured percentile (p99), so
p50/p95 cannot be sampled per interval. The kit runs sysbench with `--histogram` and
parses the final latency histogram to derive whole-run p50/p95/p99, pushed once when
the run completes.
