## ADDED Requirements

### Requirement: CRI container log parsing for system logs
The `filelog/system` receiver SHALL parse the CRI container log format (`TIMESTAMP STREAM FLAG CONTENT`), extracting the timestamp and stream into attributes and replacing the log body with the actual content (stripping the CRI wrapper).

Lines that do not match the CRI format SHALL be passed through unchanged (`on_error: send`).

#### Scenario: Standard CRI log line is parsed
- **WHEN** the system receiver reads a log line `2026-03-01T01:41:49.964937855Z stderr F Some application log message`
- **THEN** the log entry body SHALL be `Some application log message`, the timestamp SHALL be `2026-03-01T01:41:49.964937855Z`, and the `stream` attribute SHALL be `stderr`

#### Scenario: Non-CRI log line passes through
- **WHEN** the system receiver reads a log line that does not match the CRI format (e.g., a plain syslog line)
- **THEN** the log entry SHALL be sent with the original body unchanged

#### Scenario: Partial CRI log lines are recombined
- **WHEN** the system receiver reads consecutive CRI log lines with `P` (partial) flags followed by an `F` (final) flag
- **THEN** the content portions SHALL be combined into a single log entry

### Requirement: CRI multiline grouping for system logs
The `filelog/system` receiver SHALL group continuation lines that do not start with a CRI timestamp prefix into the preceding log entry.

#### Scenario: Multi-line application output within CRI wrapper
- **WHEN** a CRI-wrapped log line is followed by lines that do not begin with an ISO 8601 timestamp
- **THEN** all lines SHALL be grouped into a single log entry before CRI parsing

### Requirement: Cassandra text log parsing
The `filelog/cassandra` receiver SHALL parse Cassandra's default text log format (`LEVEL [THREAD] TIMESTAMP SOURCE - MESSAGE`), extracting severity, thread, timestamp, source, and message into structured fields.

Lines that do not match the Cassandra log format SHALL be passed through unchanged (`on_error: send`).

#### Scenario: Standard Cassandra INFO log is parsed
- **WHEN** the cassandra receiver reads `INFO  [main] 2026-03-01 01:41:49,123 Server.java:123 - Starting listening for CQL clients`
- **THEN** the severity SHALL be `INFO`, the `thread` attribute SHALL be `main`, the timestamp SHALL be `2026-03-01 01:41:49,123`, the `source` attribute SHALL be `Server.java:123`, and the body SHALL be `Starting listening for CQL clients`

#### Scenario: Cassandra WARN log with complex thread name
- **WHEN** the cassandra receiver reads `WARN  [MemtableFlushWriter:1] 2026-03-01 01:41:49,123 ColumnFamilyStore.java:456 - Flushing large memtable`
- **THEN** the severity SHALL be `WARN` and the `thread` attribute SHALL be `MemtableFlushWriter:1`

#### Scenario: Unparseable Cassandra log line passes through
- **WHEN** the cassandra receiver reads a log line that does not match the expected format
- **THEN** the log entry SHALL be sent with the original body unchanged

### Requirement: Cassandra multiline grouping
The `filelog/cassandra` receiver SHALL group stack traces and continuation lines into the preceding log entry using `multiline.line_start_pattern`.

#### Scenario: Java stack trace grouped with originating log entry
- **WHEN** a Cassandra ERROR log line is followed by lines starting with whitespace or `at ` or `Caused by:`
- **THEN** all lines SHALL be grouped into a single log entry with the ERROR severity

#### Scenario: Consecutive log entries remain separate
- **WHEN** two Cassandra log entries each start with `LEVEL [THREAD]`
- **THEN** they SHALL remain as separate log entries
