## ADDED Requirements

### Requirement: Cancel a running or pending Spark job

The system SHALL provide a `spark stop` CLI command that cancels a running or pending EMR step without terminating the cluster.

#### Scenario: Cancel a specific job by step ID

- **WHEN** the user runs `spark stop --step-id s-XXXXX`
- **THEN** the system MUST call the EMR `CancelSteps` API with `TERMINATE_PROCESS` strategy for that step
- **AND** emit an event with the step ID and the cancellation status returned by EMR

#### Scenario: Cancel the most recent job

- **WHEN** the user runs `spark stop` without a `--step-id`
- **THEN** the system MUST resolve the most recent step on the cluster (same as `spark status` default)
- **AND** cancel that step using the EMR `CancelSteps` API

#### Scenario: Cluster validation before cancellation

- **WHEN** the user runs `spark stop`
- **THEN** the system MUST validate the EMR cluster exists and is accessible before attempting cancellation
- **AND** fail with an error if the cluster is not found or in an invalid state

#### Scenario: Report cancellation result

- **WHEN** the EMR `CancelSteps` API returns a response
- **THEN** the system MUST display the step ID and the cancel status (e.g., `SUBMITTED`, `FAILED`)
- **AND** display the reason if the cancellation failed (e.g., step already completed)
