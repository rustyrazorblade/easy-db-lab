# Spark EMR

Spark job submission, monitoring, and cancellation on AWS EMR clusters with full observability integration.

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
