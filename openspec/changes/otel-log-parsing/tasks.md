## 1. System/K3s CRI Log Parsing

- [ ] 1.1 Add `multiline` config to `filelog/system` receiver with `line_start_pattern: ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}` to group continuation lines
- [ ] 1.2 Add `regex_parser` operator to `filelog/system` to parse CRI format (`TIMESTAMP STREAM FLAG CONTENT`), extracting timestamp, stream, logtag, and log content
- [ ] 1.3 Add `recombine` operator after regex_parser to merge partial CRI lines (P flag) into complete entries (F flag)
- [ ] 1.4 Add `move` operator to replace log body with parsed content from `attributes.log`

## 2. Cassandra Log Parsing

- [ ] 2.1 Add `multiline` config to `filelog/cassandra` receiver with `line_start_pattern: ^\s*(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\s+\[` to group stack traces
- [ ] 2.2 Add `regex_parser` operator to `filelog/cassandra` to parse `LEVEL [THREAD] TIMESTAMP SOURCE - MESSAGE` format, extracting severity, thread, timestamp, source, and message
- [ ] 2.3 Add `move` operator to replace log body with parsed message content

## 3. Testing

- [ ] 3.1 Deploy to a running cluster and verify system logs show clean message bodies without CRI wrapper in VictoriaLogs
- [ ] 3.2 Verify Cassandra logs show correct `detected_level` values (INFO, WARN, ERROR) in the Log Investigation dashboard
- [ ] 3.3 Verify a Cassandra stack trace appears as a single log entry rather than split across multiple rows
- [ ] 3.4 Verify unparseable log lines pass through unchanged (not dropped)

## 4. Documentation

- [ ] 4.1 Update `docs/user-guide/victoria-logs.md` to describe the structured log fields available for filtering
