## Context

Log entries in VictoriaLogs currently appear as unparsed raw text. The OTel collector's filelog receivers for system and Cassandra logs have no parsing operators — only the ClickHouse receivers use `json_parser`. This means:

- CRI container logs (from K3s) include the full CRI wrapper (`TIMESTAMP STREAM FLAG CONTENT`), duplicating timestamps and cluttering the message body
- Cassandra text logs have no level extraction, so `detected_level` shows "unknown" in the Log Investigation dashboard
- Stack traces and continuation lines are split across multiple log entries

The OTel collector config lives at `src/main/resources/.../configuration/otel/otel-collector-config.yaml` and is deployed as a ConfigMap by `OtelManifestBuilder`.

## Goals / Non-Goals

**Goals:**
- Parse CRI container log format in `filelog/system` to strip the wrapper and extract timestamp + stream
- Parse Cassandra text log format in `filelog/cassandra` to extract level, timestamp, thread, and source
- Group multiline log entries (stack traces) into single records for both system and Cassandra logs
- All parsing uses `on_error: send` so unparseable lines pass through rather than being dropped

**Non-Goals:**
- Cassandra JSON logging — requires adding extra JARs (logstash-logback-encoder or logback-contrib) since Cassandra bundles logback 1.2.9 which lacks built-in `JsonEncoder` (added in 1.3.8)
- Modifying the `filelog/tools` receiver — tool-runner logs are unstructured by nature
- Changing the ClickHouse receivers — they already use `json_parser`
- Modifying VictoriaLogs or Grafana — this is purely OTel collector config

## Decisions

### 1. Use regex_parser operators (not the container operator)

The OTel `container` operator type auto-parses CRI logs and extracts K8s pod metadata from file paths. However, our logs are at `/var/log/**/*.log` (not `/var/log/pods/*/*/*.log`), and we don't need K8s pod metadata extraction. A simple `regex_parser` targeting the CRI format gives us exactly what we need without assumptions about file path structure.

**Alternative considered:** The `container` operator — simpler config but designed for pod log paths and extracts K8s resource attributes we don't use.

### 2. CRI format parsing for filelog/system

The CRI container log format is: `TIMESTAMP STREAM FLAG CONTENT`

Example: `2026-03-01T01:41:49.964937855Z stderr F 2026-03-01T01:41:49.964Z warn internal/transaction.go:142 Failed to scrape...`

Operator pipeline:
1. `regex_parser` — extracts `time`, `stream`, `logtag`, and `log` (the actual content)
2. Regex: `^(?P<time>[^ Z]+Z) (?P<stream>stdout|stderr) (?P<logtag>[^ ]*) ?(?P<log>.*)$`
3. Timestamp parsed from `attributes.time` with layout `%Y-%m-%dT%H:%M:%S.%LZ`
4. Body is moved from `attributes.log` to replace the original raw line

This strips the CRI wrapper so the body contains only the actual log content. The `stream` attribute is preserved for filtering stdout vs stderr.

### 3. Cassandra text log parsing for filelog/cassandra

Cassandra's default log format (from logback PatternLayout): `LEVEL  [THREAD] TIMESTAMP SOURCE - MESSAGE`

Example: `INFO  [main] 2026-03-01 01:41:49,123 Server.java:123 - Starting listening...`

Operator pipeline:
1. `regex_parser` — extracts `severity`, `thread`, `timestamp`, `source`, and `message`
2. Regex: `^(?P<severity>[A-Z]+)\s+\[(?P<thread>[^\]]+)\]\s+(?P<timestamp>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2},\d{3})\s+(?P<source>[^\s]+)\s+-\s+(?P<message>.*)$`
3. Severity parsed from `attributes.severity`
4. Timestamp parsed from `attributes.timestamp` with layout `%Y-%m-%d %H:%M:%S,%L`

### 4. Multiline grouping via line_start_pattern

For Cassandra logs, stack traces and continuation lines don't start with the `LEVEL [THREAD]` pattern. Using `multiline.line_start_pattern` groups everything between log-level-prefixed lines into a single entry.

Pattern: `^\s*(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\s+\[`

For system (CRI) logs, each CRI-wrapped line starts with an ISO timestamp. Continuation lines within the content don't have a CRI prefix.

Pattern: `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}`

### 5. Recombine operator for CRI partial logs

CRI uses `P` (partial) and `F` (final) flags to indicate log line continuation at the transport level. After parsing CRI fields, a `recombine` operator merges partial entries:
- `is_last_entry`: `attributes.logtag == 'F'`
- `combine_field`: `attributes.log`

This handles the case where a single application log line exceeds the CRI line limit and gets split by the container runtime.

## Risks / Trade-offs

- **Regex mismatch on unexpected formats** → Mitigation: `on_error: send` passes unparseable lines through as raw text. No data loss.
- **Cassandra log format variation across versions** → The `LEVEL [THREAD] TIMESTAMP SOURCE - MESSAGE` format is consistent across Cassandra 4.0, 4.1, and 5.0 (all use the same default logback.xml pattern). Risk is low.
- **Performance of regex parsing** → These are simple regexes running on log lines. The OTel collector is designed for this workload. The `memory_limiter` processor already caps memory at 256 MiB.
- **Multiline grouping timeout** → If a log entry is never followed by another entry (last line of a file), the multiline grouper holds it until a timeout. OTel's default `force_flush_period` (500ms) handles this.
