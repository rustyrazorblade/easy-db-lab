# Spark / EMR

Manages EMR cluster provisioning and Spark job submission for analytics workloads.

## Requirements

### REQ-SE-001: EMR Cluster Provisioning

The system MUST support provisioning EMR clusters for Spark job execution.

**Scenarios:**

- **GIVEN** a running cluster with S3 configured, **WHEN** the user initializes Spark, **THEN** an EMR cluster is provisioned with configurable master and worker instance types and counts.

### REQ-SE-002: Spark Job Submission

The system MUST support submitting and monitoring Spark jobs on EMR.

**Scenarios:**

- **GIVEN** a running EMR cluster, **WHEN** the user submits a Spark job, **THEN** the job executes on the EMR cluster.
- **GIVEN** submitted Spark jobs, **WHEN** the user checks status, **THEN** job states are displayed.
- **GIVEN** a completed or running Spark job, **WHEN** the user requests logs, **THEN** job logs are displayed.
- **GIVEN** `EMRSparkService` submits a Spark job, **WHEN** the job is configured, **THEN** the spark-submit arguments include OTel Java agent flags (`-javaagent`) and environment variables (`OTEL_*`) targeting the control node's OTel Collector.
- **GIVEN** `EMRService.createCluster()` is called, **WHEN** the cluster is created, **THEN** bootstrap actions from `EMRClusterConfig.bootstrapActions` are attached to the `RunJobFlowRequest`.
- **GIVEN** a Spark job fails, **WHEN** logs are needed, **THEN** the existing S3 log download mechanism in `EMRSparkService` continues to work as a fallback alongside OTel-based log collection.
