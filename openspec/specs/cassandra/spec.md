# Cassandra

## Purpose

Manages Apache Cassandra deployment, version selection, configuration, and cluster operations.

## Requirements

### REQ-CA-001: Multi-Version Support

The system MUST support multiple Cassandra versions (3.0 through trunk) on the same AMI.

#### Scenario: Select a Cassandra version

- **GIVEN** a running cluster
- **WHEN** the user selects a Cassandra version
- **THEN** that version is activated with appropriate Java and Python runtime versions.

#### Scenario: Start cluster on selected version

- **GIVEN** a selected version
- **WHEN** the user starts the cluster
- **THEN** all nodes run the selected Cassandra version and join the ring.

### REQ-CA-002: Configuration Management

The system MUST allow configuration of Cassandra via YAML patch files.

#### Scenario: Push a YAML patch

- **GIVEN** a local YAML patch file with configuration overrides
- **WHEN** the user pushes configuration
- **THEN** the patch is applied to cassandra.yaml on all targeted nodes.

#### Scenario: Restart on config push

- **GIVEN** updated configuration
- **WHEN** the user requests a restart alongside the config push
- **THEN** nodes are restarted with the new configuration.

### REQ-CA-003: Cluster Lifecycle

The system MUST support starting, stopping, and restarting Cassandra across cluster nodes. When starting, the system SHALL also deploy the Cassandra sidecar as a K3s DaemonSet after all Cassandra nodes are up.

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

### REQ-CA-004: Mixed-Version Clusters

The system MUST support running different Cassandra versions on different nodes for upgrade testing.

#### Scenario: Per-host version selection

- **GIVEN** a running cluster
- **WHEN** the user selects different versions for different hosts
- **THEN** each host runs its assigned version independently.

### REQ-CA-005: CQL Access

The system MUST provide CQL query execution against the cluster via the Java driver, routed through the network access layer (SOCKS proxy or Tailscale).

#### Scenario: Execute a CQL query

- **GIVEN** a running Cassandra cluster
- **WHEN** the user executes a CQL query
- **THEN** the query is routed through the available network path and results are displayed.

#### Scenario: CQL session reuse

- **GIVEN** the REPL or server is running
- **WHEN** multiple CQL queries are executed
- **THEN** the CQL session is reused across queries.

### REQ-CA-006: Nodetool Access

The system MUST provide nodetool execution on cluster nodes.

#### Scenario: Invoke nodetool

- **GIVEN** a running cluster
- **WHEN** the user invokes nodetool
- **THEN** the specified nodetool command is executed on the targeted node.

## Success Criteria

- Users can switch Cassandra versions in under 1 minute without reprovisioning infrastructure.
