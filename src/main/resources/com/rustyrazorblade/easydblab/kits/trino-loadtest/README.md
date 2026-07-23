# trino-loadtest

A concurrent read-load driver against a running `trino` kit's `cqlite` catalog.
Each `start` runs one timestamped, labelled pod (`python:3.12-slim` + `pip install
trino` at pod start — no Java toolchain needed) that holds a pool of persistent
Trino connections and issues queries for a fixed duration, printing periodic
stats that `start.sh` scrapes and pushes to VictoriaMetrics.

## Install

This is a bench kit (`type: kit-ref` `--target`): it targets an already-running
`trino` kit and is installed as `trino-loadtest-<target>`, so you can run it
against multiple Trino installs at once if you have them.

```bash
easy-db-lab kit install trino-loadtest --target trino
```

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
| `--snapshot-check-cmd` | `TRINO_LOADTEST_SNAPSHOT_CHECK_CMD` | (unset) | Shell command printing **raw** `nodetool listsnapshots` output for every target node; if set, the driver asserts zero `cqlite-` snapshots remain after the run and exits nonzero on a leak (D12 hygiene, issue #2399). Emit the unfiltered listing — the driver does the `cqlite-` matching itself; **do not** pipe through `grep` (see the note below). Unset: SKIPPED and reported as such, never a silent pass. |
| `--snapshot-check-timeout-s` | `TRINO_LOADTEST_SNAPSHOT_CHECK_TIMEOUT_S` | `60` | Seconds the `--snapshot-check-cmd` probe may run before it is treated as a check FAILURE — a wedged probe (unreachable pod, hung `kubectl exec`) must not hang the driver indefinitely with no D12 verdict. Must be `> 0`. |

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

The field's round-9 report (#2367) added a post-round hygiene check: after a
run, `nodetool listsnapshots | grep cqlite-` must be empty on every node — a
cqlite snapshot-mode read takes a transient per-query Cassandra snapshot and
must clean it up; anything still present after the run is a leak. This driver
runs in a plain Python pod with no direct cluster/`nodetool` access, so the
check is only performed when `--snapshot-check-cmd` names an operator-provided
shell command that prints **raw** `nodetool listsnapshots` output across every
node (a `kubectl exec` one-liner, typically). Provide the *unfiltered* listing:
the driver does the `cqlite-` matching itself, so **do not** bake `| grep
cqlite-` into the command. A clean cluster makes `grep cqlite-` match nothing
and exit `1` with empty output, and — per the spec's no-vacuous-pass rule —
any nonzero probe exit is a check FAILURE (an exit-1-empty grep is
indistinguishable from an auth failure or unreachable node, so it can't be
whitelisted as a pass); a grep-style command would therefore *false-FAIL a
healthy ring*. The metric's canonical `nodetool listsnapshots | grep cqlite-
== 0` phrasing describes the human check — feed the driver the raw listing and
let it apply the `cqlite-` filter. If configured, the driver prints
`snapshot-leak check: PASS`/`FAIL` (with the leaked names) after the run and
exits `3` on a leak. If not configured, it prints `snapshot-leak check:
SKIPPED` — deliberately never a silent pass, per the requirement that the
check must not appear green when it did not run
(`openspec/changes/standard-metrics-template/specs/round-validation-reporting/spec.md`).
**A configured check command that itself fails to run** (auth failure,
unreachable pod, typo'd one-liner — nonzero exit) is also `FAIL` (exit `3`,
with the command's stderr), never treated as "ran cleanly, 0 snapshots
found" — an empty result from a broken probe must not read as a clean ring.
A blank/whitespace-only `--snapshot-check-cmd` is rejected as a config error
(`--threads`/`--interval`-style `validate_args` check, exit `2`) rather than
silently accepted — it would otherwise shell-execute as a no-op and produce
the exact same false-clean result. The probe is bounded by
`--snapshot-check-timeout-s` (default 60s): a wedged one-liner that never
returns is reported as `FAIL` (exit `3`) on timeout rather than hanging the
driver forever after the workload finished with no D12 verdict.
See `docs/development/round-validation-metrics.md` for the full 14-point
standard this is one item of.

## Metrics

Every `INTERVAL` seconds the driver prints one stats line to stdout, which
`start.sh` scrapes out of `kubectl logs -f` and pushes to VictoriaMetrics
(mirroring the sysbench kit's push pattern — the shell script parses the line
with `awk`, not the Python process itself):

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

These metrics land in VictoriaMetrics like every other kit's push-based
metrics (see `sysbench`'s dashboard for the same pattern). There is no bundled
dashboard here yet — either:

- Add a panel to an existing dashboard with a query like
  `trino_loadtest_qps{kit=~"trino-loadtest.*"}`, or
- Use Grafana Explore against the VictoriaMetrics datasource with the same
  query, filtering by `cluster=~"$cluster"` and `kit`.

`trino.rpc.*` / `cqlite.rpc.*` metrics from the coordinator and cqlite-flight
sit in the same VictoriaMetrics instance, so you can correlate load-test qps
against coordinator-side and Flight-side latency on one dashboard.

## Risks / caveats

- **Cold-start tax**: each pod runs `pip install --quiet trino` before the
  driver starts, adding roughly 5-15s (network-dependent) before the first
  query fires. This time is outside the driver's own `--duration` clock (the
  clock starts when `driver.py` runs, not when the pod starts), so it doesn't
  eat into the requested load window — it just delays when results appear in
  `kubectl logs -f` / Grafana.
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
