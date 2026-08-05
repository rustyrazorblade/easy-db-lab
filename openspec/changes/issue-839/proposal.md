## Why

sysbench's `--rate` flag limits target transactions/sec, but when a user sets it well above
what the target cluster can actually sustain, sysbench's own internal rate-limiter event
queue overflows and the run hard-aborts (`FATAL: event queue is full`) in under 15 seconds.
For anyone running an overload/latency test — deliberately pushing rate past capacity to
observe a sustained high-latency window — this is surprising: they expect degraded
performance, not an immediate crash. Nothing in the kit currently documents this behavior or
tells the user what to do instead. This was found during the TiDB×sysbench load-test
investigation (2026-07-21); see GitHub issue #839.

## What Changes

- Update the `--rate` flag's `description` in the sysbench kit's `kit.yaml` (rendered
  verbatim as CLI help text by `kit info sysbench`) to warn that rates well above cluster
  capacity cause a fast hard-abort rather than a sustained overload window, and to point at
  the docs for the recommended approach.
- Add a "Rate limiting and overload testing" subsection to `docs/user-guide/sysbench.md`
  explaining: what `--rate` does, why over-capacity rates hard-abort instead of degrading
  gracefully, and the recommended alternative for overload/latency testing — thread-bound
  runs (`--rate=0` with a chosen `--threads`) or a rate deliberately set closer to measured
  capacity rather than far above it.
- Add the currently-missing `--rate`, `--skip-trx`, and `--rand-type` flags to the Flags
  table in `docs/user-guide/sysbench.md` (pre-existing gap in the same table this change is
  already editing; all three are real `start` args in `kit.yaml` today but none appear in
  the table).
- No code, behavior, or interface changes. `--rate` continues to pass straight through to
  the sysbench binary unmodified — this is a documentation and CLI-help-text change only.

## Capabilities

### New Capabilities

- `sysbench-rate-limit-guidance`: documents the observable, user-facing contract for the
  sysbench kit's `--rate` flag — that CLI help text and the user guide must explain the
  hard-abort-on-overflow behavior and the recommended alternative. This is scoped narrowly
  to the guidance/documentation contract for `--rate`, not a spec for the sysbench kit as a
  whole (which has no existing OpenSpec capability and is out of scope here).

### Modified Capabilities

None — `--rate`'s runtime behavior (passthrough to the sysbench binary, unmodified) does
not change. No existing spec's behavioral requirements change.

## Impact

- `src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/kit.yaml` — `--rate`
  flag description text (also affects `kit info sysbench` CLI output).
- `docs/user-guide/sysbench.md` — Flags table + new explanatory subsection.
- No code, tests, or runtime behavior affected.
