# Grafana Dashboards

Standalone Grafana dashboards for monitoring Cassandra, ClickHouse, and AWS infrastructure.

## Dashboards

| Dashboard | Description |
|-----------|-------------|
| system-overview.json | CPU, memory, disk I/O, network, load average for all nodes |
| cassandra-overview.json | Comprehensive Cassandra cluster health and performance |
| cassandra-condensed.json | Single-pane summary of key Cassandra metrics |
| clickhouse.json | ClickHouse and Keeper metrics |
| clickhouse-logs.json | ClickHouse log viewer |
| stress.json | Stress test throughput and latency |
| s3-cloudwatch.json | AWS S3, EBS, and EC2 metrics (via OTel CloudWatch receiver) |
| emr.json | AWS EMR cluster metrics |
| opensearch.json | AWS OpenSearch domain metrics |
| profiling.json | Continuous profiling via Pyroscope |

## Required Datasources

- **Prometheus-compatible** (e.g., VictoriaMetrics) for metrics dashboards
- **VictoriaLogs** for the ClickHouse logs dashboard
- **Pyroscope** for the profiling dashboard

## Import Instructions

1. Download `dashboards.zip` from the [latest release](../../releases/latest)
2. Extract the JSON files
3. In Grafana, go to **Dashboards > Import** and upload each JSON file
4. Select the appropriate datasource when prompted

## Multi-Cluster Support

All dashboards include a **Cluster** dropdown variable that supports multi-select. The cluster list is auto-populated from your metrics data using `label_values(up, cluster)`.
