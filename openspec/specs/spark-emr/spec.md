# Spark EMR

Spark job submission, monitoring, and cancellation on AWS EMR clusters with full observability integration, including OTel and Pyroscope agent installation via bootstrap actions.

## Requirements

### Requirement: Spark Job Submission

The system MUST support submitting and monitoring Spark jobs on EMR with full observability instrumentation.

#### Scenario: Spark submit references reorganized module JARs

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** the system MUST resolve JAR paths from the `spark/` subdirectory modules (e.g., `spark/bulk-writer-sidecar/build/libs/`) instead of the previous top-level module paths (e.g., `bulk-writer/build/libs/`)

#### Scenario: Spark submit includes OTel and Pyroscope Java agents

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` include both `-javaagent:/opt/otel/opentelemetry-javaagent.jar` and `-javaagent:/opt/pyroscope/pyroscope.jar` with appropriate system properties

#### Scenario: OTel environment variables exclude endpoint override

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** the following environment variables are set on driver and executor JVMs:
  - `OTEL_LOGS_EXPORTER=otlp`
  - `OTEL_METRICS_EXPORTER=otlp`
  - `OTEL_TRACES_EXPORTER=otlp`
  - `OTEL_SERVICE_NAME=spark-<job-name>`
- **AND** `OTEL_EXPORTER_OTLP_ENDPOINT` is NOT set (Java agent defaults to localhost:4317)

#### Scenario: Spark logs available via OTel and S3

- **WHEN** a Spark job completes or fails
- **THEN** logs are available both via OTel (VictoriaLogs, queryable by `service.name`) and via S3 log download (existing fallback mechanism)

#### Scenario: Bootstrap actions include control node IP

- **WHEN** `EMRService.createCluster()` is called
- **THEN** bootstrap actions from `EMRClusterConfig.bootstrapActions` are attached to the `RunJobFlowRequest` with the control node IP as a script argument

### Requirement: S3 JAR validation before job submission

When a user provides an S3 path for the `--jar` option, the system SHALL verify the JAR exists in S3 before submitting the EMR step. If the JAR does not exist, the system SHALL fail immediately with a clear error message.

#### Scenario: S3 jar exists

- **WHEN** the user runs `spark submit --jar s3://bucket/path/app.jar --main-class com.example.Main`
- **AND** the jar file exists at `s3://bucket/path/app.jar`
- **THEN** the system SHALL proceed with job submission normally

#### Scenario: S3 jar does not exist

- **WHEN** the user runs `spark submit --jar s3://bucket/path/nonexistent.jar --main-class com.example.Main`
- **AND** no object exists at `s3://bucket/path/nonexistent.jar`
- **THEN** the system SHALL fail before submitting the EMR step
- **AND** the error message SHALL include the S3 path that was not found

#### Scenario: Local jar path is not affected

- **WHEN** the user runs `spark submit --jar /local/path/app.jar --main-class com.example.Main`
- **THEN** the existing local file validation SHALL apply (file exists, .jar extension)
- **AND** no S3 existence check SHALL be performed before upload

### Requirement: Spark Job Cancellation

The system SHALL provide a `spark stop` CLI command that cancels a running or pending EMR step without terminating the cluster.

#### Scenario: Cancel a specific job by step ID

- **WHEN** the user runs `spark stop --step-id s-XXXXX`
- **THEN** the system MUST call the EMR `CancelSteps` API with `TERMINATE_PROCESS` strategy for that step
- **AND** emit an event with the step ID and the cancellation status returned by EMR

#### Scenario: Cancel the most recent job

- **WHEN** the user runs `spark stop` without a `--step-id`
- **THEN** the system MUST resolve the most recent step on the cluster (same as `spark status` default)
- **AND** cancel that step using the EMR `CancelSteps` API

#### Scenario: Cluster validation before cancellation

- **WHEN** the user runs `spark stop`
- **THEN** the system MUST validate the EMR cluster exists and is accessible before attempting cancellation
- **AND** fail with an error if the cluster is not found or in an invalid state

#### Scenario: Report cancellation result

- **WHEN** the EMR `CancelSteps` API returns a response
- **THEN** the system MUST display the step ID and the cancel status (e.g., `SUBMITTED`, `FAILED`)
- **AND** display the reason if the cancellation failed (e.g., step already completed)

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
