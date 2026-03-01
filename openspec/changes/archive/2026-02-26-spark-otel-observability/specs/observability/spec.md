## MODIFIED Requirements

### Requirement: Metrics Collection and Storage

The system MUST collect metrics from all cluster nodes and store them in a Prometheus-compatible backend with configurable retention.

#### Scenario: YACE scrapes CloudWatch metrics excluding EMR

- **WHEN** YACE is deployed and AWS resources are active
- **THEN** metrics from S3, EBS, EC2, and OpenSearch namespaces are collected by OTel Collector and stored in VictoriaMetrics with `aws_` prefix
- **AND** the `AWS/ElasticMapReduce` namespace is NOT scraped by YACE

#### Scenario: YACE CloudWatch scrape job configuration

- **WHEN** OTel Collector is active on the control node
- **THEN** OTel Collector's Prometheus receiver scrapes YACE's metrics endpoint via a `yace` scrape job

### Requirement: Continuous Profiling

The system MUST support continuous profiling for cluster workloads including Spark.

#### Scenario: Pyroscope stores profiles in S3

- **WHEN** the Pyroscope server is deployed on the control node
- **THEN** it uses S3 as its storage backend with bucket and region from cluster configuration

#### Scenario: Spark JVM profiles collected

- **WHEN** a Spark job is running with the Pyroscope Java agent
- **THEN** CPU, allocation, and lock profiles from driver and executor JVMs are stored in Pyroscope and viewable in Grafana

### Requirement: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure.

#### Scenario: EMR dashboard shows OTel host metrics

- **WHEN** the user views the EMR dashboard in Grafana
- **THEN** the dashboard displays CPU, memory, disk, and network metrics from OTel host metrics collected on Spark/EMR nodes

#### Scenario: EMR dashboard shows Spark JVM metrics

- **WHEN** the user views the EMR dashboard in Grafana
- **THEN** the dashboard displays JVM heap usage, GC activity, and thread metrics from the OTel Java agent on Spark driver/executors

## ADDED Requirements

### Requirement: Status endpoint includes Tempo and Pyroscope S3 paths

The system SHALL expose Tempo and Pyroscope S3 storage paths in the `/status` endpoint's S3 section.

#### Scenario: Status response includes observability S3 paths

- **WHEN** the user queries the `/status` endpoint
- **THEN** the `s3.paths` section includes `tempo` and `pyroscope` fields with their S3 paths in the data bucket
