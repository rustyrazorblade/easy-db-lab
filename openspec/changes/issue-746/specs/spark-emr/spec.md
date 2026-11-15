## ADDED Requirements

### Requirement: Spark job state is a project-owned vocabulary

`spark status` and every Spark service result SHALL report a job's state as a project-owned state (`PENDING`, `CANCEL_PENDING`, `RUNNING`, `COMPLETED`, `CANCELLED`, `FAILED`, `INTERRUPTED`, `UNKNOWN`), mapped from the EMR step state inside the EMR adapter. A state that the SDK version does not know, or a missing state, SHALL map to `UNKNOWN`.

#### Scenario: Every EMR step state maps unchanged

- **GIVEN** a step in each EMR `StepState` value
- **WHEN** `spark status` reports it
- **THEN** the state shown is the same name as today
- **AND** the failed-step branch runs for `FAILED` only, as today

#### Scenario: Unknown EMR state

- **GIVEN** a step whose EMR state is `UNKNOWN_TO_SDK_VERSION` or absent
- **WHEN** `spark status` reports it
- **THEN** the state shown is `UNKNOWN`, and the command does not fail
