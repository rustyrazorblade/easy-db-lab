## MODIFIED Requirements

### Requirement: Collision detection is configurable per phase
`collision-check` in `kit.yaml` SHALL accept either a boolean or a map of phase names to booleans. Only the `install` and `start` phases have a collision check; any other key, or a non-boolean value, SHALL be rejected when `kit.yaml` loads. `true` SHALL guard both `install` and `start`; `false` SHALL guard neither; a map SHALL guard exactly the phases set to `true`. The `install` guard SHALL fail the install with a non-zero exit and an error-worded `CollisionDetected` event when the kit's scaffold already exists, unless `--force` is given. The `start` guard SHALL check the cluster for the kit's running workload (selected by its `runtime` block) before any start step runs, and SHALL fail with a non-zero exit and a `Kit.CollisionDetected` event naming what it found. After a successful `stop` of a kit whose `start` is guarded, the CLI SHALL wait until the kit's workload is gone, so an immediate `start` is not refused; if it does not go within the timeout, `stop` SHALL fail with a `Kit.StopIncomplete` event naming what is left.

#### Scenario: Top-level boolean guards install and start
- **WHEN** `collision-check: true` is set at the top level and the kit is already installed and running
- **THEN** a second install fails with a non-zero exit and `CollisionDetected`
- **AND** `start` fails with a non-zero exit and `Kit.CollisionDetected` before any start step runs

#### Scenario: Per-phase collision check
- **WHEN** `collision-check: {start: true, install: false}` is set
- **THEN** the `start` phase checks for collision; a second install succeeds

#### Scenario: Unknown phase is rejected
- **WHEN** `collision-check: {uninstall: true}` is set
- **THEN** loading `kit.yaml` fails with an error naming the unsupported phase

#### Scenario: Stop then start
- **WHEN** a collision-checked kit is stopped and started again immediately
- **THEN** `stop` returns only after the kit's workload is gone AND the `start` is not refused
