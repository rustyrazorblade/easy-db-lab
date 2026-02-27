# Observability

Provides metrics, logs, traces, and profiling for all cluster workloads.

## Requirements

### REQ-OB-001: Metrics Collection and Storage

The system MUST collect metrics from all cluster nodes and store them in a Prometheus-compatible backend with configurable retention.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** databases and services produce metrics, **THEN** metrics are collected via OpenTelemetry and stored in VictoriaMetrics.
- **GIVEN** stored metrics, **WHEN** retention period expires, **THEN** old metrics are automatically purged (default 7-day retention).
- **GIVEN** YACE is deployed and AWS resources are active, **WHEN** CloudWatch metrics are scraped, **THEN** metrics from EMR, S3, EBS, EC2, and OpenSearch namespaces are collected by OTel Collector and stored in VictoriaMetrics with `aws_` prefix.
- **GIVEN** stored CloudWatch-sourced metrics, **WHEN** the user backs up VictoriaMetrics and tears down infrastructure, **THEN** all CloudWatch-sourced metrics are included in the backup and queryable after restore.
- **GIVEN** YACE is running on the control node, **WHEN** OTel Collector is active, **THEN** OTel Collector's Prometheus receiver scrapes YACE's metrics endpoint via a `yace` scrape job.

### REQ-OB-002: Log Collection and Storage

The system MUST collect logs from all cluster nodes using the OpenTelemetry Collector DaemonSet and store them in VictoriaLogs.

The OTel Collector MUST collect the following log sources:
- ClickHouse server logs from `/mnt/db1/clickhouse/logs/*.log`
- ClickHouse Keeper logs from `/mnt/db1/clickhouse/keeper/logs/*.log`
- System logs from `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog`
- Cassandra logs from `/mnt/db1/cassandra/logs/*.log`
- Systemd journal entries for `cassandra.service`, `docker.service`, `k3s.service`, `sshd.service`

Each log source MUST have a `source` attribute identifying its origin (`system`, `cassandra`, `systemd`). ClickHouse logs retain their existing `database: clickhouse` attribute.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** a cluster node writes to `/var/log/syslog`, **THEN** OTel Collector ingests the log entry and forwards it to VictoriaLogs with `source: system`.
- **GIVEN** a running cluster, **WHEN** Cassandra writes to `/mnt/db1/cassandra/logs/system.log`, **THEN** OTel Collector ingests the log entry and forwards it to VictoriaLogs with `source: cassandra`.
- **GIVEN** a running cluster, **WHEN** the `cassandra.service` systemd unit emits a journal entry, **THEN** OTel Collector ingests the entry and forwards it to VictoriaLogs with `source: systemd`.
- **GIVEN** stored logs, **WHEN** the user queries logs, **THEN** matching log entries are returned.
- **GIVEN** a Spark job running with the OTel Java agent attached, **WHEN** Spark driver and executor produce logs, **THEN** logs are sent via OTLP to the control node's OTel Collector and stored in VictoriaLogs.
- **GIVEN** Spark logs stored in VictoriaLogs, **WHEN** the user queries for Spark logs, **THEN** logs are filterable by `service.name` matching `spark-<job-name>`.
- **GIVEN** `EMRSparkService.queryVictoriaLogs()` is called, **WHEN** querying Spark logs, **THEN** the query uses OTel Java agent log attributes (`service.name`, time range) instead of the defunct `step_id` tag.

### REQ-OB-003: Grafana Dashboards

The system MUST provide pre-configured Grafana dashboards for all supported databases and infrastructure.

**Scenarios:**

- **GIVEN** a running cluster with observability deployed, **WHEN** the user accesses Grafana, **THEN** pre-configured dashboards show metrics for running databases and infrastructure.
- **GIVEN** new dashboard definitions, **WHEN** the user updates Grafana configuration, **THEN** dashboards are deployed to the cluster.

### REQ-OB-004: Distributed Tracing

The system MUST collect and store distributed traces.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** instrumented services produce traces, **THEN** traces are collected and stored in Tempo.

### REQ-OB-005: Continuous Profiling

The system MUST support CPU profiling for cluster workloads.

**Scenarios:**

- **GIVEN** a running Cassandra cluster, **WHEN** the user generates a flame graph for a node, **THEN** a CPU profile is captured via async-profiler and returned.
- **GIVEN** a running cluster, **WHEN** eBPF profiling is active, **THEN** CPU profiles are continuously collected and stored in Pyroscope.

### REQ-OB-006: eBPF Network and System Metrics

The system MUST collect low-level system metrics via eBPF.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** Beyla is active, **THEN** L7 network RED metrics (rate, errors, duration) are collected for database protocols.
- **GIVEN** a running cluster, **WHEN** ebpf_exporter is active, **THEN** TCP retransmit, block I/O latency, and VFS latency metrics are collected.

### REQ-OB-007: Observability Data Backup and Restore

The system MUST support backup and restore of metrics and logs.

**Scenarios:**

- **GIVEN** stored metrics or logs, **WHEN** the user triggers a backup, **THEN** data is exported and stored for later import.
- **GIVEN** a backed-up snapshot, **WHEN** the user imports it, **THEN** historical data is restored and queryable.

### REQ-OB-008: AxonOps Integration

The system MUST support configuring the AxonOps monitoring agent on Cassandra nodes.

**Scenarios:**

- **GIVEN** AxonOps credentials configured in the user profile, **WHEN** the user enables AxonOps, **THEN** the AxonOps agent is configured on all Cassandra nodes with the specified organization.
- **GIVEN** AxonOps is configured, **WHEN** Cassandra starts, **THEN** the AxonOps agent starts alongside it.

## Success Criteria

- Grafana dashboards display meaningful metrics within 2 minutes of cluster start.
- All observability data (metrics, logs, traces) is available for at least 7 days by default.
- Flame graph generation completes in under 60 seconds for standard profiling duration.
