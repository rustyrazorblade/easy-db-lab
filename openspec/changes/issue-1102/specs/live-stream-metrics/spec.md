## MODIFIED Requirements

### Requirement: Stress job completion

Each cycle, `metrics stream` SHALL list the stress jobs in the cluster through the same reuse-only route as its queries; it SHALL never start or replace a tunnel to do so.  For each job it saw running in an earlier cycle that has now finished, it SHALL emit `Stress.JobCompleted(jobName, host, outcome, durationSeconds)` once.  `host` is the alias of the node the stress pod ran on.  `outcome` is `succeeded` or `failed`, from the Kubernetes job status.  `durationSeconds` is the job's start time to its completion time.  A failure to list the jobs SHALL be logged and that cycle's check skipped; it SHALL NOT count as a metric failure or change the cycle's samples. A failure that no retry fixes (a missing exec auth plugin, a 401 or 403) SHALL also emit `Stress.JobListFailing(reason)` once per run of such failures, with the Kubernetes handler's detail and remedy as its reason; none is emitted while the stream is stopping.

#### Scenario: Job succeeds
- **WHEN** a stress job the stream saw running finishes successfully
- **THEN** one `Stress.JobCompleted` is emitted with its job name, host, `outcome=succeeded` and its duration in seconds

#### Scenario: Job fails
- **WHEN** a stress job the stream saw running finishes as failed
- **THEN** one `Stress.JobCompleted` is emitted with `outcome=failed`

#### Scenario: Job already finished at start
- **WHEN** a stress job had already finished before the stream started
- **THEN** no `Stress.JobCompleted` is emitted for it

#### Scenario: Job deleted while running
- **WHEN** a running stress job is deleted, for example by `cassandra stress stop`
- **THEN** no `Stress.JobCompleted` is emitted for it

#### Scenario: Job list fails
- **WHEN** listing the stress jobs fails in a cycle
- **THEN** the failure is logged, no `Stress.JobCompleted` is emitted in that cycle, and the cycle's samples and metric failures are unchanged
- **AND** a job that finished during the failure is reported in the next cycle whose list succeeds


#### Scenario: Job list fails for good
- **WHEN** listing the stress jobs fails in consecutive cycles because the exec auth plugin is missing, or with a 401 or 403
- **THEN** one `Stress.JobListFailing` is emitted at the first of them, naming the cause and the remedy, and none at the others
- **AND** after a list succeeds, the next such failure emits it again
- **AND** a transient list failure emits none
