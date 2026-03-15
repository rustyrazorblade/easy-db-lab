## Context

The JVM pause detection algorithm is straightforward: a background thread sleeps in a tight loop and records how much it overslept relative to its requested sleep duration. Each overshoot represents a window where the JVM was completely unresponsive (GC stop-the-world, JVM safepoint, OS preemption). The `HdrHistogram` library (already used by jHiccup and Cassandra itself) captures the distribution with microsecond resolution and a very large dynamic range.

The main cluster already runs an OTel Collector DaemonSet on every node, accepting OTLP on `localhost:4317`. VictoriaMetrics stores native histograms, and Grafana can render them as heatmaps via the `histogram_buckets()` transform.

## Goals / Non-Goals

**Goals:**
- Measure JVM hiccup pauses on database nodes with microsecond resolution
- Export full histogram distribution as OTel `ExponentialHistogram` via OTLP
- Bundle the agent JAR in the easy-db-lab distribution for use on provisioned nodes
- Publish the JAR to GitHub Packages so it can be used independently
- Grafana heatmap dashboard for `jvm.pause.duration`

**Non-Goals:**
- Replicating jHiccup's `.hlog` file format — we go directly to OTel
- Supporting non-Cassandra databases in the initial implementation (the agent is generic but activation is wired for Cassandra)
- Instrumenting the control node

## Decisions

### Implement from scratch instead of wrapping jHiccup

**Decision:** Implement `JvmPauseDetector` directly using `HdrHistogram.Recorder` rather than using jHiccup as a dependency.

**Rationale:** jHiccup's reporting is hardwired to `.hlog` files with no pluggable output interface. To intercept histogram data at runtime requires either a fork or bytecode injection. Reimplementing the algorithm is ~50 lines using the same `HdrHistogram` library. We get direct OTel output with no file I/O round-trip.

### OTel ExponentialHistogram for heatmap support

**Decision:** Report `jvm.pause.duration` as an OTel `DoubleHistogram` with explicit bucket boundaries matched to typical JVM pause ranges.

**Rationale:** OTel SDK `DoubleHistogram` with `EXPLICIT_BUCKET_HISTOGRAM` aggregation maps directly to VictoriaMetrics native histograms. Grafana's heatmap panel renders VictoriaMetrics histograms natively via `histogram_buckets()`. Bucket boundaries should cover 100µs → 10s with logarithmic spacing (covering GC sub-millisecond pauses up to full STW events).

Note: OTel Java SDK's `ExponentialHistogram` aggregation is in alpha. We use explicit buckets for stability, which gives equivalent visual fidelity for the range of JVM pause values we expect.

### Standalone Gradle subproject + fat JAR

**Decision:** `jvm-pause-agent/` is an independent Gradle subproject that produces a shadow (fat) JAR with all dependencies included.

**Rationale:** The agent must run on database nodes that don't have easy-db-lab's classpath. A fat JAR is self-contained. The subproject can also be published independently to GitHub Packages for external use.

### Published to GitHub Packages (Maven)

**Decision:** A new GitHub Actions workflow publishes the `jvm-pause-agent` JAR to `https://maven.pkg.github.com/rustyrazorblade/easy-db-lab`.

**Rationale:** GitHub Packages is already used for the container image. Publishing the JAR there makes it available to anyone with a GitHub token. The workflow triggers on tags and main branch pushes, matching the container publishing pattern.

### JAR bundled in distribution via `distributions` block

**Decision:** The root `build.gradle.kts` `distributions.main.contents` block adds the `jvm-pause-agent` shadow JAR to `lib/` in the distribution tarball.

**Rationale:** When easy-db-lab provisions nodes, it uploads files from `APP_HOME`. Including the JAR in the distribution means it's automatically available at `APP_HOME/lib/jvm-pause-agent.jar` both in `installDist` (dev mode using the built artifact) and in the release tarball.

### Activation via cassandra.in.sh (conditional on jar presence)

**Decision:** Add a block to `packer/cassandra/cassandra.in.sh` that appends `-javaagent:/path/to/jvm-pause-agent.jar` to `JVM_OPTS` only if the JAR exists.

**Rationale:** Same pattern as Pyroscope. The jar is deployed by `SetupInstance` from the distribution's `lib/` directory. The conditional keeps AMI builds clean — a new AMI is not required to activate or deactivate the agent.

### OTLP endpoint: localhost:4317

**Decision:** The agent sends to `localhost:4317` (OTLP gRPC) by default, configurable via system property `jvm.pause.agent.otlp.endpoint`.

**Rationale:** Matches all other OTel instrumentation on the node. The OTel Collector DaemonSet runs on every node with hostNetwork=true.

## Agent Architecture

```
JvmPauseDetectorAgent (premain)
  └── JvmPauseDetector (Thread)
        ├── HdrHistogram.Recorder (microsecond resolution)
        ├── interval loop (default: 10ms sleep, 5s reporting interval)
        └── OtelReporter
              ├── OTel SDK DoubleHistogram "jvm.pause.duration" (unit: us)
              ├── resource attributes: host.name, service.name=jvm-pause-agent
              └── OTLP gRPC exporter → localhost:4317
```

The agent runs in two modes:
1. **`-javaagent` mode** — attaches to any JVM via `premain`, starts the detector thread
2. **`-jar` mode** — standalone process for testing (useful outside of `-javaagent` context)

## Bucket Boundaries

Explicit histogram bucket boundaries (in microseconds):
`100, 250, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000, 2500000, 5000000, 10000000`

This covers 100µs (sub-millisecond GC) through 10s (catastrophic pause), with logarithmic spacing.

## Grafana Dashboard

The `JVM_PAUSE` dashboard queries VictoriaMetrics:
- Panel: Heatmap using `histogram_buckets(sum(rate(jvm_pause_duration_bucket[1m])) by (le, instance))`
- Shows pause distribution over time, one row per node
- Color scale: log-linear from green (rare/short) to red (frequent/long)

## Deployment Flow

1. `easy-db-lab cluster create` → `installDist` includes `lib/jvm-pause-agent.jar`
2. `SetupInstance` uploads `APP_HOME/lib/jvm-pause-agent.jar` to each node at `/usr/local/jvm-pause-agent/jvm-pause-agent.jar`
3. `cassandra.in.sh` detects jar presence → appends `-javaagent:/usr/local/jvm-pause-agent/jvm-pause-agent.jar`
4. Cassandra JVM starts with the agent → OTel data flows to localhost:4317 → VictoriaMetrics
5. Grafana heatmap shows pause distribution

## Risks / Trade-offs

- **OTel SDK dependency size** — The fat JAR will include OTel SDK (~3-5 MB). Acceptable for a one-time deploy.
- **Agent classpath isolation** — As a `-javaagent`, the agent runs in the target JVM's classloader. OTel SDK classes must be isolated to avoid conflicts with Cassandra's own OTel usage. Solution: use `Class-Path` manifest or relocate packages in the shadow JAR.
- **Bucket precision** — Explicit buckets lose sub-bucket detail compared to HDR histograms. The chosen boundaries are sufficient for operational visibility. True HDR fidelity would require OTel ExponentialHistogram (future upgrade path).
- **GitHub Packages authentication** — External users need a GitHub token to pull from GitHub Packages. This is a GitHub limitation, not specific to this project.
