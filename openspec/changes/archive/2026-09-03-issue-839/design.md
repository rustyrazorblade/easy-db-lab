## Context

The sysbench kit's `start.sh.template` passes `--rate="${RATE}"` straight through to the
`sysbench` binary running in a k8s pod (`kits/sysbench/bin/start.sh.template:37-47`). When
`--rate` is set well above what the target database can sustain, sysbench's own internal
rate-limiter event queue overflows and the process hard-aborts with `FATAL: event queue is
full` in under 15 seconds. This is sysbench's own behavior — easy-db-lab neither computes
nor controls it. The kit's docs (`docs/user-guide/sysbench.md`) and CLI help text
(`kit.yaml`) currently say nothing about this, and the Flags table in the docs is also
missing `--rate`, `--skip-trx`, and `--rand-type` entirely (all three are real `start` args
in `kit.yaml` today).

Found during the TiDB×sysbench load-test investigation (2026-07-21); see issue #839.

## Goals / Non-Goals

**Goals:**
- Make the hard-abort-on-overflow behavior discoverable *before* a user hits it, via both
  `sysbench-<target> start --help` output (kit.yaml description text) and the user guide.
- Recommend a concrete alternative for overload/latency testing: thread-bound runs
  (`--rate=0` with a chosen `--threads`) or a rate deliberately set closer to measured
  capacity.
- Close the pre-existing Flags table gap (`--rate`/`--skip-trx`/`--rand-type` missing)
  while already editing that table.

**Non-Goals:**
- No validation, pre-flight capacity checks, or rate/threads heuristics — see Decisions
  below for why this was explicitly rejected.
- No change to `start.sh.template`'s runtime behavior — sysbench's own error message
  remains the only in-terminal signal of the abort; this change only prepares the user to
  recognize and act on it, not to intercept it.
- No change to how `--rate` is parsed, validated, or passed to sysbench.

## Decisions

**Decision: documentation-only, no runtime/validation code (owner-approved).**

Two options were considered:
- **Docs only (chosen)**: update `kit.yaml`'s `--rate` description and
  `docs/user-guide/sysbench.md` to explain the behavior and recommend alternatives. No code
  changes.
- **Docs + runtime hint**: additionally pattern-match the literal `"FATAL: event queue is
  full"` line in `start.sh.template`'s log-streaming loop and echo a hint pointing at the
  docs.

The owner chose docs-only. Rationale: the precise threshold at which `--rate` overflows the
queue depends on live target capacity (hardware, workload mix, network) and sysbench's own
internal queue-sizing, which is not stable across sysbench versions and not derivable from
`--rate`/`--threads` alone at kit-config time — any pre-flight heuristic would be a guess
that risks false-positives blocking exactly the overload tests users are trying to run
(the whole point of overload testing is not knowing the breaking point in advance). sysbench
itself already fails fast (<15s); the gap is user expectation, not missing failure
detection — a documentation problem, not a code problem.

**Decision: document in both `kit.yaml` and the user guide, not just one.**

`kit.yaml`'s `--rate` `description` is rendered verbatim as picocli help text on the
installed instance's start command — `sysbench-<target> start --help` — via
`KitRunnerCommandFactory.argOptionSpec`. (Note: `kit info sysbench` does *not* show it;
`KitInfo.buildInfoText` renders only top-level `config.args`, and `--rate` lives under
`commands.start.args`.) The start command's help is where a user sees the flag while
choosing a value, so it carries a short warning. `docs/user-guide/sysbench.md` carries the fuller explanation (why it aborts instead
of degrading, and the recommended alternative) plus the completed Flags table. Putting the
full explanation only in one place would leave the other as a dead end for users who land
there first.

**Decision: fold in the pre-existing `--rate`/`--skip-trx`/`--rand-type` Flags-table gap.**

All three are real `kit.yaml` `start` args today but are missing from the docs Flags table.
Since the table is already being edited to add `--rate`'s row, adding the other two at the
same time avoids re-creating the identical gap for two flags immediately after closing it
for a third.

## Risks / Trade-offs

- **[Risk]** A user who doesn't read the flag description or docs still hits the same
  cryptic sysbench `FATAL` message with no in-terminal pointer to the explanation. →
  **Mitigation**: none in this change (by owner's explicit choice — see Decision above); a
  runtime hint was considered and deferred, not rejected outright, and remains available as
  future work if this proves insufficient in practice.
- **[Risk]** The `sysbench-rate-limit-guidance` capability spec is unusually narrow (a
  documentation contract rather than a runtime behavior contract), since no OpenSpec
  capability for the sysbench kit exists yet to hang a delta spec off of. → **Mitigation**:
  scoped explicitly to the `--rate` guidance contract only, not a stand-in for a full
  sysbench kit spec — a full sysbench capability spec is out of scope for this change and
  not implied by this one.

## Migration Plan

Not applicable — documentation and CLI help text only, no deployed state to migrate.

## Open Questions

None.
