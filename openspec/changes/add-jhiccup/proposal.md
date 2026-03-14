## Why

JVM-based databases like Cassandra experience latency spikes caused by GC pauses, JVM safepoint operations, and OS scheduling jitter. These pauses are invisible to application-level metrics — a tail latency spike in Cassandra client measurements could be a network issue, a slow query, or a 500ms GC pause. Without a way to separate JVM pause time from application logic, it's impossible to pinpoint root cause.

jHiccup (https://github.com/giltene/jHiccup) measures JVM hiccups directly: it runs a background thread that sleeps for a fixed interval and records how much longer it actually slept. The excess sleep time captures JVM-induced pauses (GC, safepoints) and OS-level jitter (scheduler delays, CPU steal). This data is written as an HdrHistogram log file, providing a ground-truth record of when the JVM was not making progress.

easy-db-lab already instruments Cassandra nodes with Pyroscope (CPU/alloc/lock profiling), GC logs, and OTel metrics, but none of these directly answer "how long was the JVM completely stopped?" jHiccup fills this gap.

## What Changes

- **New Packer install script** (`packer/cassandra/install/install_jhiccup.sh`): Downloads the jHiccup jar from GitHub releases and installs it at `/usr/local/jhiccup/jhiccup.jar`.
- **JVM agent activation** in `packer/cassandra/cassandra.in.sh`: Activates jHiccup as a `-javaagent` when the jar is present. Writes hiccup logs to `/mnt/db1/cassandra/logs/hiccup.hlog` with a 1-second reporting interval. Conditional on jar existence, consistent with the Pyroscope activation pattern.
- **OTel file log collection**: The existing OTel collector already has a `filelog` receiver configured for Cassandra log files. A new pipeline entry collects the `.hlog` file and forwards entries to VictoriaLogs, making hiccup data searchable alongside Cassandra and system logs.
- **Grafana dashboard**: A new `JHICCUP` entry in `GrafanaDashboard` enum with a dashboard that visualizes hiccup data from VictoriaLogs, showing pause duration over time and histogram distributions per node.

## Capabilities

### New Capabilities

- `jhiccup`: jHiccup JVM agent installed on database nodes, capturing JVM hiccup intervals to log files that are collected into VictoriaLogs and visualized in Grafana.

### Modified Capabilities

- `observability`: Adds JVM hiccup data as a new signal alongside metrics, logs, profiles, and traces. The OTel collector gains a new filelog pipeline for `.hlog` files.

## Impact

- **New file**: `packer/cassandra/install/install_jhiccup.sh` — downloads and installs the jHiccup jar.
- **Modified file**: `packer/cassandra/cassandra.in.sh` — activates jHiccup agent when jar is present.
- **Modified file**: OTel collector config (`otel-collector-config.yaml` or equivalent) — new filelog receiver entry for hiccup log files.
- **Modified file**: `GrafanaDashboard.kt` — new `JHICCUP` enum entry.
- **New file**: `configuration/grafana/dashboards/jhiccup.json` — Grafana dashboard JSON.
- **Modified file**: Packer build file (`.pkr.hcl`) — adds install script to provisioner list.
- **No new K8s resources** — collection handled by the existing OTel DaemonSet via config change.
- **No new dependencies** — jHiccup is a single JAR with no external dependencies at runtime.
- **Tests**: Install script packer test, OTel config unit test for new receiver, `GrafanaDashboard` enum test for new entry, `K8sServiceIntegrationTest` for updated OTel manifest.
- **Docs**: Update observability reference to document jHiccup as a new signal.
