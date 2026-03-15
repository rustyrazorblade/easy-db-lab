# Design: jvm-pause-agent

## Architecture

```
Cassandra JVM
  └── jvm-pause-agent (JVM agent, shadow JAR)
        ├── JvmPauseAgent.premain() — starts the detector thread
        └── JvmPauseDetector (daemon thread)
              ├── Sleeps 100ms per iteration
              ├── Records overshoot into HdrHistogram.Recorder
              └── Every 10s: exports via OTel DoubleHistogram → OTLP gRPC → localhost:4317
                                                                                      ↓
                                                               OTel Collector DaemonSet
                                                                      ↓
                                                            VictoriaMetrics (port 8428)
                                                                      ↓
                                                         Grafana heatmap (jvm-pause dashboard)
```

## Gradle Subproject (`jvm-pause-agent/`)

### Dependencies
- `org.hdrhistogram:HdrHistogram:2.2.2` — histogram recording
- `io.opentelemetry:opentelemetry-sdk` — SDK for creating meters
- `io.opentelemetry:opentelemetry-exporter-otlp` — OTLP gRPC exporter

### Shadow JAR configuration
- `Premain-Class: com.rustyrazorblade.jvmpauseagent.JvmPauseAgent`
- Relocate `io.opentelemetry` → `com.rustyrazorblade.jvmpauseagent.shaded.opentelemetry`
- Relocate `io.grpc` → `com.rustyrazorblade.jvmpauseagent.shaded.grpc`
- Target JVM 11 (compatible with Cassandra 4.x and 5.x)

## Agent Implementation

### JvmPauseAgent
- `premain(agentArgs, instrumentation)` — creates and starts `JvmPauseDetector` as daemon thread

### JvmPauseDetector
- Poll interval: 100ms (configurable via `jvm.pause.agent.poll.ms` system property)
- Export interval: 10s
- Metric: `jvm.pause.duration`, unit `ms`
- Histogram boundaries (ms): 0.1, 0.5, 1, 5, 10, 25, 50, 100, 250, 500, 1000, 5000, 10000
- OTLP endpoint: `localhost:4317` (configurable via `jvm.pause.agent.otlp.endpoint` system property)
- Resource attributes: `service.name`, `host.name` (from hostname), `cluster.name`
  (from `CLUSTER_NAME` env var via `jvm.pause.agent.cluster.name` system property)

## Distribution and Deployment

### Distribution layout
```
easy-db-lab-{version}/
  lib/
    jvm-pause-agent/
      jvm-pause-agent.jar        ← shadow JAR bundled at build time
```

### SetupInstance changes
- After existing cassandra setup, check if `$APP_HOME/lib/jvm-pause-agent/jvm-pause-agent.jar` exists
- If so, upload to Cassandra nodes as `/usr/local/jvm-pause-agent/jvm-pause-agent.jar`

### cassandra.in.sh activation
```bash
JVM_PAUSE_AGENT_JAR="/usr/local/jvm-pause-agent/jvm-pause-agent.jar"
if [ -f "$JVM_PAUSE_AGENT_JAR" ]; then
    export JVM_OPTS="$JVM_OPTS -javaagent:${JVM_PAUSE_AGENT_JAR}"
    export JVM_OPTS="$JVM_OPTS -Djvm.pause.agent.cluster.name=${CLUSTER_NAME:-unknown}"
fi
```

## Grafana Dashboard

Dashboard `jvm-pause.json` added to `GrafanaDashboard.JVM_PAUSE`.

Query: VictoriaMetrics `histogram_buckets(sum(increase(jvm_pause_duration_bucket[...])) by (le, host_name))`

Visualization: Grafana Heatmap panel showing pause duration distribution over time.
