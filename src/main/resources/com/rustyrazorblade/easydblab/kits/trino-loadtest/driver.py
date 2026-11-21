#!/usr/bin/env python3
"""trino-loadtest driver — concurrent read-load generator for a Trino coordinator.

Runs a fixed-size pool of threads, each holding one persistent Trino connection,
issuing queries against ``cqlite.<keyspace>.<table>`` (or a custom query file) for
a configured duration. Every ``--interval`` seconds it prints a single parseable
stats line (qps, rows/s, p50/p99 latency, error rate); the kit's start.sh scrapes
these lines out of ``kubectl logs -f`` and pushes them to the collector (Mimir) — see
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
    pod's log stream and push it to the collector (Mimir) as
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

    The initial ``connect_fn()`` is inside the try/except so a connection that
    fails outright (coordinator unreachable, auth rejected, bad host/port) is
    recorded as an error and surfaced to stderr, then the worker returns
    cleanly. Without this, a total connection failure would raise before any
    query ran and the process would end with ``queries: 0 errors: 0`` — a
    silent failure indistinguishable from "nothing was ever submitted".
    """
    try:
        conn = connect_fn()
    except Exception as exc:  # noqa: BLE001 - a failed connect is a diagnosable load-test outcome, not a crash
        stats.record_error(exc)
        print(
            f"trino-loadtest: worker connection failed: {type(exc).__name__}: {exc}",
            file=sys.stderr,
            flush=True,
        )
        return
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
    return 0


if __name__ == "__main__":
    sys.exit(main())
