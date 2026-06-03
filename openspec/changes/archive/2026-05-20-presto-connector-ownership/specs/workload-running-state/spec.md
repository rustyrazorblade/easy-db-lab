## ADDED Requirements

### Requirement: ClusterState tracks running workloads
`ClusterState` SHALL include a `runningWorkloads: Set<String>` field persisted in `state.json`. The set contains the names of workloads that have been successfully started and not yet stopped. `WorkloadRunnerCommand` and EC2/SystemD service commands (Cassandra, OpenSearch) SHALL update this set on successful start or stop.

#### Scenario: Workload added to running set on start
- **WHEN** `easy-db-lab clickhouse start` completes successfully
- **THEN** `"clickhouse"` is present in `ClusterState.runningWorkloads`
- **AND** the updated state is persisted to `state.json`

#### Scenario: Workload removed from running set on stop
- **WHEN** `easy-db-lab clickhouse stop` completes successfully
- **THEN** `"clickhouse"` is absent from `ClusterState.runningWorkloads`
- **AND** the updated state is persisted to `state.json`

#### Scenario: Cassandra tracked in running state
- **WHEN** `easy-db-lab cassandra start` completes successfully
- **THEN** `"cassandra"` is present in `ClusterState.runningWorkloads`

### Requirement: Running workload state available as template variable
The set of running workloads SHALL be available to install templates and hook scripts as `__RUNNING_WORKLOADS__`, formatted as a comma-separated string of workload names.

#### Scenario: Template variable reflects running workloads
- **WHEN** cassandra and clickhouse are in `runningWorkloads`
- **AND** a hook script is executed
- **THEN** the environment variable `RUNNING_WORKLOADS` contains `"cassandra,clickhouse"` (order unspecified)
