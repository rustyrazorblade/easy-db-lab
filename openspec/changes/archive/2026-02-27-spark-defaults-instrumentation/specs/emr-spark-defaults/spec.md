## ADDED Requirements

### Requirement: EMR cluster created with spark-defaults classification

The system SHALL pass a `spark-defaults` classification to the EMR `RunJobFlowRequest` at cluster creation time, configuring all Spark JVMs with OTel and Pyroscope Java agent flags and OTEL exporter environment variables.

#### Scenario: spark-defaults includes OTel and Pyroscope Java agents

- **WHEN** an EMR cluster is created via `EMRProvisioningService`
- **THEN** the `spark-defaults` classification includes `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` with:
  - `-javaagent:/opt/otel/opentelemetry-javaagent.jar`
  - `-javaagent:/opt/pyroscope/pyroscope.jar`
  - `-Dpyroscope.application.name=spark`
  - `-Dpyroscope.server.address=http://<control-node-ip>:4040`
  - `-Dpyroscope.format=jfr`
  - `-Dpyroscope.profiler.event=cpu`
  - `-Dpyroscope.profiler.alloc=512k`
  - `-Dpyroscope.profiler.lock=10ms`

#### Scenario: spark-defaults includes OTEL environment variables

- **WHEN** an EMR cluster is created via `EMRProvisioningService`
- **THEN** the `spark-defaults` classification includes:
  - `spark.driverEnv.OTEL_LOGS_EXPORTER=otlp`
  - `spark.driverEnv.OTEL_METRICS_EXPORTER=otlp`
  - `spark.driverEnv.OTEL_TRACES_EXPORTER=otlp`
  - `spark.driverEnv.OTEL_SERVICE_NAME=spark`
  - `spark.executorEnv.OTEL_LOGS_EXPORTER=otlp`
  - `spark.executorEnv.OTEL_METRICS_EXPORTER=otlp`
  - `spark.executorEnv.OTEL_TRACES_EXPORTER=otlp`
  - `spark.executorEnv.OTEL_SERVICE_NAME=spark`
  - `spark.yarn.appMasterEnv.OTEL_LOGS_EXPORTER=otlp`
  - `spark.yarn.appMasterEnv.OTEL_METRICS_EXPORTER=otlp`
  - `spark.yarn.appMasterEnv.OTEL_TRACES_EXPORTER=otlp`
  - `spark.yarn.appMasterEnv.OTEL_SERVICE_NAME=spark`

#### Scenario: EMRClusterConfig supports configurations

- **WHEN** `EMRService.createCluster()` is called with an `EMRClusterConfig` containing `configurations`
- **THEN** the configurations are converted to AWS SDK `Configuration` objects and attached to the `RunJobFlowRequest`

#### Scenario: Control node IP resolved for Pyroscope server address

- **WHEN** the spark-defaults classification is built in `EMRProvisioningService`
- **THEN** the control node's private IP is read from `clusterState.hosts[ServerType.Control]` and used in `-Dpyroscope.server.address`
