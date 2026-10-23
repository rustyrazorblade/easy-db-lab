# Sysbench

The `sysbench` kit runs [sysbench](https://github.com/akopytov/sysbench) OLTP benchmarks
against a running database kit. It is a bench kit: it does not deploy a database itself,
but targets one you've already started, connecting over the MySQL or PostgreSQL wire
protocol. Benchmark pods run inside the Kubernetes cluster, and per-interval results are
pushed to Mimir so you can watch throughput and latency live in Grafana.

## Prerequisites

- Cluster is up (`easy-db-lab up`) and has an app node (`init --app.count 1` or more). Every
  sysbench pod (the run, prepare, createdb and cleanup pods) runs on an app node (`type=app`), so
  it does not compete with the database it measures. With no app node, `prepare`, `start` and
  `stop` fail before they create a pod, with `sysbench needs an app node (type=app); this cluster
  has none`. A `stop` that fails this way leaves the kit marked as running.
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

`--target` is baked in at install time — to point sysbench at a different database, install
another instance. Every other flag is passed per invocation, so you can vary them run to run
without reinstalling:

```bash
easy-db-lab sysbench-tidb start --threads 32 --duration 300
```

## Lifecycle

### prepare

```bash
easy-db-lab sysbench-tidb prepare
```

On a MySQL wire target, creates the database the target's `mysql` endpoint names if it doesn't
exist (`sbtest` for TiDB, the `--database` install arg for SingleStore, `test` by default), then
loads the test tables (`--tables` tables with `--scale` thousand rows each) into it. A PostgreSQL
target's database must already exist. Run this once before the first benchmark run.

`prepare` fails, with the pod's log, when a pod exits non-zero or sysbench prints a `FATAL` error.

### start

```bash
easy-db-lab sysbench-tidb start
```

Runs the benchmark for `--duration` seconds, streaming sysbench's interval output to
your terminal. Each 10-second interval report (TPS, QPS, p99 latency, errors/s) is also
pushed to Mimir.

When the run finishes, the final sysbench summary (SQL statistics, throughput, latency
percentiles, and errors) is written to `last-run.txt` in the kit's workspace directory
(e.g. `sysbench-tidb/last-run.txt`), prefixed with the run's parameters and its exit code. Each
run overwrites the file, a failed run too, so a run's numbers survive after the terminal output
scrolls away and the file never shows an older run.

When the run pod ends, `start` reads its exit code. When sysbench exits non-zero (for example after
`FATAL: event queue is full`), `start` says the run failed, gives the exit code, names
`last-run.txt` (which then holds the end of the run's log when sysbench printed no summary) and
exits non-zero, and does not print `Run summary written to ...`. A run that prints a `FATAL` SQL
error fails `start` the same way, quoting the `FATAL` lines, even if sysbench exited 0, and
`last-run.txt` lists them. A run pod that cannot pull its image
fails `start` at once, with the reason and the pod's events; one that stays Pending fails it after
10 minutes.

Only one run per instance can be in progress. `start` fails with a collision error while a
run pod for the same instance (e.g. `sysbench-tidb`) is still pending or running; a finished
run does not block the next one. Instances pointed at different targets run side by side.

### stop

```bash
easy-db-lab sysbench-tidb stop
```

Kills any running benchmark pod and runs sysbench cleanup, dropping the test tables from
the target database. If the target database is already stopped, `stop` still finishes and
removes its pods; it prints why the cleanup did not finish (the cleanup pod's log, or its events if it never started) and says the test tables were left.

## Target passwords

When the target kit declares a password in its `sql` capability (SingleStore does, from its
`--password` install arg), every phase authenticates with it: the cleanup and the database
creation in `prepare`, the data load, the `start` run, and the `stop` cleanup. Nothing to pass:
the password comes from the target's installed `kit.yaml`. A password with spaces or `*`
works. sysbench never prints it.

The password never appears in a pod spec, so `kubectl get pod -o yaml` and
`kubectl describe pod` cannot show it. Each phase stores it in the Secret `<kit>-credentials`
(labelled `easydblab/role=sysbench-credentials`), and every pod reads it from there as an
environment variable: the sysbench pods as `TARGET_PASSWORD`, which the container hands to
sysbench as `--mysql-password` or `--pgsql-password` when it starts, and the database-creation
pod as `MYSQL_PWD`. `stop` deletes the Secret.

A target with no password (TiDB, PostgreSQL, ClickHouse) connects exactly as before.

## Ignored errors

sysbench ignores some MySQL errors by default and retries the transaction: deadlock (1213),
record changed since last read (1020) and lock wait timeout (1205). A target kit's `mysql` endpoint
can name more codes to ignore. sysbench then runs with `--mysql-ignore-errors` set to its defaults
plus the target's codes, for example `--mysql-ignore-errors=1213,1020,1205,1062`. SingleStore names
`1062`: concurrent `oltp_read_write` transactions on the same id hit duplicate-key errors there that
MySQL's row locks prevent, and without it a run with several threads ends at the first one with
`FATAL: ... error 1062 ... Duplicate entry`. sysbench counts every ignored error in its summary, as
`ignored errors: N`, so check that line in `last-run.txt` when you compare a SingleStore run with
another database. A target that names no codes (TiDB, PostgreSQL) keeps sysbench's defaults, and
any other SQL error there ends the run.

On SingleStore, `oltp_read_write` at the default 8 threads can also stall. Two transactions can
deadlock in a way SingleStore does not detect as a deadlock, so there is no error 1213. The run
then sits at zero throughput until `@@lock_wait_timeout` passes (60 seconds by default). The
waiting transaction then fails with error 1205, which sysbench ignores and retries, and the run
continues. See [SingleStore](singlestore.md#benchmarking-with-sysbench) for how to shorten the
stalls.

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

The abort is fast — under 15 seconds into the run in the case that prompted this section,
regardless of the `--duration` you asked for. A `--rate` set well above capacity does not
produce a sustained high-latency window; it produces a run that dies almost immediately
with no useful results.

An aborted run is easy to miss after the fact, because it does not look like a failure
downstream. `last-run.txt` holds only the seeded parameter header with no `SQL statistics`
block, and the run's p50/p95/p99 series on the Grafana dashboard flatline at 0 — which
reads as a suspiciously excellent result rather than a crash. If latency drops to zero and
the summary is truncated, check the pod output for the `FATAL` line.

For overload and latency testing, drive the target past its limit with concurrency instead
of with a target rate: leave `--rate=0` and raise `--threads` until latency climbs. A
thread-bound run applies backpressure naturally — slower responses mean fewer transactions
issued — so it degrades into a high-latency window instead of overflowing the event queue.
It is not immune to aborting for other reasons: sysbench still exits on unhandled SQL
errors, which a heavily overloaded target is more likely to return. If you do want a
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
a run, these metrics are pushed through the OTel collector to Mimir, labelled by instance (`kit`):

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
