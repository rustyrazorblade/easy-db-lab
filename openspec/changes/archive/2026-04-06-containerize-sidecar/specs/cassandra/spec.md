## MODIFIED Requirements

### REQ-CA-003: Cluster Lifecycle

The system MUST support starting, stopping, and restarting Cassandra across cluster nodes. When starting, the system SHALL also deploy the Cassandra sidecar as a K3s DaemonSet after all Cassandra nodes are up.

#### Scenario: Nodes start sequentially

- **WHEN** the user starts the cluster
- **THEN** nodes start sequentially with a configurable delay between them

#### Scenario: Cluster stops gracefully

- **GIVEN** a running Cassandra cluster
- **WHEN** the user stops it
- **THEN** all nodes are stopped gracefully

#### Scenario: Sidecar deployed after Cassandra starts

- **WHEN** the user runs `cassandra start`
- **THEN** after all Cassandra nodes are up, the sidecar DaemonSet is applied to K3s
