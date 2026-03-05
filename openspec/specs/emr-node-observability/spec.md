## ADDED Requirements

### Requirement: Observability components installed on EMR nodes via bootstrap action

The system SHALL upload a bootstrap action script to S3 and configure EMR cluster creation to run it, installing the OTel Java agent, OTel Collector, and Pyroscope Java agent on every EMR node.

#### Scenario: Bootstrap script uploaded before cluster creation

- **WHEN** the user creates an EMR cluster
- **THEN** a bootstrap script (`bootstrap-otel.sh`) is uploaded to S3 at `clusters/{name}-{id}/spark/bootstrap-otel.sh` before `createCluster()` is called

#### Scenario: Bootstrap action runs on all EMR nodes

- **WHEN** the EMR cluster starts
- **THEN** the bootstrap script installs:
  1. OTel Java agent v2.25.0 JAR to `/opt/otel/opentelemetry-javaagent.jar`
  2. OTel Collector contrib binary to `/opt/otel/otelcol-contrib`
  3. Pyroscope Java agent JAR to `/opt/pyroscope/pyroscope.jar`
  4. OTel Collector config to `/opt/otel/config.yaml` with control node IP
  5. Systemd unit for `otel-collector.service`, started and enabled

#### Scenario: Bootstrap action receives control node IP

- **WHEN** the bootstrap script is invoked by EMR
- **THEN** the control node's private IP is passed as a script argument and used in the OTel Collector config's OTLP exporter endpoint

#### Scenario: EMRClusterConfig supports bootstrap actions

- **WHEN** `EMRService.createCluster()` is called with an `EMRClusterConfig` containing `bootstrapActions`
- **THEN** the bootstrap actions are attached to the `RunJobFlowRequest` and executed during cluster bootstrap

### Requirement: OTel Collector runs as systemd service on EMR nodes

The system SHALL run the OTel Collector as a persistent systemd service on every EMR node, accepting OTLP from local Spark JVMs, collecting host metrics, and forwarding all telemetry to the control node.

#### Scenario: Collector starts as systemd service

- **WHEN** the OTel Collector binary is installed
- **THEN** a systemd unit (`otel-collector.service`) is created and enabled, and the service starts automatically

#### Scenario: Collector survives process restarts

- **WHEN** the OTel Collector process crashes
- **THEN** systemd restarts it automatically (Restart=always)

#### Scenario: Spark Java agent sends to local collector

- **WHEN** a Spark driver or executor JVM starts with the OTel Java agent (no OTEL_EXPORTER_OTLP_ENDPOINT override)
- **THEN** the Java agent sends metrics, logs, and traces to localhost:4317 where the local collector receives them

#### Scenario: Host metrics collected

- **WHEN** the OTel Collector is running on an EMR node
- **THEN** it collects CPU, memory, disk, filesystem, network, load, and process metrics at regular intervals

#### Scenario: Telemetry forwarded to control node

- **WHEN** the local collector receives OTLP data or collects host metrics
- **THEN** it forwards all data to the control node's OTel Collector at `<control_ip>:4317`

### Requirement: Pyroscope agent attached to Spark driver and executor JVMs

The system SHALL inject Pyroscope Java agent flags into Spark submit arguments so that driver and executor JVMs send continuous profiles to the Pyroscope server on the control node.

#### Scenario: Spark submit includes Pyroscope agent flags

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` include `-javaagent:/opt/pyroscope/pyroscope.jar` with Pyroscope system properties

#### Scenario: Pyroscope agent configured with correct server address

- **WHEN** a Spark job is submitted
- **THEN** the Pyroscope agent is configured with `pyroscope.server.address=http://<control_ip>:4040`

#### Scenario: Pyroscope agent collects CPU, allocation, and lock profiles

- **WHEN** a Spark job is running with the Pyroscope agent
- **THEN** the agent collects CPU profiles, allocation profiles (512k threshold), and lock contention profiles (10ms threshold) in JFR format

#### Scenario: Profiles labeled with job identity

- **WHEN** a Spark job is running with the Pyroscope agent
- **THEN** profiles are labeled with `application.name=spark-<jobName>`, `hostname`, and `cluster` for identification in Pyroscope UI
