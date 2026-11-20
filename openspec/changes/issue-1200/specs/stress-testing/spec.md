## MODIFIED Requirements

### Requirement: Stress Job Monitoring

The system MUST provide log access and job listing for stress operations.  `cassandra stress logs` MUST print each log line tagged with the name of the pod it came from (for example `[stress-job-1-abc12] Running workload...`), for every pod, also when the job has one pod, and lines of different pods MUST never share a line.  Before each pod's lines it MUST print a tagged line with the pod's status, and a pod with no logs MUST print a tagged `(no logs available yet)` line.  The output of `cassandra stress info`, `list` and `fields` MUST be printed as plain text with no tag.  None of this output MUST be emitted as an event; a pod whose logs cannot be read MUST still be reported as the `Stress.PodLogFailed` error event.

#### Scenario: List stress jobs

- **GIVEN** completed or running stress jobs
- **WHEN** the user lists jobs
- **THEN** all jobs are shown with their status.

#### Scenario: Request stress job logs

- **GIVEN** a stress job
- **WHEN** the user requests logs
- **THEN** aggregated log output from the job is displayed.

#### Scenario: Logs of two pods are tagged by pod

- **GIVEN** a stress job with pods `stress-job-1-abc12` and `stress-job-1-def34`
- **WHEN** the user runs `cassandra stress logs`
- **THEN** every log line starts with `[stress-job-1-abc12] ` or `[stress-job-1-def34] `
- **AND** Redis receives no event holding the log text

#### Scenario: Stress command output is plain text

- **WHEN** the user runs `cassandra stress info KeyValue`
- **THEN** the workload's description is printed with no tag, and no event carries it
