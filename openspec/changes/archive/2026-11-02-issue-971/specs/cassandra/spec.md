## MODIFIED Requirements

### Requirement: Cluster Lifecycle

The system MUST support starting, stopping, and restarting Cassandra across cluster nodes. When starting, the system SHALL also deploy the Cassandra sidecar as a K3s DaemonSet after all Cassandra nodes are up. A stop of some db nodes SHALL NOT affect the db nodes that still run the database: the sidecar DaemonSet, the running-workload record and the kit post-stop hooks change only when no db node still runs the database.

#### Scenario: Sequential start with delay

- **GIVEN** a configured Cassandra version
- **WHEN** the user starts the cluster
- **THEN** nodes start sequentially with a configurable delay between them.

#### Scenario: Graceful stop

- **GIVEN** a running Cassandra cluster
- **WHEN** the user stops it
- **THEN** all nodes are stopped gracefully.

#### Scenario: Sidecar DaemonSet applied after start

- **WHEN** the user runs `cassandra start`
- **THEN** after all Cassandra nodes are up, the sidecar DaemonSet is applied to K3s.

#### Scenario: A stop of some db nodes keeps the sidecar

- **GIVEN** a running cluster with the db nodes db0 and db1
- **WHEN** the user runs `cassandra stop --hosts db0`
- **THEN** the database stops on db0 only
- **AND** the sidecar DaemonSet stays in place, the database stays recorded as a running workload, and the kit post-stop hooks do not run
- **AND** the output names the db nodes that still run the database and keep the sidecar.

#### Scenario: The last db node to stop removes the sidecar

- **GIVEN** a cluster where db0 is stopped and db1 still runs the database
- **WHEN** the user runs `cassandra stop --hosts db1`
- **THEN** no db node still runs the database
- **AND** the sidecar DaemonSet is removed, the database is no longer recorded as a running workload, and the kit post-stop hooks run.
