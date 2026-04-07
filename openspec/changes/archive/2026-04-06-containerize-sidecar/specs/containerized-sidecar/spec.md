## ADDED Requirements

### Requirement: Sidecar runs as a K3s DaemonSet on database nodes

The system SHALL deploy the Cassandra sidecar as a K3s DaemonSet targeting all nodes with label `type=db`, using `hostNetwork: true` so the sidecar can connect to the local Cassandra instance.

#### Scenario: Sidecar deployed on cassandra start

- **WHEN** the user runs `cassandra start`
- **THEN** a DaemonSet named `cassandra-sidecar` is applied to K3s and one sidecar pod runs on each database node

#### Scenario: Sidecar pod runs as cassandra user

- **WHEN** the sidecar DaemonSet is scheduled
- **THEN** each pod runs with `runAsUser: 999` and `runAsGroup: 999` (the cassandra user)

### Requirement: Sidecar image is configurable at start time

The system SHALL accept a `--sidecar-image` option on `cassandra start` specifying the container image (registry, repository, and tag) to use for the sidecar DaemonSet. The default SHALL be `ghcr.io/apache/cassandra-sidecar:latest`.

#### Scenario: Default image used when no flag provided

- **WHEN** the user runs `cassandra start` without `--sidecar-image`
- **THEN** the DaemonSet uses `ghcr.io/apache/cassandra-sidecar:latest`

#### Scenario: Custom image used when flag provided

- **WHEN** the user runs `cassandra start --sidecar-image ghcr.io/myfork/cassandra-sidecar:my-branch`
- **THEN** the DaemonSet uses `ghcr.io/myfork/cassandra-sidecar:my-branch`

### Requirement: Per-node config templated at pod startup

The system SHALL use an init container to write the sidecar configuration file into a shared volume before the main container starts. The init container SHALL substitute the node's private IP address (from the Kubernetes Downward API `status.hostIP`) into the config template.

#### Scenario: Config contains correct node IP

- **WHEN** a sidecar pod starts on a node with private IP `10.0.1.5`
- **THEN** the sidecar config at `/etc/cassandra-sidecar/cassandra-sidecar.yaml` contains `10.0.1.5` as the Cassandra host address

#### Scenario: Each pod gets its own node's IP

- **WHEN** the DaemonSet runs on multiple nodes with different private IPs
- **THEN** each pod's config contains only its own node's IP address

### Requirement: Sidecar has access to Cassandra data directories

The system SHALL mount the host path `/mnt/db1/cassandra` into the sidecar container at `/mnt/db1/cassandra`, providing read/write access to Cassandra data, commit log, hints, saved caches, import staging, and log directories.

#### Scenario: Sidecar can read and write data directories

- **WHEN** the sidecar pod is running
- **THEN** the sidecar has read/write access to all subdirectories under `/mnt/db1/cassandra`

### Requirement: Pyroscope Java agent injected via JAVA_TOOL_OPTIONS

The system SHALL mount the host path `/usr/local/pyroscope` into the sidecar container as read-only and set `JAVA_TOOL_OPTIONS` to load the Pyroscope Java agent with the cluster name and node hostname as profiling labels.

#### Scenario: Pyroscope agent active in sidecar JVM

- **WHEN** the sidecar pod starts
- **THEN** the JVM loads the Pyroscope agent and reports profiles to the Pyroscope server on the control node with `application.name=cassandra-sidecar`

### Requirement: OTel instrumentation via environment variables

The system SHALL set `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` and `OTEL_SERVICE_NAME=cassandra-sidecar` on the sidecar container so traces and metrics are exported to the node-local OTel collector.

#### Scenario: Sidecar traces exported to OTel collector

- **WHEN** the sidecar pod is running with hostNetwork
- **THEN** OTel data is exported to `localhost:4317` which is served by the OTel collector DaemonSet on the same node
