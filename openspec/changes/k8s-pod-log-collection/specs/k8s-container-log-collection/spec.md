## ADDED Requirements

### Requirement: OTel Collector collects K8s pod stdout/stderr with Kubernetes metadata
The OTel Collector SHALL collect stdout/stderr logs from all K8s pods running on each node by tailing `/var/log/containers/*.log`. Each log record SHALL be enriched with `k8s.pod.name`, `k8s.namespace.name`, `k8s.container.name`, and the `app.kubernetes.io/instance` pod label. No per-kit configuration SHALL be required — any K8s-native kit is covered automatically.

#### Scenario: Pod logs appear in VictoriaLogs with K8s metadata
- **WHEN** a K8s-native kit (e.g. Presto, TiDB) is running and emits a log line to stdout
- **THEN** that log line SHALL appear in VictoriaLogs
- **AND** the log record SHALL include `k8s.pod.name` matching the pod name
- **AND** the log record SHALL include `k8s.namespace.name` matching the pod namespace
- **AND** the log record SHALL include `k8s.container.name` matching the container name
- **AND** the log record SHALL include an attribute derived from the `app.kubernetes.io/instance` pod label

#### Scenario: Observability infrastructure logs are excluded
- **WHEN** the OTel Collector DaemonSet or Fluent Bit DaemonSet emits log output
- **THEN** those container logs SHALL NOT be collected by `filelog/containers`
- **AND** no feedback loop or duplication SHALL occur

### Requirement: Container log file paths excluded from system log collection
The `filelog/system` receiver SHALL exclude `/var/log/containers/**` and `/var/log/pods/**` from its glob patterns to prevent duplicate collection of container logs.

#### Scenario: Container logs are not double-collected
- **WHEN** a K8s pod emits a log line
- **THEN** that log line SHALL appear exactly once in VictoriaLogs
- **AND** it SHALL NOT also appear as a raw unattributed entry from `filelog/system`
