## MODIFIED Requirements

### Requirement: REQ-OB-001 Metrics collection (expanded)

The system SHALL collect and store metrics from all infrastructure components, now including CloudWatch-sourced metrics via YACE for EMR, S3, EBS, EC2, and OpenSearch namespaces.

#### Scenario: CloudWatch metrics stored in VictoriaMetrics

- **WHEN** YACE is deployed and AWS resources are active
- **THEN** CloudWatch metrics from EMR, S3, EBS, EC2, and OpenSearch namespaces are scraped by YACE, collected by OTel Collector, and stored in VictoriaMetrics with `aws_` prefix

#### Scenario: CloudWatch metrics survive teardown

- **WHEN** the user backs up VictoriaMetrics and tears down infrastructure
- **THEN** all CloudWatch-sourced metrics are included in the backup and queryable after restore

#### Scenario: OTel Collector scrapes YACE

- **WHEN** YACE is running on the control node
- **THEN** OTel Collector's Prometheus receiver scrapes YACE's metrics endpoint via a `yace` scrape job

### Requirement: REQ-OB-002 Log collection (expanded)

The system SHALL collect logs from all infrastructure components, now including Spark driver and executor logs via OTel Java agent auto-instrumentation of Log4j2.

#### Scenario: Spark logs stored in VictoriaLogs

- **WHEN** a Spark job is running with the OTel Java agent attached
- **THEN** Spark driver and executor logs are sent via OTLP to the control node's OTel Collector and stored in VictoriaLogs

#### Scenario: Spark logs queryable by service name

- **WHEN** the user queries VictoriaLogs for Spark logs
- **THEN** logs are filterable by `service.name` matching `spark-<job-name>`

#### Scenario: Victoria Logs query updated for OTel attributes

- **WHEN** `EMRSparkService.queryVictoriaLogs()` is called
- **THEN** the query uses OTel Java agent log attributes (`service.name`, time range) instead of the defunct `step_id` tag
