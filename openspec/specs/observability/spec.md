# Observability

Provides metrics, logs, traces, and profiling for all cluster workloads.

## Requirements

### REQ-OB-001: Metrics Collection and Storage

The system MUST collect metrics from all cluster nodes and store them in a Prometheus-compatible backend with configurable retention.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** databases and services produce metrics, **THEN** metrics are collected via OpenTelemetry and stored in VictoriaMetrics.
- **GIVEN** stored metrics, **WHEN** retention period expires, **THEN** old metrics are automatically purged (default 7-day retention).

### REQ-OB-002: Log Collection and Storage

The system MUST collect logs from all cluster nodes and make them queryable.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** services produce logs, **THEN** logs are collected via Vector and stored in VictoriaLogs.
- **GIVEN** stored logs, **WHEN** the user queries logs, **THEN** matching log entries are returned.

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
