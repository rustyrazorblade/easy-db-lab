## MODIFIED Requirements

### Requirement: REQ-SE-002 Job submission (expanded)

The system SHALL submit Spark jobs to EMR clusters with OTel Java agent instrumentation, enabling automatic collection of logs, JVM metrics, and traces from driver and executor JVMs.

#### Scenario: Spark submit injects OTel configuration

- **WHEN** `EMRSparkService` submits a Spark job
- **THEN** the spark-submit arguments include OTel Java agent flags (`-javaagent`) and environment variables (`OTEL_*`) targeting the control node's OTel Collector

#### Scenario: EMR cluster creation supports bootstrap actions

- **WHEN** `EMRService.createCluster()` is called
- **THEN** bootstrap actions from `EMRClusterConfig.bootstrapActions` are attached to the `RunJobFlowRequest`

#### Scenario: S3 log download fallback preserved

- **WHEN** a Spark job fails
- **THEN** the existing S3 log download mechanism in `EMRSparkService` continues to work as a fallback alongside OTel-based log collection
