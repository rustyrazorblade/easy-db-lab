#!/usr/bin/env python3
"""Unit tests for the trino-loadtest kit's ``driver.py``.

Lives OUTSIDE the shipped kit resource directory
(``src/main/resources/.../kits/trino-loadtest/``) on purpose: the kit ships
``driver.py`` only, and ``InstallTemplateResolverTest`` asserts
``test_driver.py`` is absent from the packaged resources. This copy is a
build-time verification artifact, wired into ``./gradlew check`` via the
``testTrinoLoadtestDriver`` Gradle task (mirroring the packer/cassandra shell
test tasks) so a regression in the driver's pure logic — its argument-error
contract, percentile math, cumulative stats separation, or snapshot-leak
parsing — fails the build.

Uses only the standard-library ``unittest`` runner: ``driver.py`` imports the
``trino`` package lazily, so the module loads and every function under test
runs with no third-party dependency and no cluster reachable.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
import unittest
from argparse import Namespace
from pathlib import Path

# Import the shipped driver.py directly from the kit resource directory by path
# (its directory name contains a hyphen, so it is not importable as a package).
_REPO_ROOT = Path(__file__).resolve().parents[4]
_DRIVER_PATH = (
    _REPO_ROOT
    / "src/main/resources/com/rustyrazorblade/easydblab/kits/trino-loadtest/driver.py"
)
_spec = importlib.util.spec_from_file_location("trino_loadtest_driver", _DRIVER_PATH)
assert _spec is not None and _spec.loader is not None
driver = importlib.util.module_from_spec(_spec)
# Register before exec so the module's @dataclass decorators can resolve their
# own annotations via sys.modules[cls.__module__] (Python 3.14).
sys.modules[_spec.name] = driver
_spec.loader.exec_module(driver)


def _args(**overrides) -> Namespace:
    """A Namespace of otherwise-valid args, with fields overridden per test."""
    base = dict(
        queries_file=None,
        keyspace="ks",
        table="tbl",
        threads=4,
        duration=60,
        interval=10.0,
        snapshot_check_cmd=None,
        snapshot_check_timeout_s=60.0,
    )
    base.update(overrides)
    return Namespace(**base)


class ValidateArgsTest(unittest.TestCase):
    """Each failure class returns its own distinct message; a valid config passes."""

    def test_valid_args_return_none(self) -> None:
        self.assertIsNone(driver.validate_args(_args()))

    def test_queries_file_satisfies_missing_ks_tbl(self) -> None:
        # --queries-file makes --ks/--tbl optional.
        self.assertIsNone(
            driver.validate_args(_args(queries_file="/q.sql", keyspace="", table=""))
        )

    def test_missing_ks_or_tbl_without_queries_file(self) -> None:
        self.assertEqual(
            driver.validate_args(_args(keyspace="", table="")),
            "--ks/--tbl are required unless --queries-file is given",
        )
        self.assertEqual(
            driver.validate_args(_args(table="")),
            "--ks/--tbl are required unless --queries-file is given",
        )

    def test_threads_below_one(self) -> None:
        self.assertEqual(
            driver.validate_args(_args(threads=0)), "--threads must be >= 1"
        )

    def test_duration_below_one(self) -> None:
        self.assertEqual(
            driver.validate_args(_args(duration=0)), "--duration must be >= 1"
        )

    def test_interval_not_positive(self) -> None:
        self.assertEqual(
            driver.validate_args(_args(interval=0)), "--interval must be > 0"
        )

    def test_blank_snapshot_check_cmd_rejected(self) -> None:
        # Guards the vacuous-pass bug #2399: a whitespace-only command would
        # shell-run as a silent no-op and read as "0 leaks found".
        self.assertEqual(
            driver.validate_args(_args(snapshot_check_cmd="   ")),
            "--snapshot-check-cmd must not be blank/whitespace-only",
        )

    def test_nonfinite_or_nonpositive_timeout_rejected(self) -> None:
        # inf silently removes the timeout entirely; <=0 defeats the bound.
        for bad in (0.0, -1.0, float("inf"), float("nan")):
            self.assertEqual(
                driver.validate_args(_args(snapshot_check_timeout_s=bad)),
                "--snapshot-check-timeout-s must be a finite value > 0",
                msg=f"timeout {bad!r} should be rejected",
            )


class MainExitCodeTest(unittest.TestCase):
    """main() surfaces a validation failure as exit code 2, before doing work."""

    @staticmethod
    def _main_quiet(argv: list[str]) -> int:
        # main() prints the error to stderr; swallow it so the runner output
        # stays clean while we assert on the exit code.
        with contextlib.redirect_stderr(io.StringIO()):
            return driver.main(argv)

    def test_main_returns_2_on_validation_error(self) -> None:
        # Missing --ks/--tbl with no --queries-file: rejected at validate_args,
        # so main() never reaches the connect/worker path.
        self.assertEqual(self._main_quiet(["--threads", "1", "--duration", "1"]), 2)

    def test_main_returns_2_on_bad_threads(self) -> None:
        self.assertEqual(
            self._main_quiet(["--ks", "k", "--tbl", "t", "--threads", "0"]), 2
        )


class PercentileTest(unittest.TestCase):
    """Linear-interpolation percentile over a sorted list, matching numpy's default."""

    def test_empty_returns_zero(self) -> None:
        self.assertEqual(driver.percentile([], 50), 0.0)

    def test_single_element(self) -> None:
        self.assertEqual(driver.percentile([42.0], 99), 42.0)

    def test_two_element_bounds_and_median(self) -> None:
        self.assertEqual(driver.percentile([10.0, 20.0], 0), 10.0)
        self.assertEqual(driver.percentile([10.0, 20.0], 100), 20.0)
        self.assertEqual(driver.percentile([10.0, 20.0], 50), 15.0)

    def test_interpolated_between_samples(self) -> None:
        # rank = 3 * 0.5 = 1.5 -> interpolate halfway between index 1 (10) and 2 (20).
        self.assertEqual(driver.percentile([0.0, 10.0, 20.0, 30.0], 50), 15.0)


class StatsCollectorTest(unittest.TestCase):
    """Interval drains must not disturb the run-cumulative totals (bug N2)."""

    def test_interval_snapshot_drains_and_resets(self) -> None:
        stats = driver.StatsCollector()
        stats.record_success(5.0, 10)
        stats.record_error()
        snap = stats.snapshot_and_reset()
        self.assertEqual(snap.queries, 2)
        self.assertEqual(snap.rows, 10)
        self.assertEqual(snap.errors, 1)
        self.assertEqual(snap.latencies_ms, [5.0])
        # Second drain sees an empty interval.
        empty = stats.snapshot_and_reset()
        self.assertEqual(empty.queries, 0)
        self.assertEqual(empty.rows, 0)
        self.assertEqual(empty.errors, 0)
        self.assertEqual(empty.latencies_ms, [])

    def test_cumulative_survives_repeated_drains(self) -> None:
        stats = driver.StatsCollector()
        stats.record_success(1.0, 3)
        stats.snapshot_and_reset()  # drain interval 1
        stats.record_success(2.0, 4)
        stats.record_error()
        stats.snapshot_and_reset()  # drain interval 2
        stats.record_success(3.0, 5)

        cumulative = stats.cumulative_snapshot()
        self.assertEqual(cumulative.queries, 4)  # 3 successes + 1 error
        self.assertEqual(cumulative.rows, 12)  # 3 + 4 + 5
        self.assertEqual(cumulative.errors, 1)
        self.assertEqual(sorted(cumulative.latencies_ms), [1.0, 2.0, 3.0])

    def test_cumulative_snapshot_is_read_only(self) -> None:
        # A second cumulative_snapshot must return the same totals (never resets).
        stats = driver.StatsCollector()
        stats.record_success(1.0, 1)
        first = stats.cumulative_snapshot()
        second = stats.cumulative_snapshot()
        self.assertEqual((first.queries, first.rows), (second.queries, second.rows))
        self.assertEqual(second.queries, 1)

    def test_record_error_captures_cause_and_resets_on_drain(self) -> None:
        # OBS-2: the underlying error cause must be sampled (type + message) so a
        # 100%-failure run is diagnosable, not just a bare err count. The sample
        # is interval-scoped: it drains and resets with the rest of the interval.
        stats = driver.StatsCollector()
        stats.record_error(ValueError("bad column 'x'"))
        snap = stats.snapshot_and_reset()
        self.assertEqual(snap.errors, 1)
        self.assertEqual(snap.last_error, "ValueError: bad column 'x'")
        # After a drain the next interval starts with no error sample.
        self.assertIsNone(stats.snapshot_and_reset().last_error)

    def test_record_error_without_exception_has_no_sample(self) -> None:
        # Counting-only callers (no exception passed) still increment errors but
        # leave last_error unset — nothing to report.
        stats = driver.StatsCollector()
        stats.record_error()
        snap = stats.snapshot_and_reset()
        self.assertEqual(snap.errors, 1)
        self.assertIsNone(snap.last_error)


class FindLeakedSnapshotsTest(unittest.TestCase):
    """Only lines whose first token starts with the prefix count as leaks."""

    _LISTING = (
        "Snapshot Details:\n"
        "Snapshot Name Keyspace Column Family True Size Size on disk\n"
        "cqlite-abc123 my_ks my_tbl 1.2 KiB 2.0 KiB\n"
        "cqlite-def456 my_ks other 3.4 KiB 4.0 KiB\n"
        "some_other_snap my_ks my_tbl 5.0 KiB 6.0 KiB\n"
        "\n"
        "Total TrueDiskSpaceUsed: 9.6 KiB\n"
    )

    def test_extracts_only_prefixed_names(self) -> None:
        self.assertEqual(
            driver.find_leaked_snapshots(self._LISTING),
            ["cqlite-abc123", "cqlite-def456"],
        )

    def test_clean_listing_returns_empty(self) -> None:
        clean = (
            "Snapshot Details:\n"
            "There are no snapshots\n"
            "Total TrueDiskSpaceUsed: 0 bytes\n"
        )
        self.assertEqual(driver.find_leaked_snapshots(clean), [])

    def test_empty_output_returns_empty(self) -> None:
        self.assertEqual(driver.find_leaked_snapshots(""), [])

    def test_custom_prefix(self) -> None:
        listing = "foo-1 ks tbl\ncqlite-2 ks tbl\n"
        self.assertEqual(driver.find_leaked_snapshots(listing, prefix="foo-"), ["foo-1"])


class SnapshotLeakResultTest(unittest.TestCase):
    """An unrun check is never a pass; a run check passes only with zero leaks."""

    def test_unconfigured_check_does_not_pass(self) -> None:
        result = driver.run_snapshot_leak_check(None)
        self.assertFalse(result.ran)
        self.assertFalse(result.passed)

    def test_clean_run_passes(self) -> None:
        result = driver.run_snapshot_leak_check(lambda: "Total TrueDiskSpaceUsed: 0\n")
        self.assertTrue(result.ran)
        self.assertTrue(result.passed)
        self.assertEqual(result.leaked, [])

    def test_leak_run_fails(self) -> None:
        result = driver.run_snapshot_leak_check(lambda: "cqlite-leak1 ks tbl\n")
        self.assertTrue(result.ran)
        self.assertFalse(result.passed)
        self.assertEqual(result.leaked, ["cqlite-leak1"])


if __name__ == "__main__":
    unittest.main()
