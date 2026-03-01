## MODIFIED Requirements

### Requirement: Spark Job Submission

The system MUST support submitting and monitoring Spark jobs on EMR.

#### Scenario: Spark submit includes OTel and Pyroscope Java agents

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` include `-Dpyroscope.application.name=spark-<job-name>` to override the cluster-wide default
- **AND** the `-javaagent` flags are NOT set per-job (they are loaded from spark-defaults at cluster level)

#### Scenario: OTel environment variables exclude endpoint override

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** the following environment variable is set on driver, executor, and YARN app master JVMs:
  - `OTEL_SERVICE_NAME=spark-<job-name>`
- **AND** `OTEL_LOGS_EXPORTER`, `OTEL_METRICS_EXPORTER`, `OTEL_TRACES_EXPORTER` are NOT set per-job (they come from spark-defaults)
- **AND** `OTEL_EXPORTER_OTLP_ENDPOINT` is NOT set (Java agent defaults to localhost:4317)

#### Scenario: Spark logs available via OTel and S3

- **WHEN** a Spark job completes or fails
- **THEN** logs are available both via OTel (VictoriaLogs, queryable by `service.name`) and via S3 log download (existing fallback mechanism)

#### Scenario: Bootstrap actions include control node IP

- **WHEN** `EMRService.createCluster()` is called
- **THEN** bootstrap actions from `EMRClusterConfig.bootstrapActions` are attached to the `RunJobFlowRequest` with the control node IP as a script argument
