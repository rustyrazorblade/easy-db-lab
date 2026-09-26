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

`src/integrationTest/kotlin/.../services/K8sServiceIntegrationTest.kt` — single K3s container shared across all tests (`@TestInstance(PER_CLASS)`). This test lives in the slow `integrationTest` source set (Docker-only), not `src/test/`; run it with `./gradlew integrationTest` (or `check`).

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
- `tailFlush: TailFlushRecord?` — the pre-teardown flush a `down` completed (time, what it verified), so a re-run skips it; `markInfrastructureUp()` clears it

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
- `ACCOUNT_BUCKET` (the account bucket; the observability stack never writes to the data bucket), `AWS_REGION`, `CLUSTER_NAME`, `CONTROL_NODE_IP`, `TENANT` (the cluster's observability tenant), `PROFILES_S3_PREFIX` (`observability/profiles`)
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

- **`CassandraVersion`** — version definition (Cassandra, Java, Python versions). `lazy: true` declares a version without baking it: the entry still ships in every node's `/etc/cassandra_versions.yaml`, so it is discoverable and installable at runtime via `cassandra install`, but the AMI bake skips it. Loaded from `packer/cassandra/cassandra_versions.yaml` merged with the profile's `cassandra_versions/` extras.
- **`CassandraYaml`** — cassandra.yaml manipulation (Jackson-based)
- **`Seeds`** — seed list management
- **`Host`** — legacy host data class (use `ClusterHost` for new code)
- **`Arch`** — CPU architecture enum (`AMD64`, `ARM64`)
- **`ClusterConfigWriter`** — generates SSH config and environment.sh files

## Grafana Subpackage (`grafana/`)

All Grafana K8s resources are built programmatically using Fabric8:

- **`GrafanaDashboard`**, **`GrafanaDashboardCatalog`**, **`GrafanaDashboardTreeWriter`**, **`GrafanaDashboardProvisioningConfig`** — the core dashboard tree, discovered from the classpath and copied to the control node as files; the mechanism is described once, in [`dashboards/CLAUDE.md`](../../../../../../dashboards/CLAUDE.md).
- **`GrafanaManifestBuilder`** — builds the Grafana K8s resources (provisioning ConfigMap and Deployment) as typed Fabric8 objects; nothing it builds varies with the dashboard tree. Uses `TemplateService` only for the cluster name. The Deployment mounts the `/mnt/db1/grafana` hostPath at `/var/lib/grafana`, which is how the copied tree reaches the pod, and includes a `grafana-image-renderer` sidecar container (port 8081) for server-side panel rendering.
- **`GrafanaDatasourceConfig`** — datasource provisioning YAML generation.
- **Dashboard JSON files** — stored in the top-level `dashboards/<folder>/` tree at the project root. `processResources` in `build.gradle.kts` copies the tree onto the classpath under a `dashboards/` prefix at build time. Also published as a standalone zip via GitHub Actions for consumption by other projects.

## Observability Store (`ObservabilityStore.kt`, `ConfigHashAnnotator.kt`)

- **`ObservabilityStore`** — the one place that builds the observability paths from the account bucket and the cluster's tenant. The backend prefixes carry no tenant, because each backend makes its own tenant directories: `metricsPrefix()` (`observabilitymetrics` — Mimir accepts only letters and digits there), `logsPrefix()` (`observability/logs`), `tracesPrefix()` (`observability/traces`), `profilesPrefix()` (`observability/profiles`). `annotationsRoot()` is `observability/annotations/<tenant>`, and `annotationsArtifact()` adds a `SnapshotName`. No metrics or logs snapshot exists: Mimir and Loki write to S3 themselves.
- **`SnapshotName`** — `<yyyyMMdd-HHmmss>_<name>-<clusterId>`, the name of an annotations backup. `SnapshotName.firstFree` moves a name past every second whose S3 key already exists, so two backups of one cluster in the same second never overwrite each other; `GrafanaAnnotationBackupService` names every backup through it.
- **`ConfigHashAnnotator`** — stamps each Deployment/DaemonSet/StatefulSet pod template with `easydblab.com/config-hash`, a SHA-256 of the rendered pod template (serialized with object keys sorted, the hash annotation left out) and of the ConfigMaps it reads (volumes, `configMapKeyRef`, `envFrom`), including ConfigMaps built elsewhere such as `cluster-config`. Hashing the template makes the hash change exactly when Kubernetes rolls the workload, so an image, probe, env or volume change is reported as a change; both sides of the comparison are hashes of what easy-db-lab rendered, never of the server-defaulted object. `ObservabilityStackService` applies it to every stage, and `OtelSyncService` re-applies the collector when a kit start/stop regenerates its ConfigMap (no forced rollout-restart, so the template hash tracks the ConfigMap and the next deploy sees the collector unchanged). Both build and hash the collector through one function, `services/CollectorResources`, which reads the telemetry redirect and CNI from cluster state — `deploy()` takes no redirect argument, so the two paths cannot stamp different hashes, and `GrafanaDashboardService` to Grafana with the `grafana-datasources` contents, so a workload rolls only when its configuration changed. A workload that reads a ConfigMap whose contents the caller did not pass is refused (`IllegalArgumentException` naming both), since its hash could never change. Before applying, `services/ConfigChangeReport` compares each workload's hash with the one running (`K8sService.workloadConfigHashes`) and emits `Event.Grafana.WorkloadConfigCompared(workload, changed)`, so `up` and `grafana update-config` say which workloads roll.
- **`cluster-config`** ConfigMap keys (built by `services/ClusterConfigData`, shared by the stack deploy and the collector resync so both hash it identically): `control_node_ip`, `aws_region`, `s3_bucket` (account bucket), `traces_s3_prefix`, `metrics_s3_prefix`, `logs_s3_prefix`, `cluster_name`, `tenant`. It is the one source of the tenant for every in-cluster producer and backend: the collector, Alloy and Loki read it as the env var `TENANT` (`clusterConfigEnv()` builds such an env var). Producers outside Kubernetes (JFR shipper, Java agents, EMR, Trino/Presto start scripts) keep their own configuration path.

## Pyroscope Subpackage (`pyroscope/`)

All Pyroscope K8s resources are built programmatically using Fabric8:

- **`PyroscopeManifestBuilder`** — builds all Pyroscope K8s resources (server ConfigMap, Service, Deployment, eBPF ServiceAccount, eBPF ClusterRole, eBPF ClusterRoleBinding, eBPF ConfigMap, eBPF DaemonSet) as typed Fabric8 objects. The server (2.3.1) runs on the control plane with native multi-tenancy and pure v2 storage (`architecture_storage: v2`) in the **account bucket** under `observability/profiles`. Config values (`__ACCOUNT_BUCKET__`, `__AWS_REGION__`, `__PROFILES_S3_PREFIX__`) are substituted at build time via TemplateService — NOT runtime env var expansion. Nothing is deleted by age: `metastore.index.cleanup_interval: 0s` and `limits.retention_period: 0s`; v2 compaction stays on. The v2 metastore index (the only record of the cluster's blocks) and Raft state live on the hostPath `/mnt/db1/pyroscope`, mounted at `/data`. S3 auth uses the default credential chain (IMDS/instance role). The Alloy eBPF agent sends the tenant in `X-Scope-OrgID` (env `TENANT`, read from the `cluster-config` ConfigMap). The eBPF DaemonSet runs under the `pyroscope-ebpf` ServiceAccount (RBAC granting pod read access) so the Alloy `discovery.kubernetes` component can attribute samples to a pod/container/service_name.
- **Config resources** — `config.yaml` (Pyroscope server config with S3 backend, `__KEY__` placeholders) and `config.alloy` (Grafana Alloy eBPF config) stored in `resources/.../configuration/pyroscope/`.

### Profiling Architecture

Two capture mechanisms coexist. **Cassandra does NOT use the Pyroscope Java agent** — it attaches
async-profiler to the running JVM at runtime. Everything else still uses the agent.

1. **Runtime async-profiler (Cassandra)** — No agent, nothing in `JVM_OPTS`. `edl-profiling-reconcile`
   (`packer/cassandra/bin/`), driven by `edl-profiling-reconcile.timer` every 60s as the `cassandra`
   user, reads desired state from `/etc/easy-db-lab/profiling.json`, attaches `asprof` to the live
   JVM, writes rotating JFR chunks to `/mnt/db1/cassandra/profiles`, POSTs completed chunks to
   Pyroscope `/ingest`, and prunes by age and by total bytes. Controlled by the
   `cassandra profile` command group (`commands/cassandra/profiler/`) via
   `CassandraProfilingService`. Changing the event set needs no Cassandra restart.

   A shipped chunk is renamed with a `-shipped.jfr` **infix**, never a `.jfr.shipped` suffix: `fetch`
   and `flamegraph` list the node with `ls *.jfr` and feed the results to `jfrconv`, so a suffix
   would hide every already-uploaded chunk from the CLI. The shipping queue is `*.jfr` minus
   `*-shipped.jfr`.

   Its `edl_jfr_*` counters reach Mimir by being **pushed as OTLP** to the node-local
   collector (`localhost:4318/v1/metrics`, a hostNetwork DaemonSet, existing `metrics/otlp`
   pipeline). The Prometheus textfile it also writes is a convenience for someone on the node — no
   node_exporter and no textfile collector exists, so nothing reads it. Do not "simplify" the push
   away in favour of the file.
2. **Grafana Alloy eBPF DaemonSet** (all nodes) — `pyroscope.ebpf` component collects `process_cpu`
   profiles only (eBPF limitation). Image: `grafana/alloy:v1.20.0`. Sends the tenant in `X-Scope-OrgID`. Labels: `hostname`, `cluster`
   from env vars, plus per-pod `namespace`, `pod`, `container`, and `service_name` (derived as
   `namespace/container`) for processes that run in K8s pods. Attribution works by discovering
   node-local pods via `discovery.kubernetes` and joining them to host processes by container id in
   `discovery.process` (see `config.alloy`); host processes with no pod are still profiled, just
   without pod labels. Also profiles ClickHouse and TiDB/TiKV/PD (CPU only, since they're C++/Go).
3. **Pyroscope Java Agent (Stress Jobs)** — `/usr/local/pyroscope/pyroscope.jar` (v2.3.0, installed
   by packer) mounted into stress K8s Jobs via hostPath volume. Configured via `JAVA_TOOL_OPTIONS`
   in `StressJobService.buildJob()`. Collects `cpu`, `alloc`, `lock`. **This is why
   `install_pyroscope_agent.sh` must stay** even though Cassandra no longer uses the jar.
4. **Pyroscope Java Agent (Spark/EMR)** — `/opt/pyroscope/pyroscope.jar`, i.e.
   `Constants.PyroscopeJavaAgent.EMR_INSTALL_PATH` (installed by EMR bootstrap action). Added to
   Spark driver and executor via `extraJavaOptions`. Service name: `spark-<job-name>`. Note the two
   install paths differ — EMR uses `/opt/pyroscope`, nodes use `/usr/local/pyroscope` — which is why
   the constant is named for EMR.
5. **Pyroscope Java Agent (Trino/Presto, Cassandra Sidecar)** — injected via `JAVA_TOOL_OPTIONS`,
   governed by the `trino` and `sidecar-otel` specs. Out of scope of the Cassandra change.

**Never add a combined cpu+wall mode.** `jfr-parser` v0.18.0 `pprof/parser.go:56` reuses one sample
value buffer across event types, so after the first wall sample every CPU sample carries the wall
event's batch count as its weight — silent corruption up to three orders of magnitude. `--nobatch`
makes it worse, not better, and is rejected by `AsprofArgValidator` — async-profiler itself refused
the flag until 4.5 accepted it. The full explanation lives on the reserved-set KDoc in
`profiling/AsprofArgValidator.kt` and at the head of `edl-profiling-reconcile`.

### Activation Flow

1. `SetupInstance` seeds `/etc/easy-db-lab/profiling.json` on each Cassandra node with
   `enabled: true` and `["-e", "cpu"]`, plus the Pyroscope URL and cluster name — so a fresh cluster
   profiles CPU with no operator action. It no longer writes `/etc/default/cassandra`.
2. `setup_instance.sh` creates `/mnt/db1/cassandra/profiles` and enables
   `edl-profiling-reconcile.timer` (guarded on the unit existing, so it is a no-op off Cassandra
   nodes).
3. `GrafanaUpdateConfig` deploys Pyroscope server to K8s (control plane, port 4040, hostNetwork).
4. The reconciler attaches within one interval and starts shipping chunks. Nothing happens at
   Cassandra JVM startup.
5. When a stress job starts, `StressJobService` mounts the agent JAR and sets `JAVA_TOOL_OPTIONS`
   with all Pyroscope properties.

See [`docs/user-guide/profiling.md`](../../../../../../docs/user-guide/profiling.md) for the
operator-facing guide, including the reserved-parameter set and the cpu+wall hazard.

## Sidecar Subpackage (`sidecar/`)

- **`SidecarManifestBuilder`** — builds the Cassandra sidecar ConfigMap (config template) + DaemonSet. Runs on all `type=db` nodes with `hostNetwork: true`. An init container (busybox) substitutes `__HOST_IP__` from the Kubernetes Downward API (`status.hostIP`) into the config template at pod startup. Pyroscope Java agent injected via `JAVA_TOOL_OPTIONS` using host-mounted jar at `/usr/local/pyroscope`. Takes `image`, `controlNodeIp`, `clusterName`, and `tenant` as `buildAllResources()` parameters; the agent sends the tenant as `-Dpyroscope.tenant.id`.
- **Config resource** — `cassandra-sidecar.yaml` stored in `resources/.../configuration/sidecar/`. Uses `__HOST_IP__` placeholder for both `cassandra_instances[0].host` and `driver_parameters.contact_points`.

## Beyla Subpackage (`beyla/`)

- **`BeylaManifestBuilder`** — builds Beyla eBPF auto-instrumentation ConfigMap + DaemonSet. Runs on all nodes with hostNetwork/hostPID/privileged for eBPF access.
- **Config resource** — `beyla-config.yaml` stored in `resources/.../configuration/beyla/`. It sets `javaagent.enabled: false`: Beyla otherwise attaches its Java agent to every JVM it instruments, including the database under test. `BeylaManifestBuilderTest` guards it.

## OTel Subpackage (`otel/`)

- **`OtelManifestBuilder`** — builds the main OTel Collector ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, and DaemonSet. Runs on all nodes, collects host metrics, Prometheus scrapes, file-based logs (system, Cassandra, ClickHouse), and OTLP. Uses `k8s_attributes` processor to derive `node_role` from K8s node label `type` (db, app, control). RBAC grants read-only access to pods and nodes. Config uses OTel runtime env expansion (`${env:HOSTNAME}`), not `__KEY__` templates.
  - **Local backend scrape jobs** (`buildLocalBackendScrapeJobs(telemetryRedirect)`, rendered into `__INFRA_SCRAPE_JOBS__` ahead of the infra jobs): `tempo` (port 3200) and `pyroscope` (port 4040), both single pods on the control node. They use `buildNodeLocalPodScrapeJob` (pod SD in `default` on `app.kubernetes.io/name`, like kube-state-metrics), not a static `localhost` target: the collector is a DaemonSet, and a static target made every other node's collector report both jobs `up = 0` permanently. Only the collector on the pod's node scrapes it; `instance` is the pod name. Local mode only — a redirect cluster deploys neither server, so neither job is rendered.
  - **Infrastructure scrape jobs built in Kotlin** (`buildInfraScrapeJobs(cni)`, rendered into `__INFRA_SCRAPE_JOBS__`): the kube-state-metrics job on every cluster, and on `CniMode.Cilium` only the `cilium-agent` (`localhost:9962`), `hubble` (`localhost:9965`), and `cilium-operator` (pod SD in `kube-system` on `io.cilium/app=operator`, port 9963) jobs (`buildCniScrapeJobs`). These live in Kotlin, not in `otel-collector-config.yaml`, because their relabel rules name `__meta_kubernetes_*`/`__address__` — a bare `__x__` in the template is a `TemplateService` placeholder and derails every substitution after it. Singleton pods (kube-state-metrics, the operator) use `buildNodeLocalPodScrapeJob`: pod SD filtered to the collector's own node and the metrics port, so one collector scrapes them and `instance` is the pod name. `buildConfigMap`/`buildAllResources` take the `CniMode`; `ObservabilityStackService` and `OtelSyncService` read it from cluster state. Cilium is the default CNI (`InitConfig.cni`, `init --cni`); `--cni=flannel` selects Flannel, and a saved state that records no CNI is read as Flannel.
  - **Per-workload scrape jobs** come from the metrics registry as `WorkloadScrapeConfig`s. A config produces either a **static NodePort** job (`localhost:<port>`, every collector scrapes it, `instance` = collector hostname) or, when it carries a `podSelector`, a **pod service-discovery** job (`kubernetes_sd_configs`, role: pod). Pod-SD scrapes each matching pod directly on its pod IP + container port, filtered to pods on the collector's own node (`__meta_kubernetes_pod_node_name == ${env:HOSTNAME}`) so the DaemonSet doesn't produce duplicate series — `instance` becomes the pod name. Every built-in kit uses pod-SD: a static job runs on every collector, so a NodePort target is duplicated once per node and a hostPort target reports down on every other node (`NodePortKitScrapeTest` / `HostPortKitScrapeTest`). The kit declares `pod-selector` on its `scrape` metrics entry (`${KIT_NAME}` is filled in with the instance name at `start`) and sets `port` to the container port. See `buildPodScrapeJob`.
- **`JournaldOtelManifestBuilder`** — builds a Fluent Bit DaemonSet (`fluent-bit-journald`) for systemd journal collection, isolated from the main OTel collector. Fluent Bit has a native systemd input plugin with journalctl compiled in, so no external binary is needed. Reads journal files directly from `/var/log/journal` (mounted read-only). Uses a `modify` filter to rename `HOSTNAME` → `host.name` and add `source: journald` for unified search with other log sources. Maps `MESSAGE` to OTLP body (the Loki line) and `PRIORITY` to OTLP severity; the collector's `transform/source_to_resource` lifts `source` onto the resource so Loki indexes it. Remaining journald fields become OTLP attributes. Forwards logs via OTLP HTTP to the main collector on `localhost:4318`. Health check on port 2020. Security context: `runAsUser: 0` with `DAC_READ_SEARCH` capability. Image: `fluent/fluent-bit:5.1.2`.
- **Config resources** — `otel-collector-config.yaml` (main collector) and `fluent-bit-journald.yaml` (journald collector) stored in `resources/.../configuration/otel/`.

## ebpf_exporter Subpackage (`ebpfexporter/`)

- **`EbpfExporterManifestBuilder`** — builds ebpf_exporter DaemonSet (no ConfigMap). Runs on all nodes with hostNetwork/hostPID/privileged. Uses built-in example programs (`biolatency`, `xfsdist`, `cachestat`) from the container image. No TemplateService needed. Available examples: https://github.com/cloudflare/ebpf_exporter/tree/master/examples

## Mimir Subpackage (`mimir/`)

- **`MimirManifestBuilder`** — builds Mimir 3.2.1's ConfigMap + Service + Deployment on the control node (hostNetwork, HTTP 9009, gRPC 9097, gossip on loopback 7947 — Pyroscope holds 7946). Monolithic target `distributor,ingester,querier,query-frontend,query-scheduler`: **no compactor and no store-gateway**, so nothing in a cluster compacts or deletes metrics, and the querier never reads the S3 block store (`querier.query_store_after: 87600h`, `limits.query_ingesters_within: 0`, local blocks kept `retention_period: 87601h`). The ingester ships each 2h block to `observabilitymetrics/<tenant>/` within about a minute (`ship_interval: 1m`) and `flush_blocks_on_shutdown`. The TSDB (head, WAL, local blocks) is on the hostPath `/mnt/db1/mimir` (mounted at `/data`); grace period 600s. Multi-tenancy and tenant federation (`a|b`) are on; ingestion and label limits are raised, `max_global_series_per_user: 0`, `out_of_order_time_window: 10m`. `mimir.yaml` is expanded from env (`-config.expand-env=true`) fed from `cluster-config`; never write a dollar-brace in a comment there, the expansion reads comments too. `MimirIntegrationTest` runs the pinned image against LocalStack.

## Loki Subpackage (`loki/`)

- **`LokiManifestBuilder`** — builds Loki 3.7.8's ConfigMap + Service + Deployment on the control node (hostNetwork, HTTP 3100, gRPC 9098). A single binary (`target: all` — the only single-process target that both writes and reads the S3 index) with the compactor idle: `compaction_interval: 87600h`, `retention_enabled: false`, `retention_period: 0s`, `deletion_mode: disabled` (the delete API is not served). `object_prefix: observability/logs`; tsdb v13 daily tables with the fixed `schema_config` (a permanent contract of the shared store: only append periods). The ingester is named `${TENANT}.${CLUSTER_NAME}`, so every index file names its tenant and cluster. OTLP index labels `cluster`, `host.name`, `node_role`, `source`; everything else is structured metadata. WAL (`flush_on_shutdown`), index head, index cache and compactor directory are on the hostPath `/mnt/db1/loki` (mounted at `/loki`, owned by uid 10001); grace period 600s. `multi_tenant_queries_enabled` allows `a|b`; `reject_old_samples_max_age: 8760h` accepts backdated lines. `LokiIntegrationTest` runs the pinned image against LocalStack.

## Tempo Subpackage (`tempo/`)

- **`TempoManifestBuilder`** — builds Tempo ConfigMap + Service + Deployment. Tempo 3.0.3 runs on the control plane with native multi-tenancy and an S3 backend in the account bucket under `observability/traces` (Tempo makes the tenant directories). Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`, `${TRACES_S3_PREFIX}`), fed from the `cluster-config` ConfigMap. Compaction — which runs retention in Tempo 3 — is disabled for every tenant (`overrides.defaults.compaction.compaction_disabled: true`), and no block retention is set: no block is ever deleted. The live store cuts a block every 5m. No ingestion limit discards spans: live traces are unlimited (`max_traces_per_user: 0`, `live_store.max_live_traces_bytes: 0`), there is no per-trace size limit, and the rate limit is 1 GB/s. No attribute value is truncated: `distributor.max_attribute_bytes: 0` (default 2048; a per-tenant override of 0 falls back to the distributor value, so only the distributor setting turns truncation off). Both WAL paths live on the hostPath `/mnt/db1/tempo` (mounted at `/var/tempo`), so a pod restart keeps received spans; `TempoBlockDurabilityIntegrationTest` proves it against LocalStack. Tempo acknowledges a push while the spans are only in memory. `live_store.flush_check_period: 1s`, `max_trace_idle: 1s` and `max_trace_live: 10s` (defaults 5s/5s/30s) put a trace in the WAL about 2s after its last span, so a killed Tempo loses at most those ~2s. The window cannot close in single-binary mode. A graceful restart loses nothing. The WAL is not fsynced, so a node crash can lose WAL data that was not yet on disk. `commit_interval` applies only to Kafka and is left unset.
- **Config resource** — `tempo.yaml` stored in `resources/.../configuration/tempo/`.

## Registry Subpackage (`registry/`)

- **`RegistryManifestBuilder`** — builds Docker Registry Deployment. Runs on control plane with TLS cert hostPath mount and HTTPS probes. No TemplateService needed.

## S3 Manager Subpackage (`s3manager/`)

- **`S3ManagerManifestBuilder`** — builds S3 Manager Deployment. Runs on control plane with IAM-based auth. No TemplateService needed.

## kube-state-metrics Subpackage (`kubestatemetrics/`)

- **`KubeStateMetricsManifestBuilder`** — builds kube-state-metrics ServiceAccount, ClusterRole, ClusterRoleBinding, Service (ClusterIP :8080), and Deployment. One replica on the control node in the **pod network** (not hostNetwork, no hostPort). RBAC is read-only: every rule grants only `list` and `watch` (`READ_ONLY_VERBS`), over the upstream default resource set (`WATCHED_RESOURCES`). Image pinned in `Constants.KubeStateMetrics.IMAGE`. Deployed by `ObservabilityStackService` on every cluster, in both telemetry modes. No ConfigMap/TemplateService needed. Scraped by the OTel collector through node-local pod discovery (see the OTel subpackage), not through the Service, so a DaemonSet of collectors yields one series set.

## YACE Subpackage (`yace/`)

- **`YaceManifestBuilder`** — builds YACE (Yet Another CloudWatch Exporter) ConfigMap + Deployment. Runs on control plane, scrapes AWS CloudWatch metrics for S3, EBS, EC2, and OpenSearch services. Exposes Prometheus metrics on port 5001, scraped by OTel collector. (EMR metrics removed — replaced by direct OTel collection on Spark nodes.)
- **Config resource** — `yace-config.yaml` stored in `resources/.../configuration/yace/` with `__AWS_REGION__` template variable for region substitution.
- **Auto-discovery** — uses tag-based auto-discovery with the `easy_cass_lab=1` tag to find cluster resources in CloudWatch.
