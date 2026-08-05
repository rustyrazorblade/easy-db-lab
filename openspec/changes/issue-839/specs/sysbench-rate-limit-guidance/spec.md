## ADDED Requirements

### Requirement: CLI help text warns about over-capacity `--rate` abort behavior

The sysbench kit's `--rate` flag description (`kit.yaml`, rendered verbatim by `kit info sysbench`) SHALL state that setting `--rate` well above the target's sustainable capacity causes sysbench's internal rate-limiter queue to overflow and the run to hard-abort within seconds, rather than sustaining a high-latency overload window, and SHALL point the user to the user guide for the recommended approach to overload/latency testing.

#### Scenario: User inspects sysbench flag help
- **WHEN** a user runs `easy-db-lab kit info sysbench`
- **THEN** the `--rate` flag's description mentions that over-capacity rates cause a fast
  hard-abort (not a sustained overload window) and references the docs for guidance

### Requirement: User guide explains rate-limiting semantics and overload-testing guidance

`docs/user-guide/sysbench.md` SHALL include a section that explains what `--rate` does, why a rate set above the target's sustainable capacity causes sysbench to hard-abort (`FATAL: event queue is full`) rather than degrade gracefully, and the recommended alternative for overload/latency testing — thread-bound runs (`--rate=0` with a chosen `--threads`) or a rate deliberately set closer to measured capacity rather than far above it.

#### Scenario: User reads the sysbench user guide for overload testing guidance
- **WHEN** a user reads `docs/user-guide/sysbench.md` looking for how to run an
  overload/latency test
- **THEN** they find an explanation of why over-capacity `--rate` values hard-abort instead
  of sustaining a high-latency window, and a recommended alternative (thread-bound runs or a
  capacity-relative rate)

### Requirement: Flags table lists all `start` command flags

The Flags table in `docs/user-guide/sysbench.md` SHALL list every flag accepted by the sysbench kit's `start` command as declared in `kit.yaml`, including `--rate`, `--skip-trx`, and `--rand-type`.

#### Scenario: User checks the Flags table for a start-command flag
- **WHEN** a user consults the Flags table in `docs/user-guide/sysbench.md` for any flag
  declared under the `start` command in `kit.yaml`
- **THEN** that flag, its default, and its description appear in the table
