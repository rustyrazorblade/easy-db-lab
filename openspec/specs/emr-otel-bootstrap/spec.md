## ADDED Requirements

### Requirement: OTel Java agent installed on EMR nodes via bootstrap action

The system SHALL upload a bootstrap action script to S3 and configure EMR cluster creation to run it, installing the OTel Java agent JAR (`/opt/otel/opentelemetry-javaagent.jar`) on every EMR node.

#### Scenario: Bootstrap script uploaded before cluster creation

- **WHEN** the user creates an EMR cluster
- **THEN** a bootstrap script (`bootstrap-otel.sh`) is uploaded to S3 at `clusters/{name}-{id}/spark/bootstrap-otel.sh` before `createCluster()` is called

#### Scenario: Bootstrap action runs on all EMR nodes

- **WHEN** the EMR cluster starts
- **THEN** the bootstrap script downloads the OTel Java agent v2.25.0 JAR to `/opt/otel/opentelemetry-javaagent.jar` on every node (master and core)

#### Scenario: EMRClusterConfig supports bootstrap actions

- **WHEN** `EMRService.createCluster()` is called with an `EMRClusterConfig` containing `bootstrapActions`
- **THEN** the bootstrap actions are attached to the `RunJobFlowRequest` and executed during cluster bootstrap

### Requirement: OTel Java agent attached to Spark JVMs

The system SHALL inject OTel Java agent flags and environment variables into Spark submit arguments so that driver and executor JVMs send logs, metrics, and traces via OTLP directly to the control node's OTel Collector.

#### Scenario: Spark submit includes OTel Java agent

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` include `-javaagent:/opt/otel/opentelemetry-javaagent.jar`

#### Scenario: OTel environment variables set on Spark JVMs

- **WHEN** a Spark job is submitted via `EMRSparkService`
- **THEN** the following environment variables are set on driver and executor JVMs:
  - `OTEL_LOGS_EXPORTER=otlp`
  - `OTEL_METRICS_EXPORTER=otlp`
  - `OTEL_TRACES_EXPORTER=otlp`
  - `OTEL_EXPORTER_OTLP_ENDPOINT=http://<control_private_ip>:4317`
  - `OTEL_SERVICE_NAME=spark-<job-name>`

#### Scenario: Spark logs appear in VictoriaLogs

- **WHEN** a Spark job is running with the OTel Java agent
- **THEN** Spark driver and executor log output (via Log4j2 auto-instrumentation) is queryable in VictoriaLogs with `service.name` matching `spark-<job-name>`

#### Scenario: Spark JVM metrics appear in VictoriaMetrics

- **WHEN** a Spark job is running with the OTel Java agent
- **THEN** JVM metrics (heap usage, GC, threads) from both driver and executor are queryable in VictoriaMetrics
