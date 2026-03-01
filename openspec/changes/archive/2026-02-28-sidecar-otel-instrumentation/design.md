## Context

The Cassandra Sidecar is a standalone Java process managed by systemd (`cassandra-sidecar.service`). It runs on every Cassandra node alongside the OTel Collector DaemonSet (which listens on `localhost:4317` for OTLP gRPC).

Cassandra itself is already instrumented with both Pyroscope and MAAC agents via `cassandra.in.sh`. The sidecar has no instrumentation today.

The sidecar's systemd service currently has:
```
Environment="JAVA_OPTS=-Dsidecar.config=file:///etc/cassandra-sidecar/cassandra-sidecar.yaml -Dsidecar.logdir=/mnt/db1/cassandra/logs/sidecar"
```

The OTel Java agent JAR does not currently exist on the VM. The Pyroscope JAR is already at `/usr/local/pyroscope/pyroscope.jar`.

## Goals / Non-Goals

**Goals:**
- Sidecar JVM metrics (heap, GC, threads) and traces exported to the local OTel Collector DaemonSet
- Sidecar CPU/allocation/lock profiles sent to the Pyroscope server
- Agent activation gated on environment variables, same pattern as Cassandra
- OTel Java agent JAR installed during Packer build for reuse by any Java process on the node

**Non-Goals:**
- Adding a Grafana dashboard for sidecar metrics (future work)
- Modifying any Kotlin code — this is entirely AMI-level configuration
- Instrumenting the sidecar's Vert.x HTTP layer with custom spans (the OTel agent auto-instruments Vert.x)

## Decisions

### Use systemd EnvironmentFile for agent activation

**Decision:** Use `/etc/default/cassandra-sidecar` as a systemd `EnvironmentFile` to inject agent flags, rather than hardcoding them in the service file.

**Alternatives considered:**
- Hardcode `-javaagent` flags directly in the service file — simple but always-on with no way to disable, and requires hardcoded control node IP
- Use a wrapper script — more complex, another moving part

**Rationale:** Matches the Cassandra pattern where `/etc/default/cassandra` provides `PYROSCOPE_SERVER_ADDRESS` and `CLUSTER_NAME`. The `SetupInstance` command already writes environment files for Cassandra nodes — extending it to also write the sidecar env file is straightforward. The systemd `EnvironmentFile=-` directive (with `-`) makes it optional, so the sidecar starts fine without it.

### Install OTel Java agent to /usr/local/otel/

**Decision:** Download `opentelemetry-javaagent.jar` to `/usr/local/otel/opentelemetry-javaagent.jar` during Packer build.

**Rationale:** Follows the same pattern as Pyroscope (`/usr/local/pyroscope/pyroscope.jar`). The JAR is available for any Java process on the node. A specific version is pinned in the install script for reproducibility.

### Export to localhost:4317 (local DaemonSet collector)

**Decision:** The OTel agent sends OTLP to `localhost:4317`, which is the OTel Collector DaemonSet running with hostNetwork.

**Rationale:** The DaemonSet already runs on every node with hostNetwork=true and accepts OTLP on port 4317. No new infrastructure needed. The DaemonSet's `metrics/otlp` pipeline forwards to VictoriaMetrics.

### Service name: cassandra-sidecar

**Decision:** Set `OTEL_SERVICE_NAME=cassandra-sidecar` and `pyroscope.application.name=cassandra-sidecar`.

**Rationale:** Distinguishes sidecar telemetry from Cassandra telemetry in dashboards and Pyroscope UI.

## Risks / Trade-offs

- **OTel agent adds JVM overhead** → The OTel Java agent typically adds ~2-5% CPU overhead. The sidecar is not on the hot path for database operations, so this is acceptable.
- **Pyroscope agent uses async-profiler** → Same overhead as on Cassandra (~1-2% CPU). Acceptable for the sidecar.
- **New AMI build required** → Changes are AMI-level (Packer scripts + systemd service). Existing clusters won't get instrumentation until re-provisioned. This is the normal workflow.
- **Agent JAR version drift** → The OTel agent version is pinned in the install script. Must be updated manually when upgrading. Same pattern as Pyroscope.
