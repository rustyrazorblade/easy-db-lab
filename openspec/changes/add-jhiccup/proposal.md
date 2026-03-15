# Proposal: jvm-pause-agent

## Problem

JVM databases suffer latency spikes that are invisible to application metrics. GC stop-the-world
pauses, JVM safepoint stalls, and OS scheduling jitter all cause the JVM to be completely
unresponsive for tens or hundreds of milliseconds. These pauses appear as tail latency in client
metrics but the root cause is not visible.

## Solution

Implement `jvm-pause-agent`, a custom JVM agent that measures JVM pauses using the same algorithm
as jHiccup: a background thread sleeps in a tight loop and measures how much longer each sleep
takes than expected. The overshoot is the time the JVM was unable to run — a direct measurement
of GC pauses, safepoint stalls, and OS jitter.

The agent is implemented as a standalone Gradle subproject (`jvm-pause-agent/`), packaged as a
shadow JAR, and emits data natively via OTLP gRPC to the local OTel collector. This data flows
to VictoriaMetrics and is visualized as a heatmap in Grafana.

## Key Design Decisions

- **Custom implementation, not jHiccup**: jHiccup's reporting is hardwired to `.hlog` files with
  no plugin interface. Instead, we implement the same ~20-line algorithm using `HdrHistogram.Recorder`
  directly. This gives us full OTel-native output with no fork to maintain.

- **Gradle subproject**: Ships as `jvm-pause-agent/` in this repo, published to GitHub Packages
  for external use, and bundled in the easy-db-lab distribution for automatic deployment.

- **OTel-native output**: Emits a `DoubleHistogram` named `jvm.pause.duration` (unit: `ms`) with
  explicit bucket boundaries via OTLP gRPC to `localhost:4317` (the local OTel collector DaemonSet).

- **Shadow JAR with relocation**: OTel packages are relocated to avoid classloader conflicts with
  Cassandra's own OTel instrumentation.

- **Conditional activation**: `cassandra.in.sh` activates the agent only if the JAR is present
  at `/usr/local/jvm-pause-agent/jvm-pause-agent.jar`, following the same pattern as Pyroscope.

- **Deployed by SetupInstance**: The JAR is bundled in the easy-db-lab distribution under
  `lib/jvm-pause-agent/` and uploaded to Cassandra nodes by `setup-instances`.
