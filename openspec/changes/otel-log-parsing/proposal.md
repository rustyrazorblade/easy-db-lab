## Why

Log entries in VictoriaLogs appear as unparsed raw text — timestamps are duplicated (CRI wrapper + embedded), log levels aren't extracted, and stack traces are split across multiple entries. This makes the Log Investigation dashboard and `logs query` CLI nearly unusable for debugging because you can't filter by level, the messages are cluttered with CRI metadata, and exceptions are fragmented into individual lines.

## What Changes

- **System/K3s log parsing** — add operators to the `filelog/system` receiver to parse the CRI container log format (`TIMESTAMP STREAM FLAG CONTENT`), extracting the timestamp, stream (stdout/stderr), and stripping the CRI wrapper so the body contains only the actual log content
- **Cassandra log parsing** — add operators to the `filelog/cassandra` receiver to extract timestamp, log level, thread name, and logger/source from Cassandra's text log format (e.g., `INFO  [main] 2026-03-01 01:41:49,123 Server.java:123 - Starting...`)
- **Multiline log grouping** — add multiline configuration to the Cassandra and system filelog receivers so that stack traces and continuation lines are grouped into a single log entry instead of being split across multiple records

## Capabilities

### New Capabilities
- `otel-log-parsing`: OTel collector filelog operator pipelines for structured parsing of system, K3s, and Cassandra logs with multiline support

### Modified Capabilities
- `observability`: Log collection now produces structured fields (level, timestamp, thread) instead of raw text, enabling field-based filtering in dashboards and CLI

## Impact

- **Modified file**: `src/main/resources/.../configuration/otel/otel-collector-config.yaml` — adding operators to filelog receivers
- **Dashboard improvement**: Log Investigation dashboard will show meaningful `detected_level` values and cleaner message content
- **No API changes**: purely OTel collector configuration
- **Risk**: regex parsers could fail on unexpected log formats — use `on_error: send` to pass through unparseable lines rather than dropping them
