## MODIFIED Requirements

### Requirement: Log collection produces structured fields
The OTel collector log pipeline SHALL produce structured log entries with extracted fields (severity/level, timestamp, source attributes) instead of raw unparsed text for system and Cassandra log sources.

VictoriaLogs `detected_level` SHALL reflect the actual log level from parsed entries, enabling field-based filtering in the Log Investigation dashboard and `logs query` CLI.

#### Scenario: System logs have clean message bodies
- **WHEN** system logs are viewed in the Log Investigation dashboard
- **THEN** the log body SHALL contain only the application log content without CRI wrapper metadata (no duplicate timestamps, no stream/flag fields in the message)

#### Scenario: Cassandra logs have detected level
- **WHEN** Cassandra logs are viewed in the Log Investigation dashboard
- **THEN** the `detected_level` column SHALL show the actual Cassandra log level (INFO, WARN, ERROR, DEBUG, TRACE) instead of "unknown"

#### Scenario: Stack traces appear as single entries
- **WHEN** a Cassandra exception with a stack trace is viewed in the Log Investigation dashboard
- **THEN** the entire exception (message + stack trace) SHALL appear as a single log entry, not split across multiple rows
