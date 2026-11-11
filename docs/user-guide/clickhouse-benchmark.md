# clickhouse-benchmark

The `clickhouse-benchmark` kit runs ClickHouse's own
[`clickhouse-benchmark`](https://clickhouse.com/docs/operations/utilities/clickhouse-benchmark)
tool against a running [ClickHouse](clickhouse.md) kit, over the ClickHouse native protocol. It is
a bench kit: it deploys no database, but targets the `clickhouse` kit you have already started. The
[ClickBench](https://github.com/ClickHouse/ClickBench) dataset and its 43 queries are built in, and
you can run your own query file instead.

The benchmark runs in a pod on an app node, so it does not compete with the database it measures.
Every 10 seconds the kit pushes the tool's figures to Mimir, so you can watch throughput and
latency live in Grafana.

## Prerequisites

- The cluster is up (`easy-db-lab up`) and has an app node (`init --app.count 1` or more). Every pod of
  this kit runs on an app node (`type=app`). With no app node, `prepare` and `start` fail before
  they create a pod, with `clickhouse-benchmark needs an app node (type=app); this cluster has none`.
- The `clickhouse` kit is installed and started.

## Quick Start

```bash
easy-db-lab kit install clickhouse
easy-db-lab clickhouse start

easy-db-lab kit install clickhouse-benchmark --target clickhouse

easy-db-lab clickhouse-benchmark-clickhouse prepare
easy-db-lab clickhouse-benchmark-clickhouse start
easy-db-lab clickhouse-benchmark-clickhouse stop
```

The kit installs as `clickhouse-benchmark-<target>`. The target must declare a `native` and an
`http` endpoint; `kit install` refuses a target that lacks one and names every missing type. See
[Bench kits](kits.md#bench-kits--benchmarking-a-database) for how cross-kit targeting works.

## Flags

| Flag | Command | Default | Description |
|---|---|---|---|
| `--target` | install | (required) | Name of the running ClickHouse kit to benchmark |
| `--version` | install | `latest` | `clickhouse/clickhouse-server` image version for the benchmark and prepare pods |
| `--workload` | prepare | `clickbench` | Dataset to load; `clickbench` is the only one |
| `--source` | prepare | the public ClickBench files | S3 URL of the parquet files to load; see [Loading from your own S3 files](#loading-from-your-own-s3-files) |
| `--storage-policy` | prepare | `default` | Storage policy of the `hits` table (`default`, `s3_main`, `s3_tier`) |
| `--recreate` | prepare | `false` | Drop an existing `hits` table and load it again |
| `--workload` | start | `clickbench` | Packaged query set to run; give this or `--queries`, not both |
| `--queries` | start | (none) | Local file of queries to run instead of a packaged set |
| `--concurrency` | start | `4` | Number of queries run at the same time |
| `--duration` | start | `60` | Stop starting queries after this many seconds (`0` = no time limit) |
| `--iterations` | start | `0` | Stop after this many queries (`0` = no limit) |
| `--randomize` | start | `true` | Run the queries in random order; `--randomize=false` runs them in file order |
| `--continue-on-errors` | start | `false` | Keep running when a query fails, instead of stopping the run |
| `--settings` | start | (none) | ClickHouse settings for every query, as `key=value,key=value` |

`--target` and `--version` are set at install time. The `prepare` and `start` flags apply to one
run, so you can change them from run to run without reinstalling.

## Lifecycle

### prepare

```bash
easy-db-lab clickhouse-benchmark-clickhouse prepare
easy-db-lab clickhouse-benchmark-clickhouse prepare --storage-policy s3_main --recreate
```

`prepare` creates the ClickBench `hits` table on every replica (`ON CLUSTER clickhouse`, as a
`ReplicatedMergeTree` table) with the storage policy you give, and then loads it on the server with
`INSERT INTO hits SELECT * FROM s3(...)`. The target has one shard, so every replica holds the whole
table. The load runs from a prepare pod with no client-side time limit, so a long load is waited
out. When the load is done, `prepare` waits for every replica to catch up and checks that they all
hold the same number of rows. It fails, and prints each replica's count, when they differ.

Before it creates the pod, `prepare` checks the target over its HTTP endpoint:

- When `hits` already exists, `prepare` fails, names the table and its storage policy, and tells
  you to use `--recreate`. It does not change the table. With `--recreate` it drops `hits` on every
  replica first.
- While a benchmark run of this instance is in progress, `prepare` fails and names the run pod. Run
  `stop`, or wait for the run to finish.

When a statement fails, `prepare` shows the server's error and exits non-zero. It never drops
`hits`. When the load itself fails, it also says `hits may be partly loaded; run prepare --recreate
to reload it`.

`hits` lives on the target's disks. When you stop and start the `clickhouse` kit, the table is gone
(issue 985), so run `prepare` again before the next `start`.

#### Loading from your own S3 files

With no `--source`, the server reads the public ClickBench files,
`https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet`,
anonymously. A `--source` you give is read with the cluster's IAM role
(`use_environment_credentials`), so it must be in a bucket that role can read; the role can read
the `easy-db-lab-*` buckets. Recent ClickHouse servers refuse to use the server's own credentials
in a query unless the setting `s3_allow_server_credentials_in_user_queries` is on, so for a
`--source` load `prepare` turns that setting on for the `INSERT` on servers that have it. The
anonymous default load never uses it. The files must have the ClickBench `hits` columns. A `--source` that
holds a `'` or a `\` is refused before any pod is created.

### start

```bash
easy-db-lab clickhouse-benchmark-clickhouse start
easy-db-lab clickhouse-benchmark-clickhouse start --concurrency 16 --duration 300 --randomize=false
```

`start` runs the 43 ClickBench queries against the target's native endpoint, from a pod on an app
node, and streams the tool's output to your terminal. With the ClickBench queries it first checks
that `hits` exists and tells you to run `prepare` if it does not.

`--duration` and `--iterations` can both be set; the run stops at the first limit it reaches.

Only one run per instance can be in progress. A second `start` fails while a run pod of the same
instance is pending or running; a finished run does not block the next one.

When the run pod ends, `start` reads the pod's exit code. When it is not 0, `start` says the run
failed, gives the exit code and exits non-zero. Without `--continue-on-errors`, the first query
that fails ends the run with the server's error code.

#### Your own queries

```bash
easy-db-lab clickhouse-benchmark-clickhouse start --queries my-queries.sql
```

`--queries` sends a file from your machine to the run pod, so it works from a Homebrew install. Put
one query per line; a query cannot span lines. A relative path is relative to the current directory.
`start` does not check for `hits` when you give `--queries`. The file and the run's scripts must fit
in one ConfigMap, 1,000,000 bytes.

#### Settings

```bash
easy-db-lab clickhouse-benchmark-clickhouse start --settings max_threads=8,use_query_cache=0
```

Each `key=value` entry becomes `--key=value` on the `clickhouse-benchmark` command line, so it
applies to every query. A value cannot hold a comma. A key must be letters, digits and `_`, so use
`max_threads`, not `max-threads`. These keys are refused, because `start` sets them itself or the
kit does not support them: `host`, `port`, `user`, `password`, `database`, `secure`,
`queries_file`, `query`, `concurrency`, `iterations`, `timelimit`, `delay`, `json`, `randomize`,
`continue_on_errors`, `cumulative`, `reconnect`, `query_id`, `confidence`, `stage`,
`max_consecutive_errors` and `help`.

### stop

```bash
easy-db-lab clickhouse-benchmark-clickhouse stop
```

`stop` deletes this instance's benchmark and prepare pods and their ConfigMaps. It leaves `hits` in
place and does not touch other kits' pods.

## Results

When a run ends, `start` writes `last-run.txt` into the kit's workspace directory (for example
`clickhouse-benchmark-clickhouse/last-run.txt`). It holds the run's settings, the exit code and
the final report: queries executed, QPS, rows and MiB per second, and the latency percentiles. A
failed run writes it too. Each run replaces the file.

The results come from the run pod's full log. The kubelet rotates pod logs at about 10 MiB; the
final report is printed last, so it is always kept, but early interval reports of a long run can be
missing from the log.

## Dashboard

The first successful `start` installs the **clickhouse-benchmark** dashboard into a Grafana folder
for the kit, so the dashboard shows data from the second run on. Its panels show the ten gauges the
kit pushes for each 10-second report:

- `clickhouse_benchmark_qps`, `clickhouse_benchmark_rows_per_second` and
  `clickhouse_benchmark_mib_per_second`: throughput in the interval.
- `clickhouse_benchmark_queries_executed`: queries run so far in the run.
- `clickhouse_benchmark_errors`: queries that failed in the interval.
- `clickhouse_benchmark_latency_p50_ms`, `_p90_ms`, `_p95_ms`, `_p99_ms` and `_p999_ms`: query
  latency in milliseconds.

Every gauge carries `kit=<instance>`, so the instance filter separates benchmark instances. A link
goes to the `clickhouse` dashboard for the same cluster, to see the server side of the same run.
