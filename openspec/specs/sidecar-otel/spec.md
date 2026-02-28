### Requirement: OTel Java agent installed on Cassandra nodes

The OTel Java agent JAR SHALL be downloaded and installed to `/usr/local/otel/opentelemetry-javaagent.jar` during the Packer AMI build.

#### Scenario: OTel agent JAR available after AMI build

- **WHEN** a Cassandra node is provisioned from the AMI
- **THEN** `/usr/local/otel/opentelemetry-javaagent.jar` SHALL exist and be readable by the cassandra user

### Requirement: Cassandra Sidecar instrumented with OTel Java agent

The Cassandra Sidecar systemd service SHALL be configured to load the OTel Java agent, exporting traces and JVM metrics to the local OTel Collector DaemonSet via OTLP gRPC on `localhost:4317`.

#### Scenario: Sidecar exports OTel telemetry when environment is configured

- **WHEN** the sidecar starts and `/etc/default/cassandra-sidecar` contains OTel configuration
- **THEN** the OTel Java agent SHALL be loaded with service name `cassandra-sidecar`
- **AND** traces and JVM metrics SHALL be exported to `localhost:4317` via OTLP gRPC
- **AND** logs SHALL be exported via OTLP

#### Scenario: Sidecar starts without instrumentation when environment file is absent

- **WHEN** the sidecar starts and `/etc/default/cassandra-sidecar` does not exist
- **THEN** the sidecar SHALL start normally without any Java agent instrumentation

### Requirement: Cassandra Sidecar instrumented with Pyroscope Java agent

The Cassandra Sidecar systemd service SHALL be configured to load the Pyroscope Java agent, sending CPU, allocation, and lock profiles to the Pyroscope server on the control node.

#### Scenario: Sidecar sends profiles to Pyroscope when configured

- **WHEN** the sidecar starts and `PYROSCOPE_SERVER_ADDRESS` is set in the environment file
- **THEN** the Pyroscope Java agent SHALL be loaded with application name `cassandra-sidecar`
- **AND** CPU, allocation (512k threshold), and lock (10ms threshold) profiles SHALL be sent to the Pyroscope server
- **AND** profiles SHALL include `hostname` and `cluster` labels

### Requirement: SetupInstance configures sidecar environment

The `SetupInstance` command SHALL write `/etc/default/cassandra-sidecar` on Cassandra nodes with OTel and Pyroscope agent configuration.

#### Scenario: Sidecar environment file created during setup

- **WHEN** the `SetupInstance` command runs on a Cassandra node
- **THEN** `/etc/default/cassandra-sidecar` SHALL be created with `JAVA_OPTS` containing OTel and Pyroscope `-javaagent` flags
- **AND** `OTEL_SERVICE_NAME` SHALL be set to `cassandra-sidecar`
- **AND** `OTEL_EXPORTER_OTLP_ENDPOINT` SHALL be set to `http://localhost:4317`
