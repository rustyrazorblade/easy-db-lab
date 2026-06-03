## 1. Cilium CNI Adoption

- [x] 1.1 Update K3s server and agent start commands in `Up.kt` to pass `--flannel-backend=none --disable-network-policy`
- [x] 1.2 Create `CiliumManifestBuilder` (or a `CiliumInstallService`) that installs Cilium via helm on the control node with `kubeProxyReplacement: true` and Hubble enabled
- [x] 1.3 Call Cilium install from `Up.kt` after K3s bootstraps, before any other cluster setup; wait for Cilium DaemonSet to be Ready
- [x] 1.4 Add Hubble Prometheus scrape job to the base `otel-collector-config.yaml` static config
- [x] 1.5 Write tests verifying K3s flags include `--flannel-backend=none` and Cilium install is called before workload setup

## 2. `metrics` Block in install.yaml

- [x] 2.1 Define `WorkloadMetrics` sealed class with subtypes `Scrape(port, path)`, `JavaAgent(serviceName)`, and `HelmNative` in `services/` (kotlinx.serialization)
- [x] 2.2 Add optional `metrics: WorkloadMetrics?` field to the `WorkloadInstallConfig` data class that deserializes `install.yaml`
- [x] 2.3 Update `install.yaml` for `clickhouse` — add `metrics: {type: scrape, port: 9363, path: /metrics}`
- [x] 2.4 Update `install.yaml` for `presto` — add appropriate `metrics` block (research Trino metrics endpoint)
- [x] 2.5 Write deserialization tests for all three metrics mode variants and the absent-metrics case

## 3. Dynamic OtelManifestBuilder

- [x] 3.1 Add `WorkloadScrapeConfig(jobName, port, path)` data class
- [x] 3.2 Add Fabric8 K8s client dependency to `OtelManifestBuilder`; add method to list all ConfigMaps with label `easydblab.com/workload-metrics=true` and return `List<WorkloadScrapeConfig>`
- [x] 3.3 Update `OtelManifestBuilder.buildConfigMap()` to accept `List<WorkloadScrapeConfig>` and inject one prometheus scrape job per entry
- [x] 3.4 Remove the hardcoded `clickhouse` scrape job from `otel-collector-config.yaml`
- [x] 3.5 Write unit tests for `buildConfigMap()` verifying: static jobs always present, dynamic jobs injected correctly, empty list produces only static jobs
- [x] 3.6 Add `OtelManifestBuilder` to `K8sServiceIntegrationTest` apply test and image pull test

## 4. Metrics Registry — Write and Delete

- [x] 4.1 Implement `MetricsRegistryService` (or extend existing K8s service) with `register(workload, scrapeConfig)` and `deregister(workload)` methods that create/delete the `easydblab-metrics-<workload>` ConfigMap with label `easydblab.com/workload-metrics=true`
- [x] 4.2 Wire `register()` call into `WorkloadInstallCommand` after `start` phase completes successfully and `metrics.type == scrape`
- [x] 4.3 Wire `deregister()` call into `WorkloadInstallCommand` after `stop` phase completes
- [x] 4.4 Wire `OtelManifestBuilder` regeneration call after both `register()` and `deregister()` — reads full registry, rebuilds ConfigMap, applies it
- [x] 4.5 Ensure no registry write or OTel sync occurs when `start` phase fails
- [x] 4.6 Ensure `deregister()` is a no-op (not an error) when the ConfigMap does not exist
- [x] 4.7 Write tests for `register()`, `deregister()`, and the full `start`/`stop` integration using a mock K8s client

## 5. hostPort Model for K8s Workloads

- [x] 5.1 Update `clickhouse` templates (`clickhouseinstallation.yaml.template`) to declare hostPort mappings for client ports (8123, 9000) and metrics port (9363)
- [x] 5.2 Update `presto` values.yaml template to declare hostPort mappings for the coordinator port (8080)
- [x] 5.3 Document the port assignment convention (which workloads use which hostPorts) in `docs/platform-substrate.md`

## 6. generate-workload Skill Update

- [x] 6.1 Update `.claude/skills/generate-workload/SKILL.md` research step to include: metrics endpoint (type, port, path), whether the workload is JVM-based (java-agent candidate), and whether the helm chart has native OTLP support (helm-native candidate)
- [x] 6.2 Update the skill's `install.yaml` format reference to include the `metrics` block schema and examples for all three modes
- [x] 6.3 Update the skill's guardrails section to note that `metrics` registration and OTel sync are automatic — no steps needed in `start`/`stop` for this

## 7. Documentation

- [x] 7.1 Update `docs/platform-substrate.md` with the hostPort port exposure model, port remapping pattern, and the metrics registration lifecycle
- [x] 7.2 Update `CLAUDE.md` if architecture or pattern descriptions are affected by Cilium adoption or the dynamic OTel config pattern
