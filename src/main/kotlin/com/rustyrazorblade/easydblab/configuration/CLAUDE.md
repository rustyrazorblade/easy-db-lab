# Configuration Package

This package manages cluster state, user configuration, S3 paths, and template substitution.

## CRITICAL: Testing Requirements for K8s Manifest Builders

**All manifest builders under `configuration/` MUST be tested with K3s TestContainers.** This is not optional.

### Required Tests (in `K8sServiceIntegrationTest`)

Every manifest builder must have:

1. **Apply test** — `buildAllResources()` applied via `serverSideApply()` to a real K3s cluster. Verify each resource (ConfigMap, Deployment, DaemonSet, Service) exists after apply.
2. **Image pull test** — the builder's container images are pulled with `crictl pull` inside the K3s container. This catches wrong image names, wrong registries, and removed tags. Only the account compactor's images have one today (`every account compactor image can be pulled`); add one when you add or change a builder.
3. **No database resource limits test** — every object rendered from a built-in `type: db` kit is verified to set no CPU or memory `requests` or `limits` anywhere in its tree (operator custom resources included; `storage` is a claim size and allowed). A database must use all of its node's hardware.

### Rules for Manifest Builders

- **NEVER set resource limits or requests on a database pod.** A database must use all of its node's hardware: never, for example, an 8 GB limit on a database whose node has 16 GB. This covers every database workload a kit runs (pods, StatefulSets and the operator custom resources that create them). Observability pods and other control-node pods may have requests and limits, and need them, because several share the control node. Size each from what it was measured to use and state the measurement in the builder's KDoc, as `TempoManifestBuilder.MEMORY_REQUEST`/`MEMORY_LIMIT` do (issue 1002). A limit is never a substitute for a component's own flow control: Tempo's GOMEMLIMIT and live-trace cap keep it under its limit, and the node collector's byte-bounded queues keep it under its own.
- **NEVER mock `TemplateService`** — always use the real instance. It only reads classpath resources.
- **NEVER mock manifest builder classes** — always use real instances in tests.
- **Always use the correct container registry** — e.g., `ghcr.io/cloudflare/ebpf_exporter` not `cloudflare/ebpf_exporter`. The image pull test catches this.
- When adding a new builder, you MUST add it to `K8sServiceIntegrationTest.collectAllResources()` and add a dedicated apply test.
- **Place pods through `PodPlacement.kt`.** A control-plane Deployment is `controlPlaneDeployment(name, namespace) { ... }` (name labels, selector, `Recreate`, control-plane node selector and toleration); a pod on the node's network calls `hostNetworked()` (`hostNetwork` plus `ClusterFirstWithHostNet`). Never write those lines in a builder. Any fixed port of 1024 or above that a host-network pod binds (outside the NodePort range, which is reserved whole) must also be in `net.ipv4.ip_local_reserved_ports` in `commands/node-tuning/60-easydblab.conf`, since the node's ephemeral range is 1024-65535; `NodeTuningReservedPortsTest` fails until it is. A host service no builder names (K3s, kubelet, Cilium, the database, Tailscale) goes in `Constants.NodeTuning.HOST_SERVICE_PORTS`, and a new builder that calls `hostNetworked()` must be added to the test's `workloads()`. Inside the `controlPlaneDeployment` block `this` is a `PodSpecBuilder`, so a call such as `buildVolumes()` resolves to the fluent builder's own method: compute values from the enclosing class before the block.
- `ManifestGoldenTest` pins the rendered YAML of every workload builder against `src/test/resources/golden/manifests/`. Regenerate with `UPDATE_GOLDEN=1 ./gradlew :test --tests '*ManifestGoldenTest'` only for an intended manifest change.

### Test Location

`src/integrationTest/kotlin/.../services/K8sServiceIntegrationTest.kt` — single K3s container shared across all tests (`@TestInstance(PER_CLASS)`). This test lives in the slow `integrationTest` source set (Docker-only), not `src/test/`; run it with `./gradlew integrationTest` (or `check`).

## Core State Management

### ClusterState (`ClusterState.kt`)

Central state data class persisted as `state.json`. Key fields:

- `name: String` — cluster name
- `clusterId: String` — unique UUID for EC2 tag-based discovery
- `hosts: Map<ServerType, List<ClusterHost>>` — all cluster hosts
- `nodes: MutableMap<Alias, NodeState>` and `default: NodeState` — the Cassandra version record. Each `NodeState` holds the active `version` and its `javaVersion`, and `installed`: one `InstalledVersion` per version on that node (JDK, supported JDKs, Python, `CassandraSource`, the URL with credentials redacted, and for a git build the branch and ant flags). No version file lives on a node; this is the only record. `CassandraNodeRestorer` reinstalls a replaced node's active version from it
- `infrastructure: InfrastructureState?` — VPC, subnet, security group IDs
- `initConfig: InitConfig?` — configuration from the `Init` command
- `emrCluster: EMRClusterState?` — optional EMR/Spark state
- `openSearchDomain: OpenSearchClusterState?` — optional OpenSearch state
- `s3Bucket: String?` — account-level S3 bucket
- `dataBucket: String` — per-cluster data bucket (`easy-db-lab-data-{clusterId}`) for ClickHouse data and CloudWatch metrics
- `backupHashes: Map<String, String>` — SHA-256 hashes of backed-up files
- `infrastructureStatus: InfrastructureStatus` — UP, DOWN, or UNKNOWN
- `networkMode: NetworkMode` — how this machine reaches the cluster: `socks` (the default), `tailscale` or `direct`, serialized as that id. Tunnel decisions use `networkMode.needsTunnel` (true only for `socks`); Tailscale-only steps compare with `NetworkMode.Tailscale`. `init` records it from the profile's Tailscale credentials and `--no-tailscale`. It replaced the `tailscaleActive` boolean; `ClusterStateManager.load()` reads an old state's `tailscaleActive` as the mode and fails on an unknown mode
- `tailFlush: TailFlushRecord?` — the signals a `down` already saved, as `TailSignal` → `SavedSignal(completedAt)`. Only `LOGS` and `METRICS` are ever recorded, each the moment its flush succeeds (their flushes stop Loki and Mimir and cannot run twice); a re-run skips them. `saveCompletedAt` is set once a save of every signal succeeded, right before the infrastructure teardown; a re-run (after a teardown that failed part-way) then skips the whole save and its SOCKS tunnel. `markInfrastructureUp()` clears it.

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

`ServerType.ordinalOf(alias): Int?` is the one parser of an alias's ordinal (`db12` is 12; another type's alias or a non-number suffix is null; each caller picks its own fallback). `InitConfig.nodeGroups()` (`NodeGroup.kt`) is the one mapping from the init fields to each group's instance type, count and architecture, read by the capacity plan, the AMI resolution and the instance specs.

### ClusterStateManager (`ClusterStateManager.kt`)

Handles persistence to `state.json`. Several commands can run in one workspace at once (issue 986), so no command writes back a whole copy it loaded earlier:
- `load(): ClusterState` — read from file; it rewrites the JSON tree first for older states (an `initConfig` with no `cni` reads as Flannel, and `tailscaleActive` becomes `networkMode`), and writes nothing. Takes no lock; every write is a rename, so a reader never sees a torn file
- `update { change }` — the only way to change existing state. Under an exclusive lock on `state.json.lock` (`Constants.ClusterStateFile`: 30s timeout, 20ms retry, through resilience4j) it reads the file again, applies `change` to that fresh copy, writes a temp file with the state file's permissions and renames it over `state.json`. A change another command saved after this one loaded is kept. A long command (`spark init`, `up`) holds the lock only for this, never while it waits. Threads of one JVM also take turns on an in-process lock first (a JVM cannot hold two locks on one file); a nested `update` fails at once
- `update(local) { change }` (extension) — applies `change` on disk and then to the caller's own copy, so the code after it sees its change. The change runs twice: it must set values, not step them
- `replace(state)` — writes a whole new state, under the same lock. Only `init` and a restore that found no `state.json` use it
- `exists(): Boolean` — check if state file exists
- `incrementStressJobCounter()`, `addRunningWorkload()`, `removeRunningWorkload()` — `update`s
- Failures: a lock timeout or an unparseable file is an `IOException` naming the file, and nothing is written; an `update` with no `state.json` is an `IllegalStateException` telling the user to run `init`
- In tests, use a real `ClusterStateManager` over `@TempDir`. A mocked manager's `update` applies no change

`ClusterState` helpers such as `updateHosts()`, `updateEmrCluster()`, `updateInfrastructure()`, `markInfrastructureUp()` and `markInfrastructureDown()` only change the object; call them inside `update`.

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
- `ACCOUNT_BUCKET` (the account bucket; the observability stack never writes to the data bucket), `AWS_REGION`, `CLUSTER_ID` (the UUID `up` tags every instance, volume and OpenSearch domain with as `ClusterId`), `CLUSTER_NAME`, `CONTROL_NODE_IP`, `TENANT` (the cluster's observability tenant), `PROFILES_S3_PREFIX` (`pyroscope`)
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

- **`PodNetworkSysctlPolicyBuilder`** — the one cluster-wide `MutatingAdmissionPolicy` (`admissionregistration.k8s.io/v1beta1`) and its binding, `easydblab-pod-network-sysctls`, that give every non-hostNetwork pod the pod network sysctls (`services/PodNetworkSysctls`, read from `60-easydblab.conf`). A CEL JSONPatch adds a missing `securityContext`, a missing `sysctls` list, and each key the pod does not set itself; `failurePolicy: Fail`. It builds no container, so it is not in `K8sServiceIntegrationTest.collectAllResources()`; its apply test is `PodNetworkTuningIntegrationTest`, which runs its own K3s with the two drop-ins the policy needs (the API server flags from `start-k3s-server.sh`, the kubelet allowlist from `services/KubeletSysctlsDropIn`). The shared K3s has neither, and a cluster-wide policy there would change every other test's pods. Move it to `v1` when the AMI reaches K3s 1.36.

- Cassandra versions are not configuration: `services/cassandraversions/` resolves a name from its sources (profile builds, ASF, cassandra-builds, the build catalog) at runtime, and the record lives in `ClusterState.nodes`.
- **`CassandraYaml`** — cassandra.yaml manipulation (Jackson-based)
- **`Seeds`** — seed list management
- **`Host`** — legacy host data class (use `ClusterHost` for new code)
- **`Arch`** — CPU architecture enum (`AMD64`, `ARM64`)
- **`ClusterConfigWriter`** — generates SSH config and environment.sh files

## Grafana Subpackage (`grafana/`)

All Grafana K8s resources are built programmatically using Fabric8:

- **`GrafanaDashboard`**, **`GrafanaDashboardCatalog`**, **`GrafanaDashboardTreeWriter`**, **`GrafanaDashboardProvisioningConfig`** — the core dashboard tree, discovered from the classpath and copied to the control node as files; the mechanism is described once, in [`dashboards/CLAUDE.md`](../../../../../../dashboards/CLAUDE.md).
- **`DashboardDefaults`** — the one install-time pass every dashboard goes through (core tree, kit `start`, `grafana install`): a pure transform on the dashboard `JsonObject` that sets each picker's `current` to its stable datasource, `cluster`, `baseline_cluster` and `candidate_cluster` to the current `<name>-<id>`, fills `doc_tenant`'s options from the tenant list with the home tenant selected, and replaces `__DOCUMENTS_URL__`. It changes no query. `DashboardInstallContext` carries what it needs; `services/DashboardInstallContextFactory` builds it from the cluster state and `TenantDirectory`. `GrafanaDashboardTreeWriter` applies it after the `__PYROSCOPE_URL__` substitution and writes compact JSON (`JsonElement.toString`, which keeps every number as written).
- **`GrafanaManifestBuilder`** — builds the Grafana K8s resources (provisioning ConfigMap, documents web server ConfigMap and Deployment) as typed Fabric8 objects; nothing it builds varies with the dashboard tree. `buildAllResources(DocumentsBucket)` takes the account bucket and its region. Uses `TemplateService` only for the cluster name. The Deployment mounts the `/mnt/db1/grafana` hostPath at `/var/lib/grafana`, which is how the copied tree reaches the pod, and includes a `grafana-image-renderer` sidecar container (port 8081) for server-side panel rendering. Grafana runs with `GF_PANELS_DISABLE_SANITIZE_HTML=true` (`Constants.Grafana.DISABLE_SANITIZE_HTML_ENV`; Grafana reads the setting from `[panels]`), so a Text panel can hold the documents iframe.
- **`GrafanaDocumentsSidecars`** — the two containers that serve the test documents to the browser: `documents-sigv4-proxy` (`aws-sigv4-proxy`, bound to `127.0.0.1:3081`, signs S3 requests with the instance role for the bucket's region from `services/aws/BucketRegion`) and `documents-web` (nginx, host port 3080). The web server's configuration, `documents-nginx.conf` rendered by `TemplateService`, forwards only `GET` for normalized paths under `/reports/` to the proxy, with the bucket fixed and the query string dropped.  It answers 405 for any other method and 400 for a decoded path that does not match `^/reports/[A-Za-z0-9._/-]+$`, so an encoded line break or `?` cannot reach S3. It ships in the `grafana-documents-web` ConfigMap, which is among the resources `ConfigHashAnnotator` hashes, so a change rolls Grafana. Ports and pinned images are in `Constants.Grafana.Documents`. `DocumentsWebServerIntegrationTest` runs the real configuration in K3s.
- **`GrafanaDatasourceConfig`** — the datasource provisioning YAML model. **`GrafanaDatasourceSet.build(TenantSet, BackendUrls)`** is the one pure function that builds it: the stable `mimir`/`loki`/`tempo`/`pyroscope` datasources on the cluster's own tenant, `<signal>-<tenant>` per tenant and `<signal>--all` (tenants joined with `|`) for metrics, logs and traces, UIDs over 40 characters shortened to `<signal>-<prefix>-<8 hex of sha256>`, cross links within one tenant view, one profiles datasource. Only the URLs depend on `BackendUrls` (`CONTROL_NODE` on a cluster). The tenants come from `services/TenantDirectory` (one delimited listing of `mimir/`), which `ObservabilityStackService.deploy()` calls.
- **Dashboard JSON files** — stored in the top-level `dashboards/<folder>/` tree at the project root. `processResources` in `build.gradle.kts` copies the tree onto the classpath under a `dashboards/` prefix at build time. Also published as a standalone zip via GitHub Actions for consumption by other projects.

## Observability Store (`ObservabilityStore.kt`, `ConfigHashAnnotator.kt`)

- **`ObservabilityStore`** — the one place that builds the observability paths from the account bucket and the cluster's tenant. The backend prefixes carry no tenant, because each backend makes its own tenant directories: each is its tool's root at the top level of the account bucket (`Constants.Observability.*_ROOT`): `metricsPrefix()` (`mimir`), `logsPrefix()` (`loki`, index under `loki/index/`), `tracesPrefix()` (`tempo`), `profilesPrefix()` (`pyroscope`). `annotationsRoot()` is `grafana/annotations/<tenant>`, and `annotationsArtifact()` adds a `SnapshotName`. `documentsRoot(cluster)` is a test's document folder, `reports/<tenant>/<name>-<id>`, and `document(cluster, name)` one file in it (`Constants.Observability.REPORTS_ROOT`); `services/documents/DocumentService` writes there. No metrics or logs snapshot exists: Mimir and Loki write to S3 themselves.
- **`SnapshotName`** — `<yyyyMMdd-HHmmss>_<name>-<clusterId>`, the name of an annotations backup. `SnapshotName.firstFree` moves a name past every second whose S3 key already exists, so two backups of one cluster in the same second never overwrite each other; `GrafanaAnnotationBackupService` names every backup through it.
- **`ConfigHashAnnotator`** — stamps each Deployment/DaemonSet/StatefulSet pod template with `easydblab.com/config-hash`, a SHA-256 of the rendered pod template (serialized with object keys sorted, the hash annotation left out) and of the ConfigMaps it reads (volumes, `configMapKeyRef`, `envFrom`), including ConfigMaps built elsewhere such as `cluster-config`. Hashing the template makes the hash change exactly when Kubernetes rolls the workload, so an image, probe, env or volume change is reported as a change; both sides of the comparison are hashes of what easy-db-lab rendered, never of the server-defaulted object. `ObservabilityStackService` applies it to every stage, and `OtelSyncService` re-applies the collector when a kit start/stop regenerates its ConfigMap (no forced rollout-restart, so the template hash tracks the ConfigMap and the next deploy sees the collector unchanged). Both build and hash the collector through one function, `services/CollectorResources`, which reads the telemetry redirect and CNI from cluster state — `deploy()` takes no redirect argument, so the two paths cannot stamp different hashes, and `GrafanaDeployService` to Grafana with the `grafana-datasources` contents, so a workload rolls only when its configuration changed. A workload that reads a ConfigMap whose contents the caller did not pass is refused (`IllegalArgumentException` naming both), since its hash could never change. Before applying, `services/ConfigChangeReport` compares each workload's hash with the one running (`K8sService.workloadConfigHashes`) and emits `Event.Grafana.WorkloadConfigCompared(workload, changed)`, so `up` and `grafana update-config` say which workloads roll.
- **`cluster-config`** ConfigMap keys (built by `services/ClusterConfigData`, shared by the stack deploy and the collector resync so both hash it identically): `control_node_ip`, `aws_region`, `s3_bucket` (account bucket), `traces_s3_prefix` (`tempo`), `metrics_s3_prefix` (`mimir`), `logs_s3_prefix` (`loki`), `cluster_name`, `tenant`. It is the one source of the tenant for every in-cluster producer and backend: the collector, Alloy and Loki read it as the env var `TENANT` (`clusterConfigEnv()` builds such an env var). Producers outside Kubernetes (JFR shipper, Java agents, EMR, Trino/Presto start scripts) keep their own configuration path; the Trino and Presto start scripts read only `cluster_name` from it, for their Pyroscope `cluster` label.

## Pyroscope Subpackage (`pyroscope/`)

All Pyroscope K8s resources are built programmatically using Fabric8:

- **`PyroscopeManifestBuilder`** — builds all Pyroscope K8s resources (server ConfigMap, Service, Deployment, eBPF ServiceAccount, eBPF ClusterRole, eBPF ClusterRoleBinding, eBPF ConfigMap, eBPF DaemonSet) as typed Fabric8 objects. The server (2.3.1) runs on the control plane with native multi-tenancy and pure v2 storage (`architecture_storage: v2`) in the **account bucket** under `pyroscope`. Config values (`__ACCOUNT_BUCKET__`, `__AWS_REGION__`, `__PROFILES_S3_PREFIX__`) are substituted at build time via TemplateService — NOT runtime env var expansion. Nothing is deleted by age: `metastore.index.cleanup_interval: 0s` and `limits.retention_period: 0s`; v2 compaction stays on. The v2 metastore index (the only record of the cluster's blocks) and Raft state live on the hostPath `/mnt/db1/pyroscope`, mounted at `/data`. S3 auth uses the default credential chain (IMDS/instance role). The Alloy eBPF agent sends the tenant in `X-Scope-OrgID` (env `TENANT`, read from the `cluster-config` ConfigMap). The eBPF DaemonSet runs under the `pyroscope-ebpf` ServiceAccount (RBAC granting pod read access) so the Alloy `discovery.kubernetes` component can attribute samples to a pod/container/service_name.
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
   by packer) mounted into stress K8s Jobs via hostPath volume. Configured via `JDK_JAVA_OPTIONS`
   in `StressJobService.buildJob()`. Not `JAVA_TOOL_OPTIONS`: the stress image selects ZGC there, and a pod value would replace it. Collects `cpu`, `alloc`, `lock`. **This is why
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

1. `NodeSetupService` (each node's setup step in `up`, and `setup-instance`) seeds `/etc/easy-db-lab/profiling.json` on each Cassandra node with
   `enabled: true` and `["-e", "cpu"]`, plus the Pyroscope URL and cluster name — so a fresh cluster
   profiles CPU with no operator action. It no longer writes `/etc/default/cassandra`.
2. `setup_instance.sh` creates `/mnt/db1/cassandra/profiles` and enables
   `edl-profiling-reconcile.timer` (guarded on the unit existing, so it is a no-op off Cassandra
   nodes).
3. `GrafanaUpdateConfig` deploys Pyroscope server to K8s (control plane, port 4040, hostNetwork).
4. The reconciler attaches within one interval and starts shipping chunks. Nothing happens at
   Cassandra JVM startup.
5. When a stress job starts, `StressJobService` mounts the agent JAR and sets `JDK_JAVA_OPTIONS`
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

- **`OtelManifestBuilder`** — builds the main OTel Collector ServiceAccount, ClusterRole, ClusterRoleBinding, ConfigMap, and DaemonSet. Runs on all nodes, collects host metrics, Prometheus scrapes, file-based logs (system, Cassandra, ClickHouse), and OTLP. Host metrics and host logs (`metrics/local`, `logs/local`) take `node_role` from the host name with `transform/node_role_from_host` (`db<N>`, `app<N>`, `control<N>`; a set `node_role` is kept); `k8s_attributes`, which needs a pod, runs before that rule in `metrics/local` (the Prometheus receiver sets the pod of each pod-discovered kit target, and the kit dashboards read its `k8s_app_instance`) and on container logs. RBAC grants read-only access to pods and nodes. Config uses OTel runtime env expansion (`${env:HOSTNAME}`), not `__KEY__` templates. `metrics/otlp` runs `resource_detection`, which overwrites `host.name` with the node's (a JVM in a pod reports its pod name); `transform/keep_emr_host` and `transform/restore_emr_host` around it keep the `host.name` of resources with `node_role` `spark-master` or `spark-worker`, so EMR nodes do not arrive as `control0` (`CollectorToBackendsIntegrationTest`).
  - **Local backend scrape jobs** (`buildLocalBackendScrapeJobs(telemetryRedirect)`, rendered into `__INFRA_SCRAPE_JOBS__` ahead of the infra jobs): `tempo` (port 3200) and `pyroscope` (port 4040), both single pods on the control node. They use `buildNodeLocalPodScrapeJob` (pod SD in `default` on `app.kubernetes.io/name`, like kube-state-metrics), not a static `localhost` target: the collector is a DaemonSet, and a static target made every other node's collector report both jobs `up = 0` permanently. Only the collector on the pod's node scrapes it; `instance` is the pod name. Local mode only — a redirect cluster deploys neither server, so neither job is rendered.
  - **Infrastructure scrape jobs built in Kotlin** (`buildInfraScrapeJobs(cni)`, rendered into `__INFRA_SCRAPE_JOBS__`): the kube-state-metrics job and the `k3s-etcd` job on every cluster (`buildEtcdScrapeJob`: K3s's embedded etcd metrics on `localhost:2381` of the control node, enabled by `etcd-expose-metrics` in the `20-datastore.yaml` drop-in; node SD role `node` keeping only the collector's own node when it carries `node-role.kubernetes.io/etcd=true`, so one collector scrapes it and no other node reports it down), and on `CniMode.Cilium` only the `cilium-agent` (`localhost:9962`), `hubble` (`localhost:9965`), and `cilium-operator` (pod SD in `kube-system` on `io.cilium/app=operator`, port 9963) jobs (`buildCniScrapeJobs`). These live in Kotlin, not in `otel-collector-config.yaml`, because their relabel rules name `__meta_kubernetes_*`/`__address__` — a bare `__x__` in the template is a `TemplateService` placeholder and derails every substitution after it. Singleton pods (kube-state-metrics, the operator) use `buildNodeLocalPodScrapeJob`: pod SD filtered to the collector's own node and the metrics port, so one collector scrapes them and `instance` is the pod name. `buildConfigMap`/`buildAllResources` take the `CniMode`; `ObservabilityStackService` and `OtelSyncService` read it from cluster state. Cilium is the default CNI (`InitConfig.cni`, `init --cni`); `--cni=flannel` selects Flannel, and a saved state that records no CNI is read as Flannel.
  - **Per-workload scrape jobs** come from the metrics registry as `WorkloadScrapeConfig`s. A config produces either a **static NodePort** job (`localhost:<port>`, every collector scrapes it, `instance` = collector hostname) or, when it carries a `podSelector`, a **pod service-discovery** job (`kubernetes_sd_configs`, role: pod). Pod-SD scrapes each matching pod directly on its pod IP + container port, filtered to pods on the collector's own node (`__meta_kubernetes_pod_node_name == ${env:HOSTNAME}`) so the DaemonSet doesn't produce duplicate series — `instance` becomes the pod name. Every built-in kit uses pod-SD: a static job runs on every collector, so a NodePort target is duplicated once per node and a hostPort target reports down on every other node (`NodePortKitScrapeTest` / `HostPortKitScrapeTest`). The kit declares `pod-selector` on its `scrape` metrics entry (`${KIT_NAME}` is filled in with the instance name at `start`) and sets `port` to the container port. See `buildPodScrapeJob`.
  - **Backend outages** (issue 991): every backend exporter queues in memory and retries without a limit (`max_elapsed_time: 0`). Every queue is bounded by size (issue 1002): `otlp_http/logs` and `otlp_grpc/tempo` 256 MiB each with `sizer: bytes`, `prometheus_remote_write` 500 batches (its 0.161.0 factory fixes the requests sizer) with `batch.send_batch_max_size: 1024`, the EMR collector 256 MiB and the stress sidecar 64 MiB. The node collector DaemonSet sets `OtelManifestBuilder.MEMORY_REQUEST`/`MEMORY_LIMIT`/`GO_MEM_LIMIT` (512Mi/4Gi/3GiB); every queue full measured 3.2 GB RSS, and `TelemetryOutageConfigTest` fails if a queue grows past what was measured. `prometheus_remote_write` (0.161.0) has no `sending_queue` (its queue is `remote_write_queue`); it retries inside the push, which the top-level `timeout` (default 5s, now `0`) bounded retries included, so it dropped every batch while Mimir was down; it ignores `max_interval` (fixed `initial_interval: 5s`); `endpoint`, `tls` and `headers` sit under `http:` (`http.timeout: 30s`), because a set `http` makes the collector ignore the top-level ones. `otlp_http/logs` and `otlp_grpc/tempo` use `sending_queue`. The EMR collector (`otlp_grpc/control`) and the stress sidecar collector (`otlp_grpc`) send to a cluster collector that restarts on every kit start/stop, so they queue and retry the same way (the sidecar sets no `GOMEMLIMIT`, so the queued heap never drives the Go GC on the stress node); the Fluent Bit journald sender sets `retry_limit: no_limits` (its default is one retry) with `scheduler.cap: 30` (default 2000s between retries) and buffers in memory. No `file_storage`, WAL or `memory_limiter`, by owner decision. The Pyroscope Java agent gets `Constants.PyroscopeJavaAgent.PROFILER_FLAGS` (format and events) and `OUTAGE_FLAGS` (unlimited tries, 360 snapshots) at every site, and `edl_pyroscope_opts` in `edl-kit-lib.sh` repeats both literally (`TemplateVariablesTest` checks they match). The replay IT gets the agent jar from the `pyroscopeAgentConfig` Gradle configuration (`pyroscope-agent` in `libs.versions.toml`, which the test checks against `VERSION`). `CollectorReplayIntegrationTest` keeps Mimir, Loki and Tempo down for 6 minutes each (past the old 5s push deadline and the 300s default retry limit, which `prometheus_remote_write` applies too), behind a Service-like network alias (`ObservabilityBackends.attachAsService`) and asserts every number arrives. `PyroscopeAgentReplayIntegrationTest` runs a JVM with the pinned agent and `OUTAGE_FLAGS`, stops Pyroscope for 150s (past the old 8-snapshot queue and 8 tries) and asserts every 10s window of the outage holds a profile and that the agent's log shows upload errors (so its message format still matches) but no dropped upload (a 4xx other than 429, or "Gave up"), apart from at most one "empty ring" refusal in the start race below. `JournaldRetryIntegrationTest` runs the pinned Fluent Bit with the rendered journald config against no collector for five retries, starts a collector in its network namespace and asserts the chunk arrives within 60s, with no retry put off past 31s (the 30s cap plus the second Fluent Bit adds to every delay). Pyroscope 2.3.1 answers 422 to every push between SIGTERM and exit, and the agent does not retry a 422, so `config.yaml` sets `metastore.min_ready_duration` and `segment_writer.lifecycler.min_ready_duration` to `0s` and `analytics.reporting_enabled: false`: each was a fixed wait at shutdown too (30s in all), and without them it exits within seconds and the agent retries the refused connection. `segment_writer.lifecycler.tokens_file_path` keeps the ring tokens on the data volume; without them a restarted segment writer rejoins its ring with no tokens and refuses every push for about 5.5s after start (`PyroscopeIndexDurabilityIntegrationTest` holds the restarted server at a busybox start gate, and a pusher in its network namespace sees the port closed, releases the gate and pushes from the moment the port opens and asserts no push is refused after the first 100ms: the port opens about 20ms before the tokens file is read, and a push in that instant is refused). The database nodes' JFR shipper `edl-profiling-reconcile` leaves a chunk answered 429, or 422 with a body of "service is unavailable" or "empty ring", pending for its next pass, like a 5xx; any other 4xx (a 422 parse error included) renames it `.rejected`. A full 360-snapshot agent queue measured about 62 MB of heap (0.17 MB a snapshot) on a Cassandra stress JVM; that figure holds for that workload only, and a synthetic many-stack load measured 5 to 15 MB a snapshot, so kit, Spark and sidecar JVMs can hold more.
- **`JournaldOtelManifestBuilder`** — builds a Fluent Bit DaemonSet (`fluent-bit-journald`) for systemd journal collection, isolated from the main OTel collector. Fluent Bit has a native systemd input plugin with journalctl compiled in, so no external binary is needed. Reads journal files directly from `/var/log/journal` (mounted read-only). Uses a `modify` filter to rename `HOSTNAME` → `host.name` and add `source: journald` for unified search with other log sources. Maps `MESSAGE` to OTLP body (the Loki line) and `PRIORITY` to OTLP severity; the collector's `transform/source_to_resource` lifts `source` onto the resource so Loki indexes it. Remaining journald fields become OTLP attributes. Forwards logs via OTLP HTTP to the main collector on `localhost:4318`. Health check on port 2020. Security context: `runAsUser: 0` with `DAC_READ_SEARCH` capability. Image: `fluent/fluent-bit:5.1.2`.
- **Config resources** — `otel-collector-config.yaml` (main collector) and `fluent-bit-journald.yaml` (journald collector) stored in `resources/.../configuration/otel/`.
- **EMR collector** — `emr/otel-collector-config.yaml`, embedded by `EMRProvisioningService` in the `bootstrap-otel.sh` bootstrap action, which runs it as a root systemd service on every EMR node. Receivers: `otlp` (the Spark Java agent), `host_metrics`, and `file_log/emr`, which reads from the start the YARN container logs (`/var/log/hadoop-yarn/containers/application_*/container_*/*`), the step logs (`/mnt/var/log/hadoop/steps/*/*`, primary only) and the bootstrap-action logs (`/emr/instance-controller/log/bootstrap-actions/*/*`), paths from the EMR Management Guide ("View Amazon EMR log files") and the AWS knowledge-center article on pushing EMR application logs to CloudWatch. The logs pipeline stamps `source=emr` (`resource/source`), `node_role` (`resource/role`, patched per node by the bootstrap) and `host.name`, and exports over OTLP to the control node's collector, whose `logs/otlp` adds `cluster`. `CollectorToBackendsIntegrationTest` runs both rendered configs end to end.

## ebpf_exporter Subpackage (`ebpfexporter/`)

- **`EbpfExporterManifestBuilder`** — builds ebpf_exporter DaemonSet (no ConfigMap). Runs on all nodes with hostNetwork/hostPID/privileged. Uses built-in example programs (`biolatency`, `xfsdist`, `cachestat`) from the container image. No TemplateService needed. Available examples: https://github.com/cloudflare/ebpf_exporter/tree/master/examples

## Mimir Subpackage (`mimir/`)

- **`MimirManifestBuilder`** — builds Mimir 3.2.1's ConfigMap + Service + Deployment on the control node (hostNetwork, HTTP 9009, gRPC 9097, gossip on loopback 7947 — Pyroscope holds 7946). Monolithic target `distributor,ingester,querier,query-frontend,query-scheduler,store-gateway`: **no compactor**, so nothing in a cluster compacts or deletes metrics. The store-gateway (in-memory ring, replication 1) reads every tenant's blocks in the shared store: `querier.query_store_after: 0`, `limits.query_ingesters_within: 0`, `bucket_store.sync_interval: 1m`, `ignore_blocks_within: 0`, `bucket_index.max_stale_period: 87600h` (a compactor that no cluster runs never fails a query), `sync_dir: /data/tsdb-sync`; local blocks kept `retention_period: 15m` (issue 988: far fewer one-minute blocks per query; only local copies already in S3 go). The ingester cuts 1-minute blocks (`block_ranges_period: [1m]`, experimental and unvalidated in 3.2.1) and checks for compaction and shipping every 15s (`head_compaction_interval`, `ship_interval`), so a sample is in `mimir/<tenant>/` in about 2 minutes; a head idle for 2m is compacted (`head_compaction_idle_timeout`), and `flush_blocks_on_shutdown`. The TSDB (head, WAL, local blocks) is on the hostPath `/mnt/db1/mimir` (mounted at `/data`); grace period 600s. Multi-tenancy and tenant federation (`a|b`) are on; ingestion and label limits are raised, `max_global_series_per_user: 0`, `out_of_order_time_window: 1h` (the collectors replay up to an hour of queued samples after a Mimir outage; issue 991). `query_scheduler.max_outstanding_requests_per_tenant: 5000` (default 100) holds two full loads of the heaviest dashboard, about 150 queries of up to 14 parts each; past it the scheduler answers 429. The pod's second container is a memcached sidecar (`Constants.K8s.MIMIR_MEMCACHED_IMAGE`, `-m 2048`, listening on `127.0.0.1:${Constants.K8s.MIMIR_MEMCACHED_PORT}`, after the Mimir container so `kubectl logs` defaults to Mimir): the store-gateway's `index_cache`, `chunks_cache` and `metadata_cache` (the bucket index content, the tenant list, a tenant's block list and a missing meta.json for 30s, under the 1m sync: at the 5m default new blocks failed the querier's consistency check; immutable objects keep their defaults) and the query-frontend's `results_cache` (`cache_results: true`) use it, and the S3 client keeps idle connections (`idle_conn_timeout: 10m`, `max_idle_connections_per_host: 1000`) (issue 988). The query engine's range vector splitting cache stays off: Mimir 3.2.1 refuses to start with it. The account compactor reads the same `mimir.yaml` but uses none of these caches (its metadata cache is `compactor.scheduler-client.metadata_cache`, unset). `mimir.yaml` is expanded from env (`-config.expand-env=true`) fed from `cluster-config`; never write a dollar-brace in a comment there, the expansion reads comments too. `MimirIntegrationTest` runs the pinned image against LocalStack.

## Compactor Subpackage (`compactor/`)

- **`CompactorManifestBuilder`** — builds the `account-compactor` ConfigMap (the cluster `mimir.yaml` and `loki.yaml`, `tempo-backend.yaml` and `compactor-gate.sh`, rendered through `TemplateService`) and Deployment, from `(CompactorAgentJar, bucketRegion)`. One replica, `Recreate`, pod network, termination grace 60s, control-plane node selector and toleration, no limits. `CompactorContainers` builds the containers: the `gate-busybox` init container (`Constants.Compactor.GATE_IMAGE`, static musl) copies busybox into the `gate-bin` emptyDir; `lease` runs `java -jar /agent/compactor-lease.jar` on `AGENT_IMAGE` with the lease timing, bucket, region, cluster name and pod name in its environment, and is the only container with a readiness probe (`test -f /lease/ready`); `mimir-compactor` (`/bin/mimir`, `-target=compactor -compactor.cleanup-interval=1m -compactor.partial-block-deletion-delay=0 -compactor.blocks-retention-period=0`), `loki-compactor` (`/usr/bin/loki`, `-target=compactor -compactor.compaction-interval=10m -compactor.retention-enabled=false`) and `tempo-backend-scheduler` / `tempo-backend-worker` (`/tempo`, the worker on its own ports) each run as `busybox sh compactor-gate.sh -- <binary>`. `Scratch` names the host paths `/mnt/db1/compactor/{mimir,loki,tempo}`, their owners (0, 10001, 10001) and mount paths. `CompactorAgentJar` is the jar's SHA-256 and its host path `/mnt/db1/compactor/agent/<sha256>.jar` (hostPath type `File`); `CompactorAgentJarFile` finds the packaged jar (`easydblab.agentsdir`, else `<apphome>/agents`). `services/CompactorAgentInstaller` (in `services`, because it runs SSH commands and `configuration` never depends on `providers`) creates the directories, gives Loki's and Tempo's to 10001, and uploads the jar unless it is there (`Event.Grafana.CompactorAgentPrepared`). `CompactorManifestBuilderTest` locks every retention and deletion setting. `CompactorPodIntegrationTest` runs the real pod spec in Docker (`DockerPod`) with the real agent jar against LocalStack: a failover only after the TTL with every compactor answering `/ready` and none exiting, and merged Mimir, Loki and Tempo stores that keep every sample, log line and trace.
- **Config resources** — `compactor-gate.sh` (POSIX sh, every applet through `$GATE_BUSYBOX`; the grace is measured on `/proc/uptime`, never the wall clock, which it uses only for the valid-until comparison; cases in `src/test/shell/compactor-gate.cases.sh`, on a virtual clock, run under the local shells by `compactor-gate.test.sh` and under busybox by `compactor-gate-busybox.test.sh`) and `tempo-backend.yaml`: `backend_worker.compaction.block_retention: 876000h`, `backend_scheduler.provider.retention.interval: 87600h`, `overrides.defaults.global.max_bytes_per_trace: 0`, `storage.trace.empty_tenant_deletion_enabled: false`, no per-tenant override.

## Loki Subpackage (`loki/`)

- **`LokiManifestBuilder`** — builds Loki 3.7.8's ConfigMap + Service + Deployment on the control node (hostNetwork, HTTP 3100, gRPC 9098). A single binary (`target: all` — the only single-process target that both writes and reads the S3 index) with the compactor idle: `compaction_interval: 87600h`, `retention_enabled: false`, `retention_period: 0s`, `deletion_mode: disabled` (the delete API is not served). `object_prefix: loki`; tsdb v13 daily tables with the fixed `schema_config` (a permanent contract of the shared store: only append periods). The ingester is named `${TENANT}.${CLUSTER_NAME}`, so every index file names its tenant and cluster. OTLP index labels `cluster`, `host.name`, `node_role`, `source`; everything else is structured metadata. WAL (`flush_on_shutdown`), index head, index cache and compactor directory are on the hostPath `/mnt/db1/loki` (mounted at `/loki`, owned by uid 10001); grace period 600s. `multi_tenant_queries_enabled` allows `a|b`; `reject_old_samples_max_age: 8760h` accepts backdated lines. `ingester.max_chunk_age: 1h` flushes a chunk of a stream that keeps writing within about an hour, and sets the per-stream out-of-order window to 30 minutes (so a slow Loki restart does not drop logs); `tsdb_shipper.resync_interval: 1m` re-lists the index in S3 (the index itself uploads on Loki's fixed 15-minute rotation). The querier asks the ingester only for entries within `query_ingesters_within` (Loki's default, 3h); `AnnotationMirror` flushes the ingester (`POST /flush`) after pushing an annotation older than `Constants.Loki.INGESTER_QUERY_WINDOW_HOURS` (2h, deliberately earlier than that 3h cutoff, so a flush happens whenever an entry might be outside the ingester window) and waits until Loki answers it. `LokiIntegrationTest` runs the pinned image against LocalStack.

## Tempo Subpackage (`tempo/`)

- **`TempoManifestBuilder`** — builds Tempo ConfigMap + Service + Deployment. Tempo 3.0.3 runs on the control plane with native multi-tenancy and an S3 backend in the account bucket under `tempo` (Tempo makes the tenant directories). Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`, `${TRACES_S3_PREFIX}`), fed from the `cluster-config` ConfigMap. Compaction — which runs retention in Tempo 3 — is disabled for every tenant (`overrides.defaults.compaction.compaction_disabled: true`), and `backend_worker.compaction.block_retention: 876000h` is the second guard: no block is ever deleted. The live store cuts a block every minute (`max_block_duration: 1m`). `down` never stops or restarts Tempo; `TempoTailFlush` waits for its own cut and upload. No ingestion limit discards spans: the live-trace count is unlimited (`max_traces_per_user: 0`), there is no per-trace size limit, and the rate limit is 1 GB/s. Memory is bounded without dropping anything (issue 1002). `live_store.max_live_traces_bytes: 500000000` is Tempo's backpressure: past it a push waits, the sender's 5s deadline ends it, and the collector retries it from its queue. `memory.automemlimit_enabled` sets GOMEMLIMIT to 80% of the container limit. The Deployment requests 2Gi and is limited to 6Gi (`MEMORY_REQUEST`/`MEMORY_LIMIT`, the only limit on a control-node pod). Under an ingest rate past what Tempo could drain, it held 5.1 GB peak with no OOM. Without the bound it reached 12.4 GB and was OOM-killed. Ingest past Tempo's drain rate then backs up in the senders' in-memory queues. No attribute value is truncated: `distributor.max_attribute_bytes: 0` (default 2048; a per-tenant override of 0 falls back to the distributor value, so only the distributor setting turns truncation off). Both WAL paths live on the hostPath `/mnt/db1/tempo` (mounted at `/var/tempo`), so a pod restart keeps received spans; `TempoBlockDurabilityIntegrationTest` proves it against LocalStack. Tempo acknowledges a push while the spans are only in memory. `live_store.flush_check_period: 1s`, `max_trace_idle: 1s` and `max_trace_live: 10s` (defaults 5s/5s/30s) put a trace in the WAL about 2s after its last span, so a killed Tempo loses at most those ~2s. The window cannot close in single-binary mode. A graceful restart loses nothing. The WAL is not fsynced, so a node crash can lose WAL data that was not yet on disk. `commit_interval` applies only to Kafka and is left unset.
- **Config resource** — `tempo.yaml` stored in `resources/.../configuration/tempo/`.

## Stress Subpackage (`stress/`)

`StressJobManifestBuilder` is a pure object that builds the stress Job (with its OTel sidecar) and the short-lived command Job from a `StressJobConfig` and a `StressCluster` (the region, control node IP, cluster label, tenant and telemetry redirect, read once from `ClusterState` by `StressCluster.from`). It loads no state and calls no cluster, so its tests need neither. `StressSidecarConfig` owns the sidecar's collector config and its ConfigMap (`CONFIG_MAP_NAME`, `FILE_NAME`). `DefaultStressJobService` (in `services/`) keeps only the lifecycle: load the state once, build, apply, wait. The golden Jobs in `src/test/resources/.../services/stress-golden/` pin the rendered YAML: a change to the pod spec changes them on purpose, in the same commit.

## Registry Subpackage (`registry/`)

- **`RegistryManifestBuilder`** — builds Docker Registry Deployment. Runs on control plane with TLS cert hostPath mount and HTTPS probes. No TemplateService needed.

## S3 Manager Subpackage (`s3manager/`)

- **`S3ManagerManifestBuilder`** — builds S3 Manager Deployment. Runs on control plane with IAM-based auth. No TemplateService needed.

## kube-state-metrics Subpackage (`kubestatemetrics/`)

- **`KubeStateMetricsManifestBuilder`** — builds kube-state-metrics ServiceAccount, ClusterRole, ClusterRoleBinding, Service (ClusterIP :8080), and Deployment. One replica on the control node in the **pod network** (not hostNetwork, no hostPort). RBAC is read-only: every rule grants only `list` and `watch` (`READ_ONLY_VERBS`), over the upstream default resource set (`WATCHED_RESOURCES`). Image pinned in `Constants.KubeStateMetrics.IMAGE`. Deployed by `ObservabilityStackService` on every cluster, in both telemetry modes. No ConfigMap/TemplateService needed. Scraped by the OTel collector through node-local pod discovery (see the OTel subpackage), not through the Service, so a DaemonSet of collectors yields one series set.

## YACE Subpackage (`yace/`)

- **`YaceManifestBuilder`** — builds YACE (Yet Another CloudWatch Exporter) ConfigMap + Deployment. Runs on control plane, scrapes AWS CloudWatch metrics for S3, EBS, EC2, and OpenSearch services. Exposes Prometheus metrics on port 5001, scraped by OTel collector. The EBS, EC2 and OpenSearch jobs search on `easy_cass_lab=1` and `ClusterId=__CLUSTER_ID__` (keys substituted from `Constants.Vpc`), so each cluster's YACE reports only its own resources; the S3 job keeps `easy_cass_lab=1` alone, because the account bucket is shared (`YaceManifestBuilderTest`). (EMR metrics removed — replaced by direct OTel collection on Spark nodes.)
- **Config resource** — `yace-config.yaml` stored in `resources/.../configuration/yace/` with `__AWS_REGION__` template variable for region substitution.
- **Auto-discovery** — uses tag-based auto-discovery with the legacy `easy_cass_lab=1` marker (`Constants.Vpc.LEGACY_TAG_KEY`, substituted into `yace-config.yaml` as `__LEGACY_TAG_KEY__`) to find cluster resources in CloudWatch. Resources also carry the new `easy_db_lab=1` marker (`Constants.Vpc.TAG_KEY`); phase 1 of the tag rename (#1117) writes both and reads only the legacy one.
