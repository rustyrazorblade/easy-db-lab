# Kit Running State

## Purpose

Tracks which kits have been successfully started and not yet stopped, persisting the set in cluster state and exposing it to install templates and hook scripts.

## Requirements

### Requirement: ClusterState tracks running kits
`ClusterState` SHALL include a `runningKits: Set<String>` field persisted in `state.json`. The set contains the names of kits that have been successfully started and not yet stopped. `WorkloadRunnerCommand` and EC2/SystemD service commands (Cassandra, OpenSearch) SHALL update this set on successful start or stop.

#### Scenario: Kit added to running set on start
- **WHEN** `easy-db-lab clickhouse start` completes successfully
- **THEN** `"clickhouse"` is present in `ClusterState.runningKits`
- **AND** the updated state is persisted to `state.json`

#### Scenario: Kit removed from running set on stop
- **WHEN** `easy-db-lab clickhouse stop` completes successfully
- **THEN** `"clickhouse"` is absent from `ClusterState.runningKits`
- **AND** the updated state is persisted to `state.json`

#### Scenario: Cassandra tracked in running state
- **WHEN** `easy-db-lab cassandra start` completes successfully
- **THEN** `"cassandra"` is present in `ClusterState.runningKits`

### Requirement: Running kit state available as template variable
The set of running kits SHALL be available to install templates and hook scripts as `__RUNNING_WORKLOADS__`, formatted as a comma-separated string of kit names.

#### Scenario: Template variable reflects running kits
- **WHEN** cassandra and clickhouse are in `runningKits`
- **AND** a hook script is executed
- **THEN** the environment variable `RUNNING_WORKLOADS` contains `"cassandra,clickhouse"` (order unspecified)
