# Journald Log Collection

Dedicated journald-based log collection pipeline for systemd journal entries, including tool-runner output.

## Requirements

### Requirement: Journald OTel collector DaemonSet
The system SHALL deploy a dedicated OTel collector DaemonSet that reads systemd journal entries using the journald receiver in chroot mode and forwards them via OTLP to the main OTel collector on localhost.

The journald collector SHALL be isolated from the main OTel collector so that failures in journald collection do not affect existing log, metric, or trace pipelines.

**Scenarios:**

- **GIVEN** `grafana update-config` is run, **WHEN** the cluster is provisioned, **THEN** a DaemonSet named `otel-journald` SHALL be deployed on all nodes, separate from the existing `otel-collector` DaemonSet.
- **GIVEN** the journald collector starts, **WHEN** it initializes, **THEN** it SHALL use `root_path: /host` with the host filesystem mounted read-only at `/host` and execute the host's `journalctl` binary.
- **GIVEN** journal entries are read, **WHEN** they are processed, **THEN** they SHALL be exported via OTLP gRPC to `localhost:4317` (the main OTel collector's OTLP receiver).
- **GIVEN** the journald collector crashes or hangs, **WHEN** a failure occurs, **THEN** the main OTel collector SHALL continue collecting metrics, file-based logs, and traces without interruption.

### Requirement: Exec run tools log to journald
The `exec run` command SHALL allow systemd-run to write tool output to the journal (systemd default) instead of routing to direct file output.

**Scenarios:**

- **GIVEN** `exec run --bg --name watch-imports -t cassandra -- inotifywait -m /mnt/db1/cassandra/import` is executed, **WHEN** the tool runs, **THEN** the tool's stdout/stderr SHALL be captured by systemd journal under the `edl-exec-watch-imports` unit name.
- **GIVEN** `exec run -t cassandra -- ls /mnt/db1` is executed (no --bg flag), **WHEN** the tool runs, **THEN** the command output SHALL still be displayed to the user via SSH.

### Requirement: Journal entries have proper timestamps
Journal entries collected from exec-run tools SHALL have timestamps assigned by systemd at the time each line was written, enabling accurate correlation with other log sources in VictoriaLogs.

**Scenarios:**

- **GIVEN** an `inotifywait` event occurs at the same time as a Cassandra log entry, **WHEN** both are queried in VictoriaLogs, **THEN** both entries SHALL have timestamps within the same second, not offset by ingestion delay.
