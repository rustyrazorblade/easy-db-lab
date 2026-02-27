## ADDED Requirements

### Requirement: CloudWatch metrics scraped into VictoriaMetrics

The system SHALL run YACE (Yet Another CloudWatch Exporter) as a K8s Deployment on the control node, scraping CloudWatch metrics and exposing them as Prometheus metrics for the OTel Collector to forward to VictoriaMetrics.

#### Scenario: YACE deployment applied to cluster

- **WHEN** the user runs `grafana update-config`
- **THEN** a YACE ConfigMap and Deployment are applied to the K8s cluster on the control node

#### Scenario: EMR metrics available in VictoriaMetrics

- **WHEN** an EMR cluster is running and YACE is deployed
- **THEN** EMR CloudWatch metrics (CoreNodesRunning, HDFSUtilization, AppsRunning, MemoryAllocatedMB, S3BytesRead, S3BytesWritten, and others) are queryable in VictoriaMetrics with `aws_elasticmapreduce_` prefix

#### Scenario: S3 and EBS metrics available in VictoriaMetrics

- **WHEN** S3 buckets and EBS volumes are in use and YACE is deployed
- **THEN** S3 metrics (BytesDownloaded, BytesUploaded, GetRequests, PutRequests, latencies) and EBS metrics (VolumeReadOps, VolumeWriteOps, VolumeQueueLength, BurstBalance) are queryable in VictoriaMetrics

#### Scenario: OpenSearch metrics available in VictoriaMetrics

- **WHEN** an OpenSearch domain is running and YACE is deployed
- **THEN** OpenSearch CloudWatch metrics (ClusterStatus, CPUUtilization, JVMMemoryPressure, search/index latency and rate) are queryable in VictoriaMetrics

#### Scenario: Metrics survive cluster teardown

- **WHEN** the user backs up VictoriaMetrics and tears down the cluster
- **THEN** CloudWatch-sourced metrics are included in the backup and queryable after restore

### Requirement: Grafana dashboards use VictoriaMetrics for CloudWatch data

The system SHALL provide Grafana dashboards for EMR, S3/EBS, and OpenSearch that query VictoriaMetrics (Prometheus datasource) using YACE metric names instead of the CloudWatch datasource.

#### Scenario: EMR dashboard works after teardown

- **WHEN** the user views the EMR Grafana dashboard after restoring a backup
- **THEN** all EMR metrics are displayed from VictoriaMetrics data

#### Scenario: S3/EBS dashboard works after teardown

- **WHEN** the user views the S3/EBS Grafana dashboard after restoring a backup
- **THEN** all S3 and EBS metrics are displayed from VictoriaMetrics data
