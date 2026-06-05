## MODIFIED Requirements

### Requirement: OTel Collector collects logs from multiple sources including K8s pods
The OTel Collector SHALL collect logs from the following sources, each in a dedicated pipeline:
- **`logs/local`**: Host file-based logs — system (`/var/log/**`), tools (`/var/log/easydblab/tools/`), Cassandra (`/mnt/db1/cassandra/logs/`), ClickHouse server and keeper logs.
- **`logs/containers`**: K8s pod stdout/stderr via `/var/log/containers/*.log`, enriched with Kubernetes metadata.
- **`logs/otlp`**: OTLP-pushed logs from remote sources (e.g. Spark nodes).

The `filelog/system` receiver in `logs/local` SHALL explicitly exclude `/var/log/containers/**` and `/var/log/pods/**` to prevent duplication with `logs/containers`.

#### Scenario: Host file logs reach VictoriaLogs
- **WHEN** a Cassandra or ClickHouse process writes to its log file on the host filesystem
- **THEN** that log entry SHALL appear in VictoriaLogs via the `logs/local` pipeline

#### Scenario: K8s pod logs reach VictoriaLogs with metadata
- **WHEN** a K8s-native kit pod writes to stdout or stderr
- **THEN** that log entry SHALL appear in VictoriaLogs via the `logs/containers` pipeline
- **AND** the entry SHALL include `k8s.pod.name`, `k8s.namespace.name`, and `app.kubernetes.io/instance` attributes

#### Scenario: Container logs are not duplicated in system logs
- **WHEN** any K8s pod emits a log line
- **THEN** that log line SHALL NOT appear in VictoriaLogs as a raw system log entry from `filelog/system`
