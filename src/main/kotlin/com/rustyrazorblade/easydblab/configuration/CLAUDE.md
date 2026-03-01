# Configuration Package

This package manages cluster state, user configuration, S3 paths, and template substitution.

## CRITICAL: Testing Requirements for K8s Manifest Builders

**All manifest builders under `configuration/` MUST be tested with K3s TestContainers.** This is not optional.

### Required Tests (in `K8sServiceIntegrationTest`)

Every manifest builder must have:

1. **Apply test** — `buildAllResources()` applied via `serverSideApply()` to a real K3s cluster. Verify each resource (ConfigMap, Deployment, DaemonSet, Service) exists after apply.
2. **Image pull test** — All container images across all builders are verified pullable via `crictl pull` inside the K3s container. This catches wrong image names, wrong registries, and removed tags.
3. **No resource limits test** — All containers across all builders are verified to have NO `resources.limits` or `resources.requests` set. Resource limits cause OOMKill and CrashLoopBackOff on the control node.

### Rules for Manifest Builders

- **NEVER set resource limits or requests** — no `ResourceRequirementsBuilder`, no `MEMORY_LIMIT`, no `CPU_REQUEST` constants. The control node never needs these.
- **NEVER mock `TemplateService`** — always use the real instance. It only reads classpath resources.
- **NEVER mock manifest builder classes** — always use real instances in tests.
- **Always use the correct container registry** — e.g., `ghcr.io/cloudflare/ebpf_exporter` not `cloudflare/ebpf_exporter`. The image pull test catches this.
- When adding a new builder, you MUST add it to `K8sServiceIntegrationTest.collectAllResources()` and add a dedicated apply test.

### Test Location

`src/test/kotlin/.../services/K8sServiceIntegrationTest.kt` — single K3s container shared across all tests (`@TestInstance(PER_CLASS)`).

## Core State Management

### ClusterState (`ClusterState.kt`)

Central state data class persisted as `state.json`. Key fields:

- `name: String` — cluster name
- `clusterId: String` — unique UUID for EC2 tag-based discovery
- `hosts: Map<ServerType, List<ClusterHost>>` — all cluster hosts
- `infrastructure: InfrastructureState?` — VPC, subnet, security group IDs
- `initConfig: InitConfig?` — configuration from the `Init` command
- `emrCluster: EMRClusterState?` — optional EMR/Spark state
- `openSearchDomain: OpenSearchClusterState?` — optional OpenSearch state
- `s3Bucket: String?` — account-level S3 bucket
- `dataBucket: String` — per-cluster data bucket (`easy-db-lab-data-{clusterId}`) for ClickHouse data and CloudWatch metrics
- `backupHashes: Map<String, String>` — SHA-256 hashes of backed-up files
- `infrastructureStatus: InfrastructureStatus` — UP, DOWN, or UNKNOWN

Key methods:
- `getControlHost()` — first control node (convenience)
- `clusterPrefix()` — returns `"clusters/{name}-{clusterId}"`
- `metricsConfigId()` — returns `"edl-{name}-{clusterId}"` (truncated to 32 chars)
- `dataBucketName()` — returns `"easy-db-lab-data-{clusterId}"`
- `s3Path()` — extension function returning `ClusterS3Path` for this cluster

### ClusterHost

```kotlin
data class ClusterHost(
    val publicIp: String,
    val privateIp: String,
    val alias: String,          // e.g., "db0", "app0", "control0"
    val availabilityZone: String,
    val instanceId: String = "",
)
```

### ServerType (`ServerType.kt`)

```kotlin
enum class ServerType(val serverType: String) {
    Cassandra("db"),      // Alias prefix: "db0", "db1", ...
    Stress("app"),        // Alias prefix: "app0", "app1", ...
    Control("control"),   // Alias prefix: "control0", "control1", ...
}
```

### ClusterStateManager (`ClusterStateManager.kt`)

Handles persistence to `state.json`:
- `load(): ClusterState` — read from file
- `save(state)` — write to file (pretty-printed JSON)
- `exists(): Boolean` — check if state file exists
- `updateHosts()`, `updateEmrCluster()`, `updateInfrastructure()` — load-update-save atomically
- `markInfrastructureUp()`, `markInfrastructureDown()` — status helpers

Note: Uses Jackson (legacy) for serialization with lenient deserialization settings.

## Common Patterns

### Getting Host IPs

```kotlin
val cassandraHosts = clusterState.hosts[ServerType.Cassandra] ?: emptyList()
val firstCassandraIp = cassandraHosts.first().privateIp
val controlHost = clusterState.getControlHost()
```

### Creating ClusterState in Tests

```kotlin
val testState = ClusterState(
    name = "test-cluster",
    versions = mutableMapOf(),
    s3Bucket = "easy-db-lab-test-bucket",
    clusterId = "test-id",
    initConfig = InitConfig(region = "us-west-2"),
    hosts = mapOf(
        ServerType.Control to listOf(testControlHost),
        ServerType.Cassandra to listOf(testDbHost),
    ),
)
```

## S3 Path Management (`ClusterS3Path.kt`)

Immutable, type-safe S3 path abstraction. Each cluster is isolated under `clusters/{name}-{clusterId}/`.

```kotlin
val path = ClusterS3Path.from(clusterState)
path.cassandra()          // s3://bucket/clusters/.../cassandra
path.backups()            // s3://bucket/clusters/.../backups
path.kubeconfig()         // s3://bucket/clusters/.../config/kubeconfig
path.resolve("custom")    // s3://bucket/clusters/.../custom
path.getKey()             // path without s3://bucket prefix
```

Factory methods: `from(clusterState)`, `root(bucket)`, `fromKey(bucket, key)`

## Template Substitution (`TemplateService`)

**Location:** `services/TemplateService.kt` (Koin-managed)

Handles `__KEY__` placeholder substitution in K8s manifests, YAML configs, etc. Uses `__` delimiters (not `${}`) to avoid conflicts with Grafana template syntax.

**Context variables** (built from cluster state):
- `BUCKET_NAME` (resolves to `dataBucket` when set, falls back to `s3Bucket`), `AWS_REGION`, `CLUSTER_NAME`, `CONTROL_NODE_IP`
- `METRICS_FILTER_ID`, `CLUSTER_S3_PREFIX`

**Key methods:**
- `fromString()` / `fromFile()` / `fromResource()` — create `Template` instances

**Template class:**
```kotlin
val template = templateService.fromString("endpoint: __CONTROL_NODE_IP__:8080")
val result = template.substitute()  // uses context variables
val result = template.substitute(mapOf("EXTRA" to "value"))  // extra vars override context
```

## User Configuration

- **`User`** — data class with AWS credentials, region, key pair, Tailscale config, S3 bucket
- **`UserConfigProvider`** — persists to `${profileDir}/settings.yaml` with caching
- SSH key always at `${profileDir}/secret.pem`

## Other Configuration Classes

- **`CassandraVersion`** — version definition (Cassandra, Java, Python versions)
- **`CassandraYaml`** — cassandra.yaml manipulation (Jackson-based)
- **`Seeds`** — seed list management
- **`Host`** — legacy host data class (use `ClusterHost` for new code)
- **`Arch`** — CPU architecture enum (`AMD64`, `ARM64`)
- **`ClusterConfigWriter`** — generates SSH config and environment.sh files

## Grafana Subpackage (`grafana/`)

All Grafana K8s resources are built programmatically using Fabric8:

- **`GrafanaDashboard`** — enum registry of all dashboards. Single source of truth for dashboard metadata (configMapName, volumeName, mountPath, resourcePath, optional flag). Adding a new dashboard = add an enum entry + drop a JSON file.
- **`GrafanaManifestBuilder`** — builds all Grafana K8s resources (provisioning ConfigMap, dashboard ConfigMaps, Deployment) as typed Fabric8 objects. Uses `TemplateService` for `__KEY__` variable substitution in dashboard JSON.
- **`GrafanaDatasourceConfig`** — datasource provisioning YAML generation.
- **Dashboard JSON files** — stored in `resources/.../configuration/grafana/dashboards/*.json`. Raw JSON with `__KEY__` template placeholders.

## Pyroscope Subpackage (`pyroscope/`)

All Pyroscope K8s resources are built programmatically using Fabric8:

- **`PyroscopeManifestBuilder`** — builds all Pyroscope K8s resources (server ConfigMap, Service, Deployment, eBPF ConfigMap, eBPF DaemonSet) as typed Fabric8 objects. The server runs on the control plane with S3 backend storage. Config values (`__BUCKET_NAME__`, `__AWS_REGION__`, `__PYROSCOPE_STORAGE_PREFIX__`) are substituted at build time via TemplateService — NOT runtime env var expansion. **Important:** Pyroscope's `storage.prefix` rejects forward slashes, so the prefix is flat (`pyroscope.{name}-{id}`). S3 auth uses the default AWS SDK credential chain (IMDS/instance role) — v1.18.0 lacks `native_aws_auth_enabled`.
- **Config resources** — `config.yaml` (Pyroscope server config with S3 backend, `__KEY__` placeholders) and `config.alloy` (Grafana Alloy eBPF config) stored in `resources/.../configuration/pyroscope/`.

### Profiling Architecture

Three independent profiling mechanisms run simultaneously:

1. **Grafana Alloy eBPF DaemonSet** (all nodes) — `pyroscope.ebpf` component collects `process_cpu` profiles only (eBPF limitation). Image: `grafana/alloy:v1.13.1`. Labels: `hostname`, `cluster` from env vars. Also profiles ClickHouse (CPU only, since it's C++).
2. **Pyroscope Java Agent (Cassandra)** — `/usr/local/pyroscope/pyroscope.jar` (v2.3.0, installed by packer). Collects `cpu`, `alloc`, `lock`, `wall` profiles via JFR/async-profiler. Activated only when `PYROSCOPE_SERVER_ADDRESS` env var is set AND agent JAR exists. The profiler event is configurable via `PYROSCOPE_PROFILER_EVENT` env var (default: `cpu`, alternative: `wall`).
3. **Pyroscope Java Agent (Stress Jobs)** — Same agent JAR mounted into stress K8s Jobs via hostPath volume from `/usr/local/pyroscope`. Configured via `JAVA_TOOL_OPTIONS` env var in `StressJobService.buildJob()`. Collects `cpu`, `alloc`, `lock` profiles.
4. **Pyroscope Java Agent (Spark/EMR)** — `/opt/pyroscope/pyroscope.jar` (installed by EMR bootstrap action). Added to Spark driver and executor via `extraJavaOptions` in `EMRSparkService.buildOtelSparkConf()`. Collects `cpu`, `alloc` (512k threshold), `lock` (10ms threshold) profiles in JFR format. Service name: `spark-<job-name>`. Sends to Pyroscope server at `http://<control-ip>:4040`.

### Activation Flow

1. `SetupInstance` writes `/etc/default/cassandra` with `PYROSCOPE_SERVER_ADDRESS=http://<control_ip>:4040` and `CLUSTER_NAME`.
2. `GrafanaUpdateConfig` deploys Pyroscope server to K8s (control plane, port 4040, hostNetwork).
3. When Cassandra starts, `cassandra.in.sh` checks for the env var and JAR, then adds `-javaagent` JVM opts. `PYROSCOPE_PROFILER_EVENT` can override the profiler event (default: `cpu`).
4. When a stress job starts, `StressJobService` mounts the agent JAR and sets `JAVA_TOOL_OPTIONS` with all Pyroscope properties.

See `spec/PYROSCOPE.md` for full architecture details and debugging steps.

## Beyla Subpackage (`beyla/`)

- **`BeylaManifestBuilder`** — builds Beyla eBPF auto-instrumentation ConfigMap + DaemonSet. Runs on all nodes with hostNetwork/hostPID/privileged for eBPF access.
- **Config resource** — `beyla-config.yaml` stored in `resources/.../configuration/beyla/`.

## OTel Subpackage (`otel/`)

- **`OtelManifestBuilder`** — builds the main OTel Collector ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, and DaemonSet. Runs on all nodes, collects host metrics, Prometheus scrapes, file-based logs (system, Cassandra, ClickHouse), and OTLP. Uses `k8sattributes` processor to derive `node_role` from K8s node label `type` (db, app, control). RBAC grants read-only access to pods and nodes. Config uses OTel runtime env expansion (`${env:HOSTNAME}`), not `__KEY__` templates.
- **`JournaldOtelManifestBuilder`** — builds a dedicated OTel Collector DaemonSet (`otel-journald`) for systemd journal collection, isolated from the main collector. Uses chroot mode (`root_path: /host`) with host `/` mounted read-only to execute the host's `journalctl` binary. Filters to `edl-exec-*` units. Forwards logs via OTLP to the main collector on `localhost:4317`. Health check on port 13134. No ServiceAccount/RBAC needed (doesn't access K8s API).
- **Config resources** — `otel-collector-config.yaml` (main collector) and `otel-journald-config.yaml` (journald collector) stored in `resources/.../configuration/otel/`.

## ebpf_exporter Subpackage (`ebpfexporter/`)

- **`EbpfExporterManifestBuilder`** — builds ebpf_exporter DaemonSet (no ConfigMap). Runs on all nodes with hostNetwork/hostPID/privileged. Uses built-in example programs (`biolatency`, `xfsdist`, `cachestat`) from the container image. No TemplateService needed. Available examples: https://github.com/cloudflare/ebpf_exporter/tree/master/examples

## Victoria Subpackage (`victoria/`)

- **`VictoriaManifestBuilder`** — builds VictoriaMetrics and VictoriaLogs Services + Deployments. Both run on control plane with hostPath data volumes. No ConfigMap/TemplateService needed.

## Tempo Subpackage (`tempo/`)

- **`TempoManifestBuilder`** — builds Tempo ConfigMap + Service + Deployment. Runs on control plane with S3 backend for trace storage in the account-level bucket under cluster prefix. Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`, `${CLUSTER_S3_PREFIX}`).
- **Config resource** — `tempo.yaml` stored in `resources/.../configuration/tempo/`.

## Registry Subpackage (`registry/`)

- **`RegistryManifestBuilder`** — builds Docker Registry Deployment. Runs on control plane with TLS cert hostPath mount and HTTPS probes. No TemplateService needed.

## S3 Manager Subpackage (`s3manager/`)

- **`S3ManagerManifestBuilder`** — builds S3 Manager Deployment. Runs on control plane with IAM-based auth. No TemplateService needed.

## YACE Subpackage (`yace/`)

- **`YaceManifestBuilder`** — builds YACE (Yet Another CloudWatch Exporter) ConfigMap + Deployment. Runs on control plane, scrapes AWS CloudWatch metrics for S3, EBS, EC2, and OpenSearch services. Exposes Prometheus metrics on port 5001, scraped by OTel collector. (EMR metrics removed — replaced by direct OTel collection on Spark nodes.)
- **Config resource** — `yace-config.yaml` stored in `resources/.../configuration/yace/` with `__AWS_REGION__` template variable for region substitution.
- **Auto-discovery** — uses tag-based auto-discovery with the `easy_cass_lab=1` tag to find cluster resources in CloudWatch.
