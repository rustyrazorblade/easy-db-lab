## Why

K8s workloads installed via the platform substrate (`install clickhouse`, `install scylladb`, etc.) have no standard way to wire their metrics into the OTel collector. Each workload exposes metrics differently — via a Prometheus scrape endpoint, a JVM Java agent, or native helm-configurable telemetry — and today there is no mechanism for a workload's install definition to declare which approach it uses or to register itself with the running OTel collector when it starts.

## What Changes

- **New `metrics` block in `install.yaml`**: Top-level declarative field with three mode types: `scrape` (Prometheus endpoint via hostPort), `java-agent` (OTel Java agent JAR injected via hostPath), and `helm-native` (metrics configured through helm values, no OTel config change needed).
- **K8s-native metrics registry**: Each workload writes a ConfigMap `easydblab-metrics-<workload>` (labelled `easydblab.com/workload-metrics=true`) when it starts, containing its scrape config (job-name, port, path). Deleted before the workload stops.
- **Dynamic `OtelManifestBuilder`**: Extended to list all `easydblab-metrics-*` ConfigMaps and inject a per-workload scrape job alongside the existing static jobs (MAAC, Beyla, ebpf-exporter, yace). Regenerates and applies the OTel collector ConfigMap.
- **New `sync-otel` install step type**: Triggers `OtelManifestBuilder` to regenerate the OTel collector ConfigMap from current registry state. Added to `start` phase (after workload is Ready) and `stop` phase (after metrics ConfigMap is deleted, before workload teardown).
- **hostPort port exposure model**: K8s workloads use standard pod networking with `hostPort` mappings for external access. Port remapping is supported (e.g., ScyllaDB CQL `containerPort: 9042 → hostPort: 9142`) to avoid conflicts with host processes like Cassandra. Apps running as K8s pods use ClusterIP Services on native ports internally.
- **`generate-workload` skill update**: Skill researches the workload's metrics endpoint (type, port, path) and proposes the `metrics` block. Includes `configmap` + `sync-otel` steps in the correct lifecycle order.

## Capabilities

### New Capabilities

- `workload-metrics-declaration`: The `metrics` block in `install.yaml` — three modes, port/path fields, and the K8s ConfigMap registry pattern that connects workload lifecycle to OTel config.
- `dynamic-otel-scrape-config`: `OtelManifestBuilder` reads `easydblab-metrics-*` ConfigMaps and generates a complete OTel collector config combining static infrastructure jobs with dynamic per-workload scrape jobs.

### Modified Capabilities

- `typed-install-steps`: New `sync-otel` step type added to the supported step table.
- `observability`: OTel collector config is no longer fully static — scrape targets for K8s workloads are derived from the metrics registry ConfigMaps at apply time.

## Impact

- `services/InstallStep.kt`: Add `SyncOtel` data class to the sealed interface.
- `configuration/otel/OtelManifestBuilder.kt`: Add K8s client dependency; add `buildConfigMap(extraScrapeJobs)` overload that accepts dynamically discovered scrape configs.
- `commands/install/WorkloadInstallCommand.kt` (or step executor): Implement `SyncOtel` step execution — list ConfigMaps by label, build scrape configs, call `OtelManifestBuilder`, apply.
- `install.yaml` files (clickhouse, presto): Add `metrics` block and `configmap` + `sync-otel` steps in correct lifecycle order.
- `.claude/skills/generate-workload/SKILL.md`: Update skill to research metrics endpoint and propose the full metrics configuration pattern.
- Docs: Update `docs/platform-substrate.md` with hostPort port exposure model and metrics registration pattern.
