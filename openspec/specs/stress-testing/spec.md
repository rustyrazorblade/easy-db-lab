# Stress Testing

## Purpose

Manages Cassandra stress job lifecycle including starting, stopping, monitoring, and log collection.
## Requirements
### Requirement: Stress Job Lifecycle

The system MUST support starting, stopping, and monitoring stress jobs against a Cassandra cluster.

#### Scenario: Start a stress job

- **GIVEN** a running Cassandra cluster
- **WHEN** the user starts a stress job with parameters
- **THEN** the job runs on designated stress nodes.

#### Scenario: Stop a stress job

- **GIVEN** a running stress job
- **WHEN** the user stops it
- **THEN** the job is terminated.

#### Scenario: Check stress job status

- **GIVEN** active stress jobs
- **WHEN** the user checks status
- **THEN** running jobs and their states are displayed.

### Requirement: Stress Job Monitoring

The system MUST provide log access and job listing for stress operations.

#### Scenario: List stress jobs

- **GIVEN** completed or running stress jobs
- **WHEN** the user lists jobs
- **THEN** all jobs are shown with their status.

#### Scenario: Request stress job logs

- **GIVEN** a stress job
- **WHEN** the user requests logs
- **THEN** aggregated log output from the job is displayed.

### Requirement: Observability Sidecars

The system MUST deploy observability sidecars alongside long-running stress jobs to collect metrics.

#### Scenario: OTel sidecar deployed with a long-running job

- **GIVEN** a long-running stress job
- **WHEN** the job starts
- **THEN** an OTel sidecar is deployed to scrape stress metrics and forward them to the cluster's metrics pipeline.

### Requirement: Stress job start reports its host

When `cassandra stress start` has the stress pod running, the `Stress.JobStarted` event it emits SHALL carry a `host` field: the alias of the node the stress pod runs on, for example `app0`.  The existing fields (`jobName`, `image`, `contactPoint`, `promPort`, `args`) SHALL stay.  `metrics stream` SHALL NOT emit `Stress.JobStarted`, so each start is reported once.

#### Scenario: Start reports the host
- **WHEN** `cassandra stress start` starts a job and its pod is running on `app0`
- **THEN** the `Stress.JobStarted` event carries `host=app0` along with its existing fields

