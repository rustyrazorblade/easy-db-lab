# Configuration Package

This package manages cluster state, user configuration, S3 paths, and template substitution.

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
- `backupHashes: Map<String, String>` — SHA-256 hashes of backed-up files
- `infrastructureStatus: InfrastructureStatus` — UP, DOWN, or UNKNOWN

Key methods:
- `getControlHost()` — first control node (convenience)
- `clusterPrefix()` — returns `"clusters/{name}-{clusterId}"`
- `metricsConfigId()` — returns `"edl-{name}-{clusterId}"` (truncated to 32 chars)
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
- `BUCKET_NAME`, `AWS_REGION`, `CLUSTER_NAME`, `CONTROL_NODE_IP`
- `METRICS_FILTER_ID`, `CLUSTER_S3_PREFIX`

**Key methods:**
- `extractResources(dir, filter)` — extract K8s YAML without substitution (used by `Init`)
- `extractAndSubstituteResources(dir, filter)` — extract with `__KEY__` substitution (used by `K8Apply`, `GrafanaDashboardService`)
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

- **`PyroscopeManifestBuilder`** — builds all Pyroscope K8s resources (server ConfigMap, Service, Deployment, eBPF ConfigMap, eBPF DaemonSet) as typed Fabric8 objects. The server runs on the control plane with a hostPath volume at `/mnt/db1/pyroscope`. Directory permissions are set via SSH in `K8Apply` before deploying (no init container).
- **Config resources** — `config.yaml` (Pyroscope server config) and `config.alloy` (Grafana Alloy eBPF config) stored in `resources/.../configuration/pyroscope/`.

## Beyla Subpackage (`beyla/`)

- **`BeylaManifestBuilder`** — builds Beyla eBPF auto-instrumentation ConfigMap + DaemonSet. Runs on all nodes with hostNetwork/hostPID/privileged for eBPF access.
- **Config resource** — `beyla-config.yaml` stored in `resources/.../configuration/beyla/`.

## OTel Subpackage (`otel/`)

- **`OtelManifestBuilder`** — builds OTel Collector ConfigMap + DaemonSet. Runs on all nodes, collects host metrics, Prometheus scrapes, file-based logs, and OTLP. Config uses OTel runtime env expansion (`${env:HOSTNAME}`), not `__KEY__` templates.
- **Config resource** — `otel-collector-config.yaml` stored in `resources/.../configuration/otel/`.

## ebpf_exporter Subpackage (`ebpfexporter/`)

- **`EbpfExporterManifestBuilder`** — builds ebpf_exporter ConfigMap + DaemonSet. Runs on all nodes with hostNetwork/hostPID/privileged. Provides TCP retransmit, block I/O, and VFS latency eBPF metrics.
- **Config resource** — `config.yaml` stored in `resources/.../configuration/ebpfexporter/`.

## Victoria Subpackage (`victoria/`)

- **`VictoriaManifestBuilder`** — builds VictoriaMetrics and VictoriaLogs Services + Deployments. Both run on control plane with hostPath data volumes. No ConfigMap/TemplateService needed.

## Tempo Subpackage (`tempo/`)

- **`TempoManifestBuilder`** — builds Tempo ConfigMap + Service + Deployment. Runs on control plane with S3 backend for trace storage. Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`).
- **Config resource** — `tempo.yaml` stored in `resources/.../configuration/tempo/`.

## Vector Subpackage (`vector/`)

- **`VectorManifestBuilder`** — builds two Vector deployments: node DaemonSet (all nodes, system/db log collection) and S3 Deployment (control plane, EMR log ingestion via SQS). Config uses Vector runtime env expansion.
- **Config resources** — `vector-node.yaml` and `vector-s3.yaml` stored in `resources/.../configuration/vector/`.

## Registry Subpackage (`registry/`)

- **`RegistryManifestBuilder`** — builds Docker Registry Deployment. Runs on control plane with TLS cert hostPath mount and HTTPS probes. No TemplateService needed.

## S3 Manager Subpackage (`s3manager/`)

- **`S3ManagerManifestBuilder`** — builds S3 Manager Deployment. Runs on control plane with IAM-based auth. No TemplateService needed.
