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

#### Scenario: Tool runner logs shipped to VictoriaLogs

- **WHEN** the OTel Collector DaemonSet is running on a node
- **THEN** a `filelog/tools` receiver SHALL watch `/var/log/easydblab/tools/*.log`
- **AND** log entries SHALL include the attribute `source: tool-runner`
- **AND** logs SHALL be shipped to VictoriaLogs via the `logs/local` pipeline
