# trino-loadtest

A concurrent read-load driver against a running `trino` kit's `cqlite` catalog.
Each `start` runs one timestamped, labelled pod (`python:3.12-slim`, pinned by digest in `bin/start.sh`, + a pinned
`pip install trino` at pod start — no Java toolchain needed) that holds a pool of persistent
Trino connections and issues queries for a fixed duration, printing periodic
stats that `start.sh` scrapes and pushes, as OTLP gauges, to the OpenTelemetry
Collector on the control node, which stores them in Mimir.

## Install

This is a bench kit (`type: kit-ref` `--target`): it targets an already-running
`trino` kit and is installed as `trino-loadtest-<target>`, so you can run it
against multiple Trino installs at once if you have them.

```bash
easy-db-lab kit install trino-loadtest --target trino
```

`start` first checks that the target Trino has a `cqlite` catalog (`SHOW CATALOGS`
over the Trino HTTP API, through the workspace `curl` wrapper) and fails before any
pod runs when it has none. The trino kit does not ship that catalog yet
(pmcfadin/cqlite#2869).

## Usage

```bash
# Scan + count against cqlite.test_basic.simple_table, 8 threads, 2 minutes
easy-db-lab trino-loadtest-trino start --ks test_basic --tbl simple_table --threads 8 --duration 120

# Custom query file instead of the built-in default set
easy-db-lab trino-loadtest-trino start --queries-file /path/to/queries.sql --threads 16 --duration 300

# Attach a random W3C traceparent to every query, to see the full
# client -> Flight -> cqlite-core trace in Tempo
easy-db-lab trino-loadtest-trino start --ks test_basic --tbl simple_table --traceparent

easy-db-lab trino-loadtest-trino stop
```

### Args

| Flag | Variable | Default | Description |
|------|----------|---------|-------------|
| `--target` | `TARGET` | (required) | Name of the running `trino` kit to load-test |
| `--ks` | `TRINO_LOADTEST_KEYSPACE` | `""` | cqlite keyspace to query (ignored when `--queries-file` is set) |
| `--tbl` | `TRINO_LOADTEST_TABLE` | `""` | cqlite table to query (ignored when `--queries-file` is set) |
| `--queries-file` | `TRINO_LOADTEST_QUERIES_FILE` | `""` | Path on the control host to a file with one SQL query per line; overrides the built-in default set |
| `--threads` | `THREADS` | `4` | Number of concurrent, persistent Trino connections |
| `--duration` | `DURATION` | `60` | Load duration in seconds |
| `--interval` | `INTERVAL` | `10` | Interval stats reporting period in seconds |
| `--traceparent` | `TRACEPARENT` | `false` | Attach a random W3C `traceparent` header to every query (off by default) |
| `--snapshot-check-cmd` | `TRINO_LOADTEST_SNAPSHOT_CHECK_CMD` | (unset) | Command run with `bash -c` on your machine (not in the driver pod) after the run, printing the raw `nodetool listsnapshots` listing of every db node; the run fails if any `cqlite-` snapshot remains. Do **not** pipe it through `grep` (see the note below). Unset: SKIPPED. |
| `--snapshot-check-timeout-s` | `TRINO_LOADTEST_SNAPSHOT_CHECK_TIMEOUT_S` | `60` | Whole seconds `--snapshot-check-cmd` may run before the check fails and its processes are killed. |

Either `--ks`/`--tbl` or `--queries-file` is required (`--ks`/`--tbl` if you
want the built-in default query set; `--queries-file` for anything custom).

### Query-file format

One SQL statement per line, executed against `cqlite.<keyspace>.<table>` — or
any catalog/table you name directly, since queries are run verbatim. Blank
lines and lines starting with `#` are skipped:

```
# queries.sql
SELECT * FROM cqlite.test_basic.simple_table LIMIT 500
SELECT count(*) FROM cqlite.test_basic.simple_table
SELECT id, name FROM cqlite.test_basic.simple_table WHERE id = uuid() LIMIT 1
```

When no `--queries-file` is given, the driver falls back to a schema-agnostic
default set (works against any table, since it never references a column
name): two `LIMIT` scans of different sizes plus a full-table `COUNT(*)`.

### Traceparent injection

With `--traceparent`, every query gets a random, spec-valid W3C `traceparent`
HTTP header (`00-<32 hex trace-id>-<16 hex span-id>-01`) generated fresh per
query. This uses the trino-python-client's lower-level
`trino.client.TrinoQuery.execute(additional_http_headers=...)`, since the
DBAPI `Cursor.execute()` does not expose a way to set per-request headers —
see `driver.py`'s `default_exec_fn` docstring. Off by default; the read load
otherwise produces no client-side trace context.

### Snapshot-leak check (D12 hygiene, issue #2399)

After a run, `nodetool listsnapshots` must show no `cqlite-` snapshot on any
node. A cqlite snapshot-mode read takes a per-query Cassandra snapshot and must
remove it; one left behind is a leak.

`start.sh` runs the check on **your machine**, after the driver pod exits, with
`bash -c`. The driver pod has no `nodetool` and cannot reach the db nodes; your
machine can, because kit scripts have the workspace `bin/` first on `PATH`, so
`ssh db0` goes through the workspace ssh wrapper. The command must print the
raw `nodetool listsnapshots` listing of every db node, for example:

```bash
easy-db-lab trino-loadtest-trino start --ks my_ks --tbl my_tbl \
  --snapshot-check-cmd 'for h in db0 db1 db2; do ssh "$h" nodetool listsnapshots; done'
```

Do **not** pipe it through `grep cqlite-`: `start.sh` does that matching
itself, and on a clean cluster `grep` exits 1, which fails the check.

- No `cqlite-` snapshot remains: `snapshot-leak check: PASS`.
- A snapshot remains: `snapshot-leak check: FAIL - N leaked snapshot(s): ...`,
  and `start` fails.
- The command exits non-zero (an unreachable node, a typo): `FAIL` with its
  exit code and stderr, never a pass.
- The command exits 0 but prints nothing, or only whitespace: `FAIL - the
  check command printed nothing`. `nodetool listsnapshots` always prints
  something, even if only "There are no snapshots", so an empty listing means
  no node was listed (a loop over the wrong aliases), not a clean cluster.
- The command runs longer than `--snapshot-check-timeout-s` (default 60):
  `FAIL`, and every process it started is killed.
- A blank command or a timeout that is not a whole number above 0 fails
  `start` before any pod runs.
- Unset: `snapshot-leak check: SKIPPED`, never a silent pass.

## Metrics

Every `INTERVAL` seconds the driver prints one stats line to stdout, which
`start.sh` scrapes out of the pod log and pushes as OTLP gauges to the
collector on the control node with the shared kit library's `edl_push_gauges`
(the sysbench kit's path — the shell script parses the line with `awk`, not the
Python process itself). The collector adds the `cluster` label and stores them
in Mimir:

```
[ 30s ] threads: 8 qps: 143.20 rows_s: 21345.67 lat_p50_ms: 12.34 lat_p99_ms: 98.76 err_s: 0.20
```

Pushed metric names (label: `kit="trino-loadtest-<target>"`):

| Metric | Meaning |
|--------|---------|
| `trino_loadtest_qps` | Queries completed per second in the interval |
| `trino_loadtest_rows_per_sec` | Rows fetched per second in the interval |
| `trino_loadtest_lat_p50_ms` | Median per-query latency (connect excluded; query issue -> full fetch) |
| `trino_loadtest_lat_p99_ms` | p99 per-query latency, same window |
| `trino_loadtest_errors_per_second` | Failed queries per second in the interval |

A final `[ final ]` summary line with cumulative totals is printed at the end
of the run — it deliberately does not match the interval-line pattern, so
`start.sh` never pushes it as another metric sample.

### Viewing results in Grafana

These metrics land in Mimir like every other kit's push-based metrics (see `sysbench`'s dashboard for the same pattern). There is no bundled
dashboard here yet — either:

- Add a panel to an existing dashboard with a query like
  `trino_loadtest_qps{kit=~"trino-loadtest.*"}`, or
- Use Grafana Explore against the Mimir (`metrics_datasource`) datasource with the same
  query, filtering by `cluster=~"$cluster"` and `kit`.

`trino.rpc.*` / `cqlite.rpc.*` metrics from the coordinator and cqlite-flight
sit in the same Mimir tenant, so you can correlate load-test qps
against coordinator-side and Flight-side latency on one dashboard.

## Risks / caveats

- **PyPI at every start**: app nodes must reach `pypi.org` and
  `files.pythonhosted.org`. The pinned version keeps runs comparable, but it
  does not help when PyPI is unreachable: the pod's install fails and so does
  `start`.
- **Cold-start tax**: each pod runs `pip install --quiet trino==<pinned>` before the
  driver starts, adding roughly 5-15s (network-dependent) before the first
  query fires. This time is outside the driver's own `--duration` clock (the
  clock starts when `driver.py` runs, not when the pod starts), so it doesn't
  eat into the requested load window — it just delays when results appear in
  `kubectl logs -f` / Grafana. The pod logs
  `trino-loadtest: installing trino client <version> from PyPI...` first, so the
  wait is visible in the streamed log.
- **Per-query header injection uses a semi-private trino-python-client API**:
  `Connection._create_request()` and the lower-level `trino.client.TrinoQuery`
  class, because the public `dbapi.Cursor.execute()` has no
  `additional_http_headers` parameter. If a future trino-python-client release
  changes this internal shape, `--traceparent` (and only `--traceparent`; the
  non-traceparent path uses the same lower-level call for consistency) would
  need to be revisited.
- **No target-side capacity signal**: this kit only measures client-observed
  qps/latency/errors: it does not itself watch Trino coordinator/worker CPU or
  queueing, so a saturated coordinator shows up here only as rising latency —
  correlate with the `trino` kit's own dashboard for the full picture.
