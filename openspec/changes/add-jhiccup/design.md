## Context

Cassandra and other JVM databases suffer from latency spikes caused by GC pauses, JVM safepoint operations, and OS scheduling jitter. easy-db-lab already collects GC logs, CPU profiles (Pyroscope), and application metrics (MAAC), but none of these measure how long the JVM was completely paused — unable to make progress on any thread.

jHiccup (https://github.com/giltene/jHiccup) fills this gap. It runs a background thread that sleeps for a known interval and records the actual elapsed time. The excess is a hiccup — time the JVM was not running. Output is written as HdrHistogram log files (`.hlog`), one line per interval containing: relative start time, interval length, max hiccup value, and a base64-encoded histogram.

## Goals / Non-Goals

**Goals:**
- Install jHiccup on database nodes via Packer
- Activate jHiccup as a JVM `-javaagent` in `cassandra.in.sh`, conditioned on jar presence
- Write hiccup logs to `/mnt/db1/cassandra/logs/hiccup.hlog` (1-second intervals)
- Collect hiccup log files into VictoriaLogs via the existing OTel filelog pipeline
- Add a Grafana dashboard showing max hiccup per interval and histogram distribution per node

**Non-Goals:**
- Decoding the full HdrHistogram binary payload from log lines — max value is sufficient for initial visualization
- Exposing hiccup data as Prometheus metrics — VictoriaLogs is sufficient for this signal
- Running jHiccup on non-JVM database nodes (ClickHouse, OpenSearch are out of scope)
- Per-version jHiccup configuration — a single version is installed and used across all Cassandra versions

## Decisions

### 1. jHiccup jar, not jHiccup-standalone

jHiccup can run as a standalone process or as a JVM agent. The agent mode attaches directly to the Cassandra JVM and captures pauses within that JVM's process, including GC safepoints, which standalone cannot see. We use agent mode: `-javaagent:/usr/local/jhiccup/jhiccup.jar`.

**Alternative considered:** Running jHiccup standalone as a separate process — rejected because it measures OS-level jitter but misses JVM-internal pauses (GC stop-the-world) that happen within the Cassandra JVM.

### 2. Log file destination in Cassandra log directory

jHiccup is configured to write to `/mnt/db1/cassandra/logs/hiccup.hlog`. This directory is already:
- On the data volume (not ephemeral)
- Collected by the existing OTel `filelog/cassandra` receiver (which covers `*.log`)

However, `.hlog` files are not covered by the `*.log` glob. We add a new dedicated `filelog/jhiccup` receiver instead of extending the cassandra glob, to keep source attribution clean and avoid sending binary histogram payloads through the cassandra log pipeline.

**Alternative considered:** Writing to `/var/log/` — rejected because the data volume path is already collected and `/var/log/` adds another collection path.

### 3. OTel filelog receiver with regex parsing

The `.hlog` format has comment lines (`#`-prefixed), a CSV header line, and data lines:
```
0.000,1.000,1234,HISTFAAA...
```
Fields: `startTimestamp(s), intervalLength(s), maxHiccup(µs), encodedHistogram`

The OTel filelog receiver supports `operators` for regex parsing. We parse each data line and extract:
- `hiccup_max_us` — max hiccup value in microseconds (field 3)
- `interval_start` — relative start timestamp in seconds (field 1)
- Skip comment lines (`type: filter` operator matching `^#` or `^"`)

This structured data is forwarded to VictoriaLogs where Grafana can query it.

**Alternative considered:** Sending raw lines to VictoriaLogs and using Grafana regex — rejected because OTel-side parsing produces cleaner structured attributes and avoids per-query regex overhead.

### 4. No new Packer build file provisioner section — extend existing

The jHiccup install script is added to the same Packer provisioner list as other Cassandra install scripts. The jar is unconditionally installed on every Cassandra AMI build. Activation in `cassandra.in.sh` is conditional on jar existence (same pattern as Pyroscope), so nodes without the jar silently skip jHiccup rather than failing startup.

### 5. Grafana dashboard using VictoriaLogs as data source

The hiccup dashboard queries VictoriaLogs using the victoriametrics-logs-datasource plugin (already installed). The dashboard shows:
- **Max hiccup per interval** — time series per node (y-axis in milliseconds, converted from `hiccup_max_us`)
- **Heatmap** — pause frequency × magnitude over time
- **Node selector** — variable filter on `host.name` attribute

The VictoriaLogs LogsQL query extracts the `hiccup_max_us` attribute and renders it as a numeric time series.

## File Changes

| File | Change |
|------|--------|
| `packer/cassandra/install/install_jhiccup.sh` | New — download and install jHiccup jar |
| `packer/cassandra/cassandra.pkr.hcl` (or equivalent) | Modified — add install script to provisioner |
| `packer/cassandra/cassandra.in.sh` | Modified — activate jHiccup agent |
| `src/main/resources/.../configuration/otel/otel-collector-config.yaml` | Modified — new `filelog/jhiccup` receiver and pipeline entry |
| `src/main/kotlin/.../configuration/grafana/GrafanaDashboard.kt` | Modified — new `JHICCUP` enum entry |
| `src/main/resources/.../configuration/grafana/dashboards/jhiccup.json` | New — Grafana dashboard JSON |

## Risks / Trade-offs

- **HdrHistogram binary data**: The encoded histogram field in each `.hlog` line is large (hundreds of bytes of base64). The `filelog/jhiccup` receiver's filter operators drop lines that don't match the data format, and the parsed output only stores the numeric max value — the base64 payload is discarded. This keeps VictoriaLogs storage overhead minimal.
- **Relative timestamps in .hlog**: jHiccup records timestamps relative to base time (defined in the file header), not as absolute Unix timestamps. OTel file log receiver assigns ingestion-time timestamps. For 1-second intervals this introduces at most ~1-second jitter in Grafana, which is acceptable for pause analysis.
- **No hiccup data for non-Cassandra nodes**: jHiccup is installed only on Cassandra images. This is intentional — the feature is scoped to JVM-based databases. Can be extended to OpenSearch nodes in a future change.
- **Log rotation**: The `.hlog` file grows continuously. jHiccup does not support log rotation natively. At 1-second intervals, each line is ~200 bytes, so growth is ~700KB/hour. For a typical 8-hour test this is ~5MB — negligible. The `filelog/jhiccup` receiver uses `start_at: end` to avoid replaying on restart.
