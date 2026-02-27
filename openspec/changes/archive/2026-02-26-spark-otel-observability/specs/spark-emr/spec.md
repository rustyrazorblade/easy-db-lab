## MODIFIED Requirements

### Requirement: Spark Job Submission

The system MUST support submitting and monitoring Spark jobs on EMR.

**Scenarios:**

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
