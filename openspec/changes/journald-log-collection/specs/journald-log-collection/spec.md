## ADDED Requirements

### Requirement: Journald OTel collector DaemonSet
The system SHALL deploy a dedicated OTel collector DaemonSet that reads systemd journal entries using the journald receiver in chroot mode and forwards them via OTLP to the main OTel collector on localhost.

The journald collector SHALL be isolated from the main OTel collector so that failures in journald collection do not affect existing log, metric, or trace pipelines.

#### Scenario: Journald collector deploys alongside main collector
- **WHEN** `grafana update-config` is run
- **THEN** a DaemonSet named `otel-journald` SHALL be deployed on all nodes, separate from the existing `otel-collector` DaemonSet

#### Scenario: Journald collector reads host journal via chroot
- **WHEN** the journald collector starts
- **THEN** it SHALL use `root_path: /host` with the host filesystem mounted read-only at `/host` and execute the host's `journalctl` binary

#### Scenario: Journald collector forwards to main collector
- **WHEN** journal entries are read
- **THEN** they SHALL be exported via OTLP gRPC to `localhost:4317` (the main OTel collector's OTLP receiver)

#### Scenario: Journald collector failure does not affect main collector
- **WHEN** the journald collector crashes or hangs
- **THEN** the main OTel collector SHALL continue collecting metrics, file-based logs, and traces without interruption

### Requirement: Exec run tools log to journald
The `exec run` command SHALL allow systemd-run to write tool output to the journal (systemd default) instead of routing to direct file output.

#### Scenario: Background tool output goes to journal
- **WHEN** `exec run --bg --name watch-imports -t cassandra -- inotifywait -m /mnt/db1/cassandra/import` is executed
- **THEN** the tool's stdout/stderr SHALL be captured by systemd journal under the `edl-exec-watch-imports` unit name

#### Scenario: Foreground tool output is returned to user
- **WHEN** `exec run -t cassandra -- ls /mnt/db1` is executed (no --bg flag)
- **THEN** the command output SHALL still be displayed to the user via SSH

### Requirement: Journal entries have proper timestamps
Journal entries collected from exec-run tools SHALL have timestamps assigned by systemd at the time each line was written, enabling accurate correlation with other log sources in VictoriaLogs.

#### Scenario: Tool output timestamps correlate with Cassandra logs
- **WHEN** an `inotifywait` event occurs at the same time as a Cassandra log entry
- **THEN** both entries SHALL have timestamps within the same second in VictoriaLogs, not offset by ingestion delay
