## Context

Spark/EMR nodes are EC2 instances provisioned by AWS EMR. They currently have the OTel Java agent installed via a bootstrap action, which sends telemetry directly to the control node's OTel Collector over the network. There is no local collector on Spark nodes, no host metrics collection, no profiling, and the EMR Grafana dashboard relies entirely on CloudWatch data via YACE.

The control node runs the full observability stack: OTel Collector (DaemonSet), VictoriaMetrics, VictoriaLogs, Tempo (S3-backed), Pyroscope (filesystem-backed), and YACE. Pyroscope is the only storage backend still using local disk.

## Goals / Non-Goals

**Goals:**
- OTel Collector running as a systemd service on every EMR node, collecting host metrics and forwarding OTLP to the control node
- Pyroscope Java agent attached to Spark JVMs for CPU, allocation, and lock profiling
- Pyroscope switched to S3 storage (eliminating backup concern)
- EMR dashboard rebuilt around OTel host metrics and Spark JVM metrics
- YACE EMR CloudWatch scraping removed (replaced by direct collection)
- Tempo and Pyroscope S3 paths exposed in /status endpoint

**Non-Goals:**
- eBPF profiling on EMR nodes (no K8s DaemonSet available on EMR)
- OpenSearch observability changes (managed service, CloudWatch only)
- OTel Collector config changes on the control node (already receives OTLP)
- Pyroscope data migration from filesystem to S3 (clean cut)

## Decisions

### D1: OTel Collector installed via EMR bootstrap action as systemd service

The existing `bootstrap-otel.sh` already runs on all EMR nodes. Expanding it to install the collector binary, write a config file, and create a systemd unit is the simplest path — no additional bootstrap actions needed.

The collector config is a stripped-down version of the control node's: OTLP receivers (gRPC + HTTP) for Spark agent telemetry, host metrics receiver for node-level data, batch processor, and a single OTLP gRPC exporter pointing at the control node's collector (`<control_ip>:4317`).

**Alternative considered:** Installing the collector as a YARN auxiliary service. Rejected — unnecessarily coupled to YARN, harder to debug, and the collector needs to run regardless of YARN state.

### D2: Pyroscope Java agent installed alongside OTel agent in bootstrap

The bootstrap script downloads `pyroscope.jar` to `/opt/pyroscope/pyroscope.jar` (same pattern as the OTel agent at `/opt/otel/`). The Pyroscope agent version and download URL are added to `Constants.kt`.

Spark submit adds `-javaagent:/opt/pyroscope/pyroscope.jar` plus system properties to both driver and executor `extraJavaOptions`, matching the existing Cassandra/stress job pattern.

### D3: Control node IP passed to bootstrap via EMR configuration

The bootstrap script needs the control node's private IP to configure the collector's OTLP exporter endpoint. This is passed as an EMR configuration property or environment variable during cluster creation, alongside the existing S3 path for the bootstrap script.

### D4: Pyroscope switches to S3 backend matching Tempo's pattern

Pyroscope's `config.yaml` changes from filesystem to S3 backend using runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`), exactly like Tempo. `PyroscopeManifestBuilder` injects these env vars from the cluster-config ConfigMap. The hostPath volume for `/mnt/db1/pyroscope` is removed.

**Alternative considered:** Adding a backup service for filesystem Pyroscope (like VictoriaBackupService). Rejected — S3 backend is cleaner, eliminates the backup problem entirely, and Pyroscope natively supports it.

### D5: EMR dashboard rebuilt around OTel host metrics

The old dashboard used `aws_elasticmapreduce_*` metrics from YACE. The new dashboard uses:
- `system.cpu.*`, `system.memory.*`, `system.disk.*`, `system.network.*` from OTel host metrics on Spark nodes
- JVM metrics from the OTel Java agent (`jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`)
- Pyroscope profiling links

Panels are organized per-node with hostname filtering.

### D6: S3 paths added to /status as new fields in S3Paths

`S3Paths` data class gets `tempo` and `pyroscope` fields. `StatusCache` populates them from the data bucket. Tempo uses prefix `tempo/`, Pyroscope uses a similar prefix (e.g., `pyroscope/`).

## Risks / Trade-offs

- **Bootstrap script complexity** — The script now installs three components (OTel agent, OTel collector, Pyroscope agent). If any download fails, the bootstrap action fails and EMR cluster creation fails. → Mitigation: Each download is independent with error checking; collector and Pyroscope failures should be non-fatal warnings rather than hard failures.

- **Control node IP must be known at bootstrap time** — The bootstrap script needs the control IP before the collector can start. → Mitigation: The control node is always provisioned before EMR; its IP is in cluster state.

- **Pyroscope S3 transition is a clean cut** — Existing filesystem profile data is not migrated. → Mitigation: Profiles are ephemeral analysis data, not long-term storage. Acceptable to lose historical profiles on upgrade.

- **EMR dashboard loses CloudWatch-specific metrics** — YARN container counts, HDFS utilization, apps running/pending are not available from OTel host metrics. → Mitigation: Host metrics (CPU, memory, disk, network) plus JVM metrics provide more actionable data for Spark job debugging. YARN/HDFS metrics can be re-added later via JMX scraping if needed.
