## Why

JVM databases (Cassandra, etc.) suffer latency spikes from GC stop-the-world pauses, JVM safepoints, and OS scheduling jitter. These pauses are invisible to application metrics — a 50ms GC pause looks like a client timeout, not a GC event, unless you have dedicated JVM pause measurement.

[jHiccup](https://github.com/giltene/jHiccup) pioneered the approach of measuring JVM pauses by running a background thread that sleeps in a loop and records sleep overshoot. Each overshoot represents a period where the JVM was completely unavailable — whether due to GC, safepoints, or OS scheduling. This gives an accurate picture of "hiccup" latency that correlates directly with tail latency observed by clients.

Rather than running jHiccup as a separate agent (which has no pluggable output and requires file-tailing), we implement the same algorithm directly as a small Gradle subproject. This gives us:

- **OTel-native output** — no file I/O, no log parsing, direct OTLP to the local OTel Collector
- **HDR histogram fidelity** — full distribution data as OTel `ExponentialHistogram`, enabling Grafana heatmaps
- **Standalone JAR** — can be used independently by anyone, not just easy-db-lab users
- **No fork maintenance** — we own the code, uses the same `HdrHistogram` library as jHiccup

## What Changes

- New Gradle subproject `jvm-pause-agent/` — standalone fat JAR implementing the JVM pause detector
- Published to GitHub Packages (Maven) via GitHub Actions so external users can depend on it
- JAR bundled in the easy-db-lab distribution via the `distributions` block — available at `APP_HOME/lib/jvm-pause-agent.jar`
- Activated on database nodes as a `-javaagent` via `cassandra.in.sh` (same pattern as Pyroscope) — conditional on jar presence
- Sends `jvm.pause.duration` metrics as OTel `ExponentialHistogram` via OTLP gRPC to `localhost:4317`
- New Grafana heatmap dashboard showing JVM pause distribution over time per node

## Capabilities

### New Capabilities

- `jvm-pause-measurement`: Measures JVM hiccup pauses (GC, safepoints, OS jitter) and reports as OTel histograms

### Modified Capabilities

- `observability`: New `jvm.pause.duration` metric in VictoriaMetrics; new Grafana heatmap dashboard

## Impact

- New Gradle subproject `jvm-pause-agent/` with its own `build.gradle.kts`
- `settings.gradle` — add `include "jvm-pause-agent"`
- Root `build.gradle.kts` — add distribution content to bundle the JAR
- `packer/cassandra/cassandra.in.sh` — activate agent conditional on jar presence
- GitHub Actions — new publish workflow for the subproject JAR
- `GrafanaDashboard` enum — add `JVM_PAUSE` entry
- New Grafana dashboard JSON in `configuration/grafana/dashboards/`
- `docs/reference/` — document the new metric and dashboard
