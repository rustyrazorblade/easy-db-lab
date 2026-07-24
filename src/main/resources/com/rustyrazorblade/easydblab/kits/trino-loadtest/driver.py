#!/usr/bin/env python3
"""trino-loadtest driver — concurrent read-load generator for a Trino coordinator.

Runs a fixed-size pool of threads, each holding one persistent Trino connection,
issuing queries against ``cqlite.<keyspace>.<table>`` (or a custom query file) for
a configured duration. Every ``--interval`` seconds it prints a single parseable
stats line (qps, rows/s, p50/p99 latency, error rate); the kit's start.sh scrapes
these lines out of ``kubectl logs -f`` and pushes them to VictoriaMetrics — see
README.md for the full metric contract.

Networking (the real ``trino`` package, real sockets) is imported lazily inside
the small ``default_connect_fn``/``default_exec_fn`` factories at the bottom of
this file so that everything above them — the percentile math, stats
aggregation, query-file parsing, traceparent generation, and the worker loop
itself — can be imported and exercised by test_driver.py on a machine with no
`trino` package installed and no cluster reachable.
"""

from __future__ import annotations

import argparse
import math
import os
import random
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, List, Optional

DEFAULT_INTERVAL_SECONDS = 10
DEFAULT_PORT = 8080
DEFAULT_CATALOG = "cqlite"
DEFAULT_USER = "cqlite-loadtest"


# --------------------------------------------------------------------------
# Query sets
# --------------------------------------------------------------------------


def default_queries(keyspace: str, table: str) -> List[str]:
    """Built-in scan + aggregate query set for an arbitrary cqlite table.

    Kept schema-agnostic (no column names) so it works against any table: two
    LIMIT scans of different sizes plus a full-table COUNT(*) aggregate.
    """
    fq = f"cqlite.{keyspace}.{table}"
    return [
        f"SELECT * FROM {fq} LIMIT 100",
        f"SELECT * FROM {fq} LIMIT 1000",
        f"SELECT count(*) FROM {fq}",
    ]


def load_queries(path: Optional[str], keyspace: str, table: str) -> List[str]:
    """Load one SQL statement per non-blank, non-comment line from ``path``.

    Falls back to :func:`default_queries` when ``path`` is falsy.
    """
    if not path:
        return default_queries(keyspace, table)
    queries: List[str] = []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            queries.append(line)
    if not queries:
        raise ValueError(f"queries file {path!r} contained no usable SQL lines")
    return queries


# --------------------------------------------------------------------------
# W3C traceparent generation (issue #2107 optional flag)
# --------------------------------------------------------------------------


def random_traceparent() -> str:
    """Generate a random, spec-valid W3C ``traceparent`` header value.

    Format: ``00-<32 hex trace-id>-<16 hex span-id>-01``. A trace-id or
    span-id of all zeroes is invalid per the W3C spec, so both are re-rolled
    on the (astronomically unlikely) chance ``os.urandom`` returns all zero
    bytes.
    """
    trace_id = 0
    while trace_id == 0:
        trace_id = int.from_bytes(os.urandom(16), "big")
    span_id = 0
    while span_id == 0:
        span_id = int.from_bytes(os.urandom(8), "big")
    return f"00-{trace_id:032x}-{span_id:016x}-01"


# --------------------------------------------------------------------------
# Stats aggregation
# --------------------------------------------------------------------------


def percentile(sorted_values: List[float], pct: float) -> float:
    """Linear-interpolation percentile (numpy's default method) over an
    already-sorted list. Returns 0.0 for an empty input.
    """
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    rank = (len(sorted_values) - 1) * (pct / 100.0)
    lo = int(rank)
    hi = min(lo + 1, len(sorted_values) - 1)
    if lo == hi:
        return sorted_values[lo]
    lo_weight = sorted_values[lo] * (hi - rank)
    hi_weight = sorted_values[hi] * (rank - lo)
    return lo_weight + hi_weight


@dataclass
class IntervalStats:
    queries: int = 0
    rows: int = 0
    errors: int = 0
    latencies_ms: List[float] = field(default_factory=list)
    # OBS-2: a rate-limited sample (``<ExcType>: <message>``) of the most recent
    # per-query failure in this interval, so a failing run reports WHY, not just
    # a bare error count. ``None`` when no query errored (or the caller counted
    # an error without passing the exception).
    last_error: Optional[str] = None


class StatsCollector:
    """Thread-safe accumulator for both the current reporting interval and the
    run's true cumulative totals (issue N2).

    ``snapshot_and_reset`` drains the interval-only counters that
    ``reporter_loop`` reads every ``--interval`` seconds to print the ``[ Ns ]``
    rate line, then resets them to zero. A second, separate set of counters is
    never reset by that drain — it accumulates for the whole run — so the
    end-of-run ``[ final ]`` line (via :meth:`cumulative_snapshot`) reports real
    totals across every interval instead of just the last partial one.

    Latencies are accumulated in full for the cumulative view: at load-test
    volumes (bounded threads/duration) keeping every sample in memory is cheap,
    and it keeps the cumulative p50/p99 exact rather than an approximation.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._reset_interval_locked()
        self._cum_queries = 0
        self._cum_rows = 0
        self._cum_errors = 0
        self._cum_latencies_ms: List[float] = []

    def _reset_interval_locked(self) -> None:
        self._queries = 0
        self._rows = 0
        self._errors = 0
        self._latencies_ms: List[float] = []
        self._last_error: Optional[str] = None

    def record_success(self, latency_ms: float, row_count: int) -> None:
        with self._lock:
            self._queries += 1
            self._rows += row_count
            self._latencies_ms.append(latency_ms)
            self._cum_queries += 1
            self._cum_rows += row_count
            self._cum_latencies_ms.append(latency_ms)

    def record_error(self, exc: Optional[BaseException] = None) -> None:
        """Count a failed query, optionally sampling its cause.

        When ``exc`` is given, its ``<ExcType>: <message>`` string overwrites the
        interval's ``last_error`` sample (OBS-2). Overwriting — rather than
        keeping every message — is deliberate: the reporter prints one sample per
        interval, so retaining more would waste memory on the failure hot path.
        """
        with self._lock:
            self._queries += 1
            self._errors += 1
            self._cum_queries += 1
            self._cum_errors += 1
            if exc is not None:
                self._last_error = f"{type(exc).__name__}: {exc}"

    def snapshot_and_reset(self) -> IntervalStats:
        """Drain and reset the current interval's counters (used by ``reporter_loop``).

        Does not touch the cumulative counters — see :meth:`cumulative_snapshot`.
        """
        with self._lock:
            snap = IntervalStats(
                queries=self._queries,
                rows=self._rows,
                errors=self._errors,
                latencies_ms=self._latencies_ms,
                last_error=self._last_error,
            )
            self._reset_interval_locked()
            return snap

    def cumulative_snapshot(self) -> IntervalStats:
        """Read-only snapshot of the run's true totals across every interval.

        Never resets — safe to call once at end-of-run without disturbing the
        interval counters that ``reporter_loop`` still owns.
        """
        with self._lock:
            return IntervalStats(
                queries=self._cum_queries,
                rows=self._cum_rows,
                errors=self._cum_errors,
                latencies_ms=list(self._cum_latencies_ms),
            )


def format_interval_line(elapsed_s: int, threads: int, interval_s: float, snap: IntervalStats) -> str:
    """Format one periodic stats line.

    Matches ``^\\[ [0-9]+s \\]`` so the kit's start.sh can pick it out of the
    pod's log stream and push it to VictoriaMetrics as
    ``trino_loadtest_{qps,rows_per_sec,lat_p50_ms,lat_p99_ms,errors_per_second}``.
    """
    qps = snap.queries / interval_s if interval_s else 0.0
    rows_s = snap.rows / interval_s if interval_s else 0.0
    err_s = snap.errors / interval_s if interval_s else 0.0
    sorted_lat = sorted(snap.latencies_ms)
    p50 = percentile(sorted_lat, 50)
    p99 = percentile(sorted_lat, 99)
    return (
        f"[ {elapsed_s}s ] threads: {threads} qps: {qps:.2f} rows_s: {rows_s:.2f} "
        f"lat_p50_ms: {p50:.2f} lat_p99_ms: {p99:.2f} err_s: {err_s:.2f}"
    )


def format_final_line(threads: int, snap: IntervalStats) -> str:
    """Cumulative end-of-run summary. ``snap`` must be a true run-cumulative
    :class:`IntervalStats` (see :meth:`StatsCollector.cumulative_snapshot`),
    not a single interval's drain — ``reporter_loop`` resets the interval
    counters every ``--interval`` seconds, so a snapshot taken from those would
    only reflect the last partial interval. Deliberately does NOT match the
    ``[ Ns ]`` interval-line regex — it reports totals, not a per-interval
    rate, so start.sh must not scrape it as another metric sample.
    """
    sorted_lat = sorted(snap.latencies_ms)
    p50 = percentile(sorted_lat, 50)
    p99 = percentile(sorted_lat, 99)
    return (
        f"[ final ] threads: {threads} queries: {snap.queries} rows: {snap.rows} "
        f"lat_p50_ms: {p50:.2f} lat_p99_ms: {p99:.2f} errors: {snap.errors}"
    )


# --------------------------------------------------------------------------
# Worker loop (dependency-injected: no `trino` import here — see module docstring)
# --------------------------------------------------------------------------

ConnectFn = Callable[[], object]
ExecFn = Callable[[object, str, Optional[dict]], int]


def run_worker(
    connect_fn: ConnectFn,
    exec_fn: ExecFn,
    queries: List[str],
    stop_event: threading.Event,
    stats: StatsCollector,
    traceparent_enabled: bool,
) -> None:
    """One persistent-connection worker: connect once, then loop issuing
    random queries from ``queries`` until ``stop_event`` is set.
    """
    conn = connect_fn()
    try:
        while not stop_event.is_set():
            sql = random.choice(queries)
            headers = {"traceparent": random_traceparent()} if traceparent_enabled else None
            start = time.monotonic()
            try:
                row_count = exec_fn(conn, sql, headers)
                stats.record_success((time.monotonic() - start) * 1000.0, row_count)
            except Exception as exc:  # noqa: BLE001 - a failed query is a load-test data point, not a crash
                stats.record_error(exc)
    finally:
        close = getattr(conn, "close", None)
        if callable(close):
            close()


def reporter_loop(
    stats: StatsCollector,
    interval: float,
    threads: int,
    stop_event: threading.Event,
    start_time: float,
) -> None:
    next_tick = start_time + interval
    while not stop_event.is_set():
        sleep_for = next_tick - time.monotonic()
        if sleep_for > 0:
            stop_event.wait(sleep_for)
        if stop_event.is_set():
            break
        snap = stats.snapshot_and_reset()
        elapsed = int(round(time.monotonic() - start_time))
        print(format_interval_line(elapsed, threads, interval, snap), flush=True)
        # OBS-2: when this interval had failures, surface ONE sampled cause to
        # stderr alongside the rate line so a failing run is diagnosable without
        # per-query spam. stderr keeps it out of start.sh's stdout metric scrape.
        if snap.errors and snap.last_error is not None:
            print(
                f"[ {elapsed}s ] last_error ({snap.errors} this interval): {snap.last_error}",
                file=sys.stderr,
                flush=True,
            )
        next_tick += interval


# --------------------------------------------------------------------------
# Real Trino wiring (lazy `trino` import — only touched at actual run time)
# --------------------------------------------------------------------------


def make_default_connect_fn(host: str, port: int, user: str, catalog: str, schema: str) -> ConnectFn:
    def _connect() -> object:
        import trino.dbapi  # noqa: PLC0415 - deliberately lazy, see module docstring

        return trino.dbapi.connect(
            host=host,
            port=port,
            user=user,
            catalog=catalog,
            schema=schema,
            http_scheme="http",
        )

    return _connect


def default_exec_fn(conn: object, sql: str, headers: Optional[dict]) -> int:
    """Execute one query and return its row count.

    Uses the lower-level ``trino.client.TrinoQuery`` API (rather than the
    DBAPI ``Cursor``) because the DBAPI cursor does not expose a way to pass
    per-request HTTP headers, and the optional ``--traceparent`` flag needs a
    fresh header value on every query while reusing the same connection.
    ``Connection._create_request()`` is a semi-private helper on the trino
    client's own ``Connection`` class; there is no public equivalent as of
    trino-python-client's current dbapi surface.
    """
    import trino.client  # noqa: PLC0415 - deliberately lazy, see module docstring

    request = conn._create_request()  # noqa: SLF001 - see docstring above
    result = trino.client.TrinoQuery(request, query=sql).execute(additional_http_headers=headers)
    return sum(1 for _ in result)


# --------------------------------------------------------------------------
# Snapshot leak check (D12 hygiene, issue #2399)
# --------------------------------------------------------------------------
#
# Local mirror of the field's post-round hygiene check (#2367 round-9
# "Proposed standard metrics", D12): ``nodetool listsnapshots | grep cqlite-``
# must be empty on every node after a run — a cqlite snapshot-mode read takes
# a transient per-query Cassandra snapshot and must clean it up. This driver
# has no direct cluster/nodetool access (it runs in a plain Python pod that
# only talks to Trino), so the actual ``nodetool listsnapshots`` invocation is
# always operator-injected via ``--snapshot-check-cmd`` / the
# ``TRINO_LOADTEST_SNAPSHOT_CHECK_CMD`` env var (e.g. a ``kubectl exec``
# one-liner spanning every Cassandra pod). When it is not configured the
# check is reported as SKIPPED, never as a silent pass (issue #2399,
# "SHALL NOT pass vacuously").
#
# IMPORTANT — the injected command must emit the RAW ``nodetool listsnapshots``
# output; ``find_leaked_snapshots`` below does the ``grep cqlite-`` matching in
# Python. Do NOT bake ``| grep cqlite-`` (or any filter) into the command
# itself: a clean cluster makes ``grep cqlite-`` match nothing and exit 1 with
# empty output, and this check treats any nonzero exit as a FAILURE (the spec's
# non-negotiable "a nonzero exit ... SHALL be reported as a check failure,
# never 'ran cleanly, 0 snapshots found'" — an exit-1-empty grep is
# indistinguishable from an auth failure / unreachable node, so it cannot be
# whitelisted as a pass without reopening that vacuous-pass hole). A grep-style
# command would therefore false-FAIL a healthy ring. The metric's canonical
# ``| grep cqlite- == 0`` phrasing describes the human check; the driver's
# ``--snapshot-check-cmd`` wants the un-filtered listing (roborev finding,
# final branch review).

DEFAULT_SNAPSHOT_PREFIX = "cqlite-"
# The operator-injected probe (a kubectl-exec/nodetool one-liner spanning the
# ring) can wedge — an unreachable pod, a hung `kubectl exec`, a stuck pipe —
# and without a bound it would hang the driver forever AFTER the workload
# finished, so the D12 verdict would never be produced. Bound it and surface a
# timeout as a check FAILURE (same "no verdict" class as a nonzero exit),
# never a silent hang (roborev finding, final review round).
DEFAULT_SNAPSHOT_CHECK_TIMEOUT_S = 60


def find_leaked_snapshots(listsnapshots_output: str, prefix: str = DEFAULT_SNAPSHOT_PREFIX) -> List[str]:
    """Return the snapshot names in ``listsnapshots_output`` starting with ``prefix``.

    Mirrors ``nodetool listsnapshots | grep cqlite-`` against that command's
    tabular output: each line's first whitespace-separated token is treated
    as a candidate snapshot name. Header/footer lines (``Snapshot Details:``,
    column headers, ``Total ...`` summary lines, blank lines) never start
    with ``prefix`` so they are excluded without any special-casing.
    """
    leaked = []
    for line in listsnapshots_output.splitlines():
        fields = line.split()
        if fields and fields[0].startswith(prefix):
            leaked.append(fields[0])
    return leaked


@dataclass
class SnapshotLeakResult:
    """Outcome of the D12 check. ``ran=False`` means "no verdict" — a caller
    MUST NOT treat an unrun check as a pass (see :func:`run_snapshot_leak_check`).
    """

    ran: bool
    leaked: List[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.ran and not self.leaked


def run_snapshot_leak_check(
    list_snapshots_fn: Optional[Callable[[], str]],
    prefix: str = DEFAULT_SNAPSHOT_PREFIX,
) -> SnapshotLeakResult:
    """Run the D12 hygiene check by calling the injected ``list_snapshots_fn``.

    ``list_snapshots_fn`` is injected (never a hardcoded ``nodetool`` call) so
    this is unit-testable without a live cluster and so operators can point
    it at whatever cross-node listing mechanism their environment provides.
    ``None`` means unconfigured: the check has NOT run, and the result's
    ``ran`` flag is ``False`` so callers report a SKIP, not a pass.
    """
    if list_snapshots_fn is None:
        return SnapshotLeakResult(ran=False)
    output = list_snapshots_fn()
    return SnapshotLeakResult(ran=True, leaked=find_leaked_snapshots(output, prefix))


def make_default_list_snapshots_fn(
    cmd: str,
    timeout_s: float = DEFAULT_SNAPSHOT_CHECK_TIMEOUT_S,
) -> Callable[[], str]:
    """Build a ``list_snapshots_fn`` that runs the operator-provided shell ``cmd``.

    ``cmd`` is expected to print ``nodetool listsnapshots``-style output for
    every target node (e.g. a ``kubectl exec ... nodetool listsnapshots``
    one-liner covering the whole ring). Import ``subprocess`` lazily here,
    matching this module's lazy-import discipline for anything that touches
    the outside world (see the module docstring) — ``test_driver.py`` never
    needs it.

    A nonzero exit raises ``RuntimeError`` (never returns as if it were a
    clean, empty listing) — a probe that failed to run (auth failure,
    unreachable pod, typo'd command) must surface as a check failure, not as
    "0 snapshots found." Silently treating that as a pass is exactly the
    vacuous-pass bug #2399 exists to prevent (roborev finding, round-1 review).

    The probe is bounded by ``timeout_s`` (default
    :data:`DEFAULT_SNAPSHOT_CHECK_TIMEOUT_S`): a wedged one-liner (unreachable
    pod, hung ``kubectl exec``) would otherwise hang the driver indefinitely
    after the workload finished and never yield a D12 verdict. A timeout also
    raises ``RuntimeError`` so it lands in the same check-FAILURE path as a
    nonzero exit, never a silent hang (roborev finding, final review round).

    The command is launched in its own process group (``start_new_session``)
    and, on timeout, the WHOLE group is killed — a bare ``subprocess.run(...,
    shell=True, timeout=...)`` only kills the shell, leaving any child it
    spawned (``kubectl exec``, ``nodetool``) running and merely moving the hang
    off-driver instead of closing it (roborev finding, final review round).
    """
    import signal
    import subprocess

    def _run() -> str:
        proc = subprocess.Popen(  # noqa: S602 - operator-provided, trusted CLI arg
            cmd,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
        # start_new_session makes the shell its own session/process-group leader,
        # so its PGID == its PID. Capture it NOW, while the process is known
        # alive — resolving it later inside the timeout handler races with the
        # shell exiting first (which would raise ProcessLookupError and skip the
        # kill, leaving a live grandchild). Using the captured pgid closes that
        # ordering race structurally (roborev finding, final review round).
        pgid = proc.pid
        try:
            stdout, stderr = proc.communicate(timeout=timeout_s)
        except subprocess.TimeoutExpired as exc:
            # Kill the entire process group (the shell AND any children it
            # spawned), then reap — so a wedged `kubectl exec`/`nodetool`
            # cannot outlive the driver after we report the timeout. The
            # except is a defensive backstop (harmless if the group is already
            # gone), no longer the primary mechanism.
            try:
                os.killpg(pgid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            proc.wait()
            raise RuntimeError(
                f"snapshot-check-cmd timed out after {timeout_s}s (probe wedged; no D12 verdict)"
            ) from exc
        if proc.returncode != 0:
            detail = stderr.strip() or "(no stderr)"
            raise RuntimeError(f"snapshot-check-cmd exited {proc.returncode}: {detail}")
        return stdout

    return _run


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def _env_default(name: str, default: Optional[str] = None) -> Optional[str]:
    return os.environ.get(name, default)


def _env_flag(name: str, default: bool = False) -> bool:
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() in ("1", "true", "yes", "on")


def _int_env_default(name: str, default: int) -> int:
    """Read an integer env-var default defensively (issue #2130).

    Kubernetes injects Docker-link-style service env vars for every Service
    in the namespace (e.g. a Service named ``trino`` makes
    ``TRINO_PORT=tcp://10.43.x.x:8080`` available to every pod). Building an
    argparse *default* by eagerly calling ``int()`` on such a value crashes
    at parser-construction time — before the caller's explicit ``--port``
    flag (which start.sh always passes) ever gets a chance to win. Falling
    back to ``default`` on any parse failure keeps parser construction from
    ever raising, regardless of what happens to be sitting in the env.
    """
    val = os.environ.get(name)
    if val is None:
        return default
    try:
        return int(val)
    except ValueError:
        return default


def _float_env_default(name: str, default: float) -> float:
    """Float counterpart of :func:`_int_env_default` — same defensive fallback."""
    val = os.environ.get(name)
    if val is None:
        return default
    try:
        return float(val)
    except ValueError:
        return default


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Concurrent read-load driver for a Trino coordinator's cqlite catalog")
    # NOTE: the bare TRINO_HOST / TRINO_PORT / TRINO_USER / TRINO_CATALOG names
    # are deliberately NOT used here (issue #2130) — Kubernetes auto-injects
    # Docker-link-style env vars for every Service in the namespace (a Service
    # named "trino" makes TRINO_PORT=tcp://<ip>:8080 available to every pod),
    # which collides with exactly these generic names. Namespaced
    # TRINO_LOADTEST_* names match the rest of this kit's env vars and avoid
    # the collision entirely.
    parser.add_argument("--host", default=_env_default("TRINO_LOADTEST_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=_int_env_default("TRINO_LOADTEST_PORT", DEFAULT_PORT))
    parser.add_argument("--user", default=_env_default("TRINO_LOADTEST_USER", DEFAULT_USER))
    parser.add_argument("--catalog", default=_env_default("TRINO_LOADTEST_CATALOG", DEFAULT_CATALOG))
    parser.add_argument("--ks", "--keyspace", dest="keyspace", default=_env_default("TRINO_LOADTEST_KEYSPACE", ""))
    parser.add_argument("--tbl", "--table", dest="table", default=_env_default("TRINO_LOADTEST_TABLE", ""))
    parser.add_argument("--queries-file", dest="queries_file", default=_env_default("TRINO_LOADTEST_QUERIES_FILE"))
    parser.add_argument("--threads", type=int, default=_int_env_default("TRINO_LOADTEST_THREADS", 4))
    parser.add_argument("--duration", type=int, default=_int_env_default("TRINO_LOADTEST_DURATION", 60))
    parser.add_argument(
        "--interval",
        type=float,
        default=_float_env_default("TRINO_LOADTEST_INTERVAL", float(DEFAULT_INTERVAL_SECONDS)),
    )
    parser.add_argument(
        "--traceparent",
        action="store_true",
        default=_env_flag("TRINO_LOADTEST_TRACEPARENT"),
        help="attach a random W3C traceparent header to every query (default: off)",
    )
    parser.add_argument(
        "--snapshot-check-cmd",
        dest="snapshot_check_cmd",
        default=_env_default("TRINO_LOADTEST_SNAPSHOT_CHECK_CMD"),
        help=(
            "shell command printing RAW `nodetool listsnapshots` output for every "
            "target node (e.g. a kubectl-exec one-liner); if set, asserts zero "
            "cqlite- snapshots remain after the run (D12 hygiene, issue #2399) and "
            "exits nonzero on a leak. Emit the UNFILTERED listing — the driver does "
            "the `grep cqlite-` matching itself; do NOT pipe through `grep`, since a "
            "clean cluster makes grep exit 1 (empty) and any nonzero exit is treated "
            "as a check FAILURE, which would false-FAIL a healthy ring. Unset: the "
            "check is SKIPPED and reported as such, never a silent pass."
        ),
    )
    parser.add_argument(
        "--snapshot-check-timeout-s",
        dest="snapshot_check_timeout_s",
        type=float,
        default=_float_env_default(
            "TRINO_LOADTEST_SNAPSHOT_CHECK_TIMEOUT_S", float(DEFAULT_SNAPSHOT_CHECK_TIMEOUT_S)
        ),
        help=(
            "seconds to allow the --snapshot-check-cmd probe to run before it is "
            "treated as a check FAILURE (a wedged probe must not hang the driver; "
            f"default {DEFAULT_SNAPSHOT_CHECK_TIMEOUT_S}s, D12 hygiene, issue #2399)."
        ),
    )
    return parser


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    return build_arg_parser().parse_args(argv)


def validate_args(args: argparse.Namespace) -> Optional[str]:
    """Return an error message if ``args`` is unusable, else None."""
    if not args.queries_file and (not args.keyspace or not args.table):
        return "--ks/--tbl are required unless --queries-file is given"
    if args.threads < 1:
        return "--threads must be >= 1"
    if args.duration < 1:
        return "--duration must be >= 1"
    if args.interval <= 0:
        return "--interval must be > 0"
    if args.snapshot_check_cmd is not None and not args.snapshot_check_cmd.strip():
        # A whitespace-only command shell-executes as a silent no-op (exit 0, empty
        # stdout) — that would read as "ran cleanly, 0 leaks found" (PASS) despite the
        # D12 probe never actually touching a node. Reject it as a config error up
        # front instead of letting it degrade into the exact vacuous pass #2399 exists
        # to prevent (roborev finding, review round 2).
        return "--snapshot-check-cmd must not be blank/whitespace-only"
    if not math.isfinite(args.snapshot_check_timeout_s) or args.snapshot_check_timeout_s <= 0:
        # A non-positive timeout defeats the bound (0 = expire-immediately,
        # negative errors); `inf`/`nan` are worse — `inf` silently removes the
        # timeout entirely and reintroduces the indefinite-hang case the bound
        # exists to prevent. Require a real, finite, positive budget so a wedged
        # probe reliably surfaces as a FAIL (roborev finding, final review round).
        return "--snapshot-check-timeout-s must be a finite value > 0"
    return None


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    error = validate_args(args)
    if error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    queries = load_queries(args.queries_file, args.keyspace, args.table)
    print(
        f"trino-loadtest: target={args.host}:{args.port} catalog={args.catalog} "
        f"{len(queries)} quer{'y' if len(queries) == 1 else 'ies'}, {args.threads} threads, "
        f"{args.duration}s duration, interval={args.interval}s, "
        f"traceparent={'on' if args.traceparent else 'off'}",
        flush=True,
    )

    stats = StatsCollector()
    stop_event = threading.Event()
    start_time = time.monotonic()

    reporter = threading.Thread(
        target=reporter_loop,
        args=(stats, args.interval, args.threads, stop_event, start_time),
        daemon=True,
    )
    reporter.start()

    connect_fn = make_default_connect_fn(args.host, args.port, args.user, args.catalog, args.keyspace or "")
    threads = [
        threading.Thread(
            target=run_worker,
            args=(connect_fn, default_exec_fn, queries, stop_event, stats, args.traceparent),
        )
        for _ in range(args.threads)
    ]
    for t in threads:
        t.start()

    stop_event.wait(args.duration)
    stop_event.set()
    for t in threads:
        t.join()
    reporter.join(timeout=args.interval + 5)

    final = stats.cumulative_snapshot()
    print(format_final_line(args.threads, final), flush=True)

    # D12 hygiene check (issue #2399) — local mirror of the field's
    # `nodetool listsnapshots | grep cqlite-` == 0 post-run check.
    if not args.snapshot_check_cmd:
        print(
            "snapshot-leak check: SKIPPED (no --snapshot-check-cmd configured; D12, issue #2399)",
            flush=True,
        )
        return 0

    try:
        leak_result = run_snapshot_leak_check(
            make_default_list_snapshots_fn(args.snapshot_check_cmd, args.snapshot_check_timeout_s)
        )
    except Exception as exc:  # noqa: BLE001 - a probe command that FAILED to run (auth, unreachable
        # pod, typo'd one-liner) must surface as a check FAILURE, never be swallowed into an
        # empty-stdout "0 leaks" pass (that vacuous-pass gap was a roborev finding on this
        # change's first review round) or crash main() with a bare traceback.
        print(f"snapshot-leak check: FAIL - check command errored: {exc}", file=sys.stderr, flush=True)
        return 3

    if leak_result.leaked:
        print(
            f"snapshot-leak check: FAIL - {len(leak_result.leaked)} leaked snapshot(s): "
            f"{', '.join(leak_result.leaked)}",
            file=sys.stderr,
            flush=True,
        )
        return 3

    print("snapshot-leak check: PASS (0 cqlite- snapshots remain)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
