## MODIFIED Requirements

### Requirement: Journald log collection pipeline

The system SHALL deploy a dedicated Fluent Bit DaemonSet (`fluent-bit-journald`) that reads systemd journal entries and forwards them via OTLP to the main OTel collector on localhost, on environments whose nodes have a systemd journal (the `SYSTEMD_JOURNAL` capability). An environment without it SHALL skip the DaemonSet and report the skip. The journald collector SHALL be isolated from the main OTel collector so that failures in journald collection do not affect existing log, metric, or trace pipelines.

#### Scenario: Journald collector deployed as separate DaemonSet

- **GIVEN** the environment provides `SYSTEMD_JOURNAL`, **WHEN** the observability stack is deployed, **THEN** a DaemonSet named `fluent-bit-journald` SHALL be deployed, separate from the `otel-collector` DaemonSet.

#### Scenario: Journald collector skipped without a journal

- **GIVEN** the environment lacks `SYSTEMD_JOURNAL`, **WHEN** the observability stack is deployed, **THEN** no `fluent-bit-journald` DaemonSet SHALL be applied or awaited, and a `StageSkipped` event SHALL be emitted.

#### Scenario: Journal entries forwarded to main collector

- **GIVEN** journal entries are read, **WHEN** they are processed, **THEN** they SHALL be exported via OTLP gRPC to `localhost:4317` (the main OTel collector's OTLP receiver).

#### Scenario: Journald collector failure does not affect other pipelines

- **GIVEN** the journald collector crashes or hangs, **WHEN** a failure occurs, **THEN** the main OTel collector SHALL continue collecting metrics, file-based logs, and traces without interruption.
