## MODIFIED Requirements

### Requirement: CloudWatch metrics scraped into Mimir

The system SHALL run YACE (Yet Another CloudWatch Exporter) as a K8s Deployment on the control node, scraping CloudWatch metrics and exposing them as Prometheus metrics for the OTel Collector to forward to Mimir.

#### Scenario: YACE deployment applied to cluster

- **WHEN** the user runs `grafana update-config`
- **THEN** a YACE ConfigMap and Deployment are applied to the K8s cluster on the control node

#### Scenario: EMR metrics available in Mimir

- **WHEN** an EMR cluster is running and YACE is deployed
- **THEN** EMR CloudWatch metrics (CoreNodesRunning, HDFSUtilization, AppsRunning, MemoryAllocatedMB, S3BytesRead, S3BytesWritten, and others) are queryable in Mimir with `aws_elasticmapreduce_` prefix

#### Scenario: S3 and EBS metrics available in Mimir

- **WHEN** S3 buckets and EBS volumes are in use and YACE is deployed
- **THEN** S3 metrics (BytesDownloaded, BytesUploaded, GetRequests, PutRequests, latencies) and EBS metrics (VolumeReadOps, VolumeWriteOps, VolumeQueueLength, BurstBalance) are queryable in Mimir

#### Scenario: OpenSearch metrics available in Mimir

- **WHEN** an OpenSearch domain is running and YACE is deployed
- **THEN** OpenSearch CloudWatch metrics (ClusterStatus, CPUUtilization, JVMMemoryPressure, search/index latency and rate) are queryable in Mimir

#### Scenario: Metrics survive cluster teardown

- **WHEN** the user tears down the cluster with `down`
- **THEN** the CloudWatch-sourced metrics are in Mimir blocks in S3 under `mimir/<tenant>/`
