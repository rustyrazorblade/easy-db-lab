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
- `tailFlush: TailFlushRecord?` — the signals a `down` already saved, as `TailSignal` → `SavedSignal(completedAt)`. Only `LOGS` and `METRICS` are ever recorded, each the moment its flush succeeds (their flushes stop Loki and Mimir and cannot run twice); a re-run skips them. `saveCompletedAt` is set once a save of every signal succeeded, right before the infrastructure teardown; a re-run (after a teardown that failed part-way) then skips the whole save and its SOCKS tunnel. `markInfrastructureUp()` clears it. `ClusterStateManager.save` writes a temp file with the state file's permissions and renames it over `state.json`, so a concurrent `load()` never reads a torn file

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

Handles persistence to `state.json` in the workspace (`context.workspaceFile("state.json")`, a required constructor argument bound in `ConfigurationModule`; `load()` fails with a `check()` message naming the absolute path when the file is missing):
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

- **`CassandraVersion`** — version definition (Cassandra, Java, Python versions). `lazy: true` declares a version without baking it: the entry still ships in every node's `/etc/cassandra_versions.yaml`, so it is discoverable and installable at runtime via `cassandra install`, but the AMI bake skips it. Loaded from `packer/cassandra/cassandra_versions.yaml` merged with the profile's `cassandra_versions/` extras.
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

- **`ObservabilityStore`** — the one place that builds the observability paths from the account bucket and the cluster's tenant. The backend prefixes carry no tenant, because each backend makes its own tenant directories: each is its tool's root at the top level of the account bucket (`Constants.Observability.*_ROOT`): `metricsPrefix()` (`mimir`), `logsPrefix()` (`loki`, index under `loki/index/`), `tracesPrefix()` (`tempo`), `profilesPrefix()` (`pyroscope`). `annotationsRoot()` is `grafana/annotations/<tenant>`, and `annotationsArtifact()` adds a `SnapshotName`. `documentsRoot(cluster)` is a test's document folder, `reports/<tenant>/<name>-<id>`, and `document(cluster, name)` one file in it (`Constants.Observability.REPORTS_ROOT`); `services/documents/TestDocumentService` writes there. No metrics or logs snapshot exists: Mimir and Loki write to S3 themselves.
- **`SnapshotName`** — `<yyyyMMdd-HHmmss>_<name>-<clusterId>`, the name of an annotations backup. `SnapshotName.firstFree` moves a name past every second whose S3 key already exists, so two backups of one cluster in the same second never overwrite each other; `GrafanaAnnotationBackupService` names every backup through it.
- **`ConfigHashAnnotator`** — stamps each Deployment/DaemonSet/StatefulSet pod template with `easydblab.com/config-hash`, a SHA-256 of the rendered pod template (serialized with object keys sorted, the hash annotation left out) and of the ConfigMaps it reads (volumes, `configMapKeyRef`, `envFrom`), including ConfigMaps built elsewhere such as `cluster-config`. Hashing the template makes the hash change exactly when Kubernetes rolls the workload, so an image, probe, env or volume change is reported as a change; both sides of the comparison are hashes of what easy-db-lab rendered, never of the server-defaulted object. `ObservabilityStackService` applies it to every stage, and `OtelSyncService` re-applies the collector when a kit start/stop regenerates its ConfigMap (no forced rollout-restart, so the template hash tracks the ConfigMap and the next deploy sees the collector unchanged). Both build and hash the collector through one function, `services/CollectorResources`, which reads the telemetry redirect and CNI from cluster state and the pod-log source from the capabilities — `deploy()` takes no redirect argument, so the two paths cannot stamp different hashes, and `GrafanaDeployService` to Grafana with the `grafana-datasources` contents, so a workload rolls only when its configuration changed. A workload that reads a ConfigMap whose contents the caller did not pass is refused (`IllegalArgumentException` naming both), since its hash could never change. Before applying, `services/ConfigChangeReport` compares each workload's hash with the one running (`K8sService.workloadConfigHashes`) and emits `Event.Grafana.WorkloadConfigCompared(workload, changed)`, so `up` and `grafana update-config` say which workloads roll.
- **`cluster-config`** ConfigMap keys (built by `services/ClusterConfigData`, shared by the stack deploy and the collector resync so both hash it identically): `control_node_ip`, `aws_region`, `s3_bucket` (account bucket), `traces_s3_prefix` (`tempo`), `metrics_s3_prefix` (`mimir`), `logs_s3_prefix` (`loki`), `cluster_name`, `tenant`, `capabilities` (the present node capabilities). It is the one source of the tenant for every in-cluster producer and backend: the collector, Alloy and Loki read it as the env var `TENANT` (`clusterConfigEnv()` builds such an env var). Producers outside Kubernetes (JFR shipper, Java agents, EMR, Trino/Presto start scripts) keep their own configuration path; the Trino and Presto start scripts read only `cluster_name` from it, for their Pyroscope `cluster` label.

## Node Capabilities (`NodeCapability.kt`)

- **`NodeCapability`** (`SYSTEMD_JOURNAL`, `CLOUDWATCH`, `S3`, `PRIVILEGED`, `HOST_NETWORK`, `HOST_PATH`, `NODE_SSH`) is what an execution environment's nodes, account or admission policy provide; each has a `serialName` (its name in `state.json`, `init --capability` and `cluster-config`) and an `absentReason`. **`NodeCapabilities`** is a set (`AWS` has all seven; `LOCAL_DOCKER` has `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH`); `present()`/`absent()` list it in enum order. `NODE_SSH` is renamed `NODE_ACCESS` when #1039 lands. Consumers ask `capability in capabilities`; they never test an environment name.
- **Recorded set**: `init` records the effective set in `ClusterState.capabilities` (serial names; Jackson `@JsonProperty` matches `@SerialName`). `NodeCapabilities.from(state)` returns it, or `AWS` for a state that records none. Only the effective set is recorded, no provenance. `init` starts from AWS (it creates AWS clusters only; #1040 supplies the declared set per environment).
- **Overrides**: `init --capability <name>=<bool>` (repeatable) is parsed by `CapabilityOverrides.parse` (pure; fails on an unknown name, a value other than `true`/`false`, or one name given both values, listing the valid names) before `init` cleans or writes anything; `applyTo` adds or removes each one.
- **Init report**: `init` prints, for each absent capability, its reason and every stage that requires it (`services/AbsentCapabilityReport`), or "no component needs it yet". The stage list is `services/ObservabilityStage.declared(redirect)`, the same declarations `ObservabilityStackService` builds its stages from, so the report names exactly what `up` skips.
- **Probe**: `services/CapabilityProbeService.probe(KubeApi, namespace)` decides `PRIVILEGED`, `HOST_NETWORK` and `HOST_PATH` by submitting `dryRun=All` Pods (`CapabilityProbePods`: a Pod Security "restricted" baseline, then one Pod per capability differing in one field; never a DaemonSet). It creates a missing namespace and waits for its default ServiceAccount. `services/ProbeRejection` classifies a rejection: 403/422, or a 400 "admission webhook ... does not support dry run" (the note names the webhook), makes the capability absent with a note; a rejected baseline or any other error fails naming the namespace. `NODE_SSH` is never in a probed set. Not called on AWS or local Docker; `init --kubeconfig` (#1082) calls it. `CapabilityProbeIntegrationTest` runs it on K3s.
- **Publishing**: `cluster-config` key `capabilities` (present serial names, enum order, comma-joined, so an unchanged set keeps the config hash); `KitProcessEnvironment` sets `EDL_NODE_SSH` from `NODE_SSH` in the workspace's recorded set (AWS when there is no `state.json`; a `state.json` that cannot be read fails the launch naming the file; the state comes from the injected `ClusterStateManager`), not from `sshConfig`; `status` prints `=== CAPABILITIES ===`.
- Gated workloads (`services/ObservabilityStage`): Fluent Bit journald needs `SYSTEMD_JOURNAL` and `PRIVILEGED`; the host add-on collector, Beyla, ebpf_exporter and the Pyroscope eBPF agent need `PRIVILEGED`; the core collector's pod logs need `HOST_PATH` (the pseudo-stage "Pod logs", which applies nothing); YACE needs `CLOUDWATCH` (and so does the core collector's `yace` scrape job, rendered by `OtelManifestBuilder.buildInfraScrapeJobs`); s3manager needs `S3` (also its section in `status` and `StatusCache`'s `accessInfo`).
- `ObservabilityStage` entries and `StageRequirement` carry `requires: Set<NodeCapability>`; a stage is applied only when every capability is present. A stage missing any is not applied, not awaited, and emits one `Event.Grafana.StageSkipped` per missing capability, and `init`'s report lists it under each one. A stage may name workloads in `rolledOutFirst`: the stack waits for their rollout before it applies the stage, so the host add-on never races old core pods for hostPorts 4317 and 4318. Builders stay unaware of capabilities. `CollectorResources.build` takes the capabilities as a required argument, and the deploy and the kit sync (`DefaultOtelSyncService`) each derive them through a `capabilitiesOf` seam, so both render the same collector config. Where `HOST_PATH` exists, pod logs reach Loki without a journal through `file_log/containers` (`PodLogsWithoutJournalIntegrationTest`).

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
   collector (`localhost:4318/v1/metrics`, the host add-on's node listener, which forwards to the
   core's `metrics/otlp` pipeline). The Prometheus textfile it also writes is a convenience for someone on the node — no
   node_exporter and no textfile collector exists, so nothing reads it. Do not "simplify" the push
   away in favour of the file.
2. **Grafana Alloy eBPF DaemonSet** (all nodes; its own stage "Pyroscope Agent", gated on `PRIVILEGED`, so the server is applied and awaited without it) — `pyroscope.ebpf` component collects `process_cpu`
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

1. `SetupInstance` seeds `/etc/easy-db-lab/profiling.json` on each Cassandra node with
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

- **`BeylaManifestBuilder`** — builds Beyla eBPF auto-instrumentation ConfigMap + DaemonSet. Runs on all nodes with hostNetwork/hostPID/privileged for eBPF access, so its stage is gated on `PRIVILEGED`. The host add-on scrapes it at `localhost:9400`.
- **Config resource** — `beyla-config.yaml` stored in `resources/.../configuration/beyla/`. It sets `javaagent.enabled: false`: Beyla otherwise attaches its Java agent to every JVM it instruments, including the database under test. `BeylaManifestBuilderTest` guards it.

## OTel Subpackage (`otel/`)

Two collectors run on every node. The core is unprivileged and the only exporter to the backends. The host add-on is privileged and forwards everything to the core. Both are DaemonSets, so a singleton target is scraped only by the collector on its node, and node identity is the `K8S_NODE_NAME`/`K8S_NODE_IP` env (downward API `spec.nodeName`/`status.hostIP`), never the OS hostname, which on the pod network is the pod's name.

- **`OtelManifestBuilder`** — the core collector (`otel-collector`): ServiceAccount, ClusterRole (pods, nodes, `nodes/stats get` for `kubelet_stats`), ClusterRoleBinding, ConfigMap, DaemonSet and Service. The DaemonSet is in the pod network (`hostNetwork: false`, container ports only, no hostPort), tolerates every taint, and rolls with `maxSurge: 1, maxUnavailable: 0`. The Service (`buildService`) is ClusterIP with `internalTrafficPolicy: Local` on OTLP gRPC 4317, OTLP HTTP 4318 and Jaeger 6831/UDP, and selects only `app.kubernetes.io/name=otel-collector`; `OtelEndpoints` names it (`otel-collector.<ns>.svc`). Receivers: `otlp`, `jaeger`, `kubelet_stats` (`https://${env:K8S_NODE_IP}:10250`, service-account auth, the kubelet cert verified against the service-account `ca.crt`, which K3s signs it with; `CoreAndHostCollectorIntegrationTest` proves the scrape succeeds), `prometheus` (its own telemetry, backend, infra and kit jobs) and, with `PodLogSource.NodeFiles`, `file_log/containers` and `file_log/clickhouse-containers`. `resource/host_name` sets `host.name` from `K8S_NODE_NAME`. `metrics/otlp` keeps and restores the EMR nodes' `host.name` around it (`transform/keep_emr_host`, `transform/restore_emr_host`), so EMR nodes do not arrive as the control node (`CollectorToBackendsIntegrationTest`). Host-file log counters skip forwarded host lines with `NOT_A_HOST_LOG` (`source == nil`).
  - **`PodLogSource`** (`NodeFiles` | `None`) — the only environment-bound input to the DaemonSet and config, chosen by `services/CollectorResources.podLogSource(capabilities)` (`NodeFiles` when `HOST_PATH`). `NodeFiles` mounts the kubelet's `/var/log/pods` read-only (hostPath type `Directory`), runs as root (UID 0) with all capabilities dropped but `DAC_READ_SEARCH`, and renders the `logs/containers` pipeline from `otel-collector-pod-logs.yaml` into `__POD_LOG_RECEIVERS__`/`__POD_LOG_PIPELINE__`. `None` renders no pod-log receiver or pipeline, mounts no hostPath and runs non-root (`Constants.OtelCollector.NON_ROOT_UID`) with every capability dropped. Both are `privileged: false`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` (the config writes nothing to disk), seccomp `RuntimeDefault`.
  - **Local backend scrape jobs** (`buildLocalBackendScrapeJobs(telemetryRedirect)`, rendered into `__INFRA_SCRAPE_JOBS__` ahead of the infra jobs): `tempo` (port 3200) and `pyroscope` (port 4040), both single pods on the control node, found by `NodeScrapeJobs.nodeLocalPodJob` (pod SD in `default` on `app.kubernetes.io/name`, filtered to the collector's own node, `instance` = pod name). Local mode only; a redirect cluster deploys neither server.
  - **Infrastructure scrape jobs** (`buildInfraScrapeJobs(capabilities)`): kube-state-metrics on every cluster, `yace` (instance `${K8S_NODE_NAME}:<port>`) only with `CLOUDWATCH`. These are Kotlin, not template text, because their relabel rules name `__meta_kubernetes_*`/`__address__`; a bare `__x__` in the template is a `TemplateService` placeholder.
  - **Per-workload scrape jobs** (`buildKitScrapeJobs`) from the metrics registry's `WorkloadScrapeConfig`s: a **static NodePort** job targets `${env:K8S_NODE_IP}:<port>` (the core is not on the host network, so `localhost` would be the pod) with `instance` = `<node>:<port>`; a config with a `podSelector` becomes a node-local **pod service-discovery** job (pod IP and container port, `instance` = pod name). Every built-in kit uses pod SD. Static NodePort jobs are still scraped once per node (known, out of scope).
- **`OtelHostCollectorManifestBuilder`** — the host add-on (`otel-collector-host`): its own ServiceAccount, ClusterRole (pods get/watch/list, for the Cilium operator's pod discovery), ClusterRoleBinding, ConfigMap (`otel-host-collector-config.yaml`, with `__CNI_SCRAPE_JOBS__`) and DaemonSet. Deployed as the stage "OTel Host Collector", gated on `PRIVILEGED`, and applied only after the core's rollout. hostNetwork with `ClusterFirstWithHostNet` (so it resolves the Service), privileged root, hostPorts 4317, 4318 and 13133, the host root read-only at `/hostfs` (`HOST_ROOT_MOUNT_PATH`, `host_metrics.root_path`) and `/var/log` and `/mnt/db1/cassandra/logs` read-only. Receivers: `otlp` on the node's 4317/4318 (host processes that are not pods: the AMI Cassandra agent, the profiling reconciler's counters, Fluent Bit, EMR), `host_metrics`, `file_log/system|tools|cassandra` (the last tails only the JVM GC log, `source=cassandra-gc`; each sets `source` on the resource), and `prometheus` (Beyla 9400, ebpf_exporter 9435, its own telemetry as job `otel-collector-host`, and on Cilium the agent, Hubble and operator from `buildCniScrapeJobs`). Host sources get `host.name` from `K8S_NODE_NAME` and `transform/node_role_from_host`. Every pipeline ends in `otlp_grpc/core` to `${env:OTEL_CORE_ENDPOINT}` (the Service on 4317); it has no backend exporter, no tenant header and no `memory_limiter`. The Service reaches only the node's own core pod, so `otlp_grpc/core` retries with no time limit (`retry_on_failure.max_elapsed_time: 0s`) and queues up to 5000 batches (`sending_queue`), which holds the node's data through a core restart.
- **`NodeScrapeJobs`** — the scrape-job shapes both builders share: `nodeLocalPodJob`, `localhostJob`, the cluster relabel, and the `NODE_NAME`/`NODE_IP` env expansions.
- **`JournaldOtelManifestBuilder`** — builds a Fluent Bit DaemonSet (`fluent-bit-journald`) for systemd journal collection, isolated from the collectors. Fluent Bit has a native systemd input plugin with journalctl compiled in, so no external binary is needed. Reads journal files directly from `/var/log/journal` (mounted read-only). Uses a `modify` filter to rename `HOSTNAME` → `host.name` and add `source: journald` for unified search with other log sources. Maps `MESSAGE` to OTLP body (the Loki line) and `PRIORITY` to OTLP severity; the core's `transform/source_to_resource` lifts `source` onto the resource so Loki indexes it. Remaining journald fields become OTLP attributes. Forwards logs via OTLP HTTP to `localhost:4318`, the host add-on's node listener, which forwards them to the core. Health check on port 2020. Security context: `runAsUser: 0` with `DAC_READ_SEARCH` capability. Image: `fluent/fluent-bit:5.1.2`.
- **Config resources** — `otel-collector-config.yaml` (core), `otel-collector-pod-logs.yaml` (the core's `NodeFiles` receivers), `otel-host-collector-config.yaml` (host add-on) and `fluent-bit-journald.yaml` (journald collector), in `resources/.../configuration/otel/`.
- **Producers** — in-cluster producers send to the Service, never a node IP: the stress JVM agent (`OtelEndpoints.otlpHttpEndpoint()`), the stress sidecar (`__OTEL_CORE_GRPC_ENDPOINT__`), the Cassandra sidecar (`otlpGrpcEndpoint()`), and kits through `__OTEL_OTLP_HTTP_ENDPOINT__`/`__OTEL_COLLECTOR_HOST__` (`TemplateVariables`, also kit-process env). `BuiltinKitCollectorEndpointTest` fails on a kit file naming a collector port on `HOST_IP`, `CONTROL_HOST_PRIVATE` or a hand-written `svc.cluster.local`. `edl_push_gauges` (shared kit library) runs from the operator's machine, so it posts to the running core pod on the control node through the API server's pod proxy (`/api/v1/namespaces/default/pods/<pod>:4318/proxy/v1/metrics`) on a `kubectl proxy` that lives only for that push. Not the Service proxy: it picks a core pod on any node per request, and each core stamps its own node as `host.name`, so one gauge would split into a series per node. It skips a core pod with a `deletionTimestamp` (one being replaced in a rollout). A TERM or INT during the push is only noted by its trap; the push then stops the proxy, restores the caller's traps, clears its `EDL_PUSH_*` globals and sends the signal to the shell again (bash 3.2 drops an INT a trap sends to its own shell).
- **EMR collector** — `emr/otel-collector-config.yaml`, embedded by `EMRProvisioningService` in the `bootstrap-otel.sh` bootstrap action, which runs it as a root systemd service on every EMR node. Receivers: `otlp` (the Spark Java agent), `host_metrics`, and `file_log/emr`, which reads from the start the YARN container logs (`/var/log/hadoop-yarn/containers/application_*/container_*/*`), the step logs (`/mnt/var/log/hadoop/steps/*/*`, primary only) and the bootstrap-action logs (`/emr/instance-controller/log/bootstrap-actions/*/*`), paths from the EMR Management Guide ("View Amazon EMR log files") and the AWS knowledge-center article on pushing EMR application logs to CloudWatch. The logs pipeline stamps `source=emr` (`resource/source`), `node_role` (`resource/role`, patched per node by the bootstrap) and `host.name`, and exports over OTLP to the control node's 4317 (the host add-on there), which forwards to the core, whose `logs/otlp` adds `cluster`. `CollectorToBackendsIntegrationTest` runs both rendered configs end to end.

## ebpf_exporter Subpackage (`ebpfexporter/`)

- **`EbpfExporterManifestBuilder`** — builds ebpf_exporter DaemonSet (no ConfigMap). Runs on all nodes with hostNetwork/hostPID/privileged, so its stage is gated on `PRIVILEGED`. The host add-on scrapes it at `localhost:9435`. Uses built-in example programs (`biolatency`, `xfsdist`, `cachestat`) from the container image. No TemplateService needed. Available examples: https://github.com/cloudflare/ebpf_exporter/tree/master/examples

## Mimir Subpackage (`mimir/`)

- **`MimirManifestBuilder`** — builds Mimir 3.2.1's ConfigMap + Service + Deployment on the control node (hostNetwork, HTTP 9009, gRPC 9097, gossip on loopback 7947 — Pyroscope holds 7946). Monolithic target `distributor,ingester,querier,query-frontend,query-scheduler,store-gateway`: **no compactor**, so nothing in a cluster compacts or deletes metrics. The store-gateway (in-memory ring, replication 1) reads every tenant's blocks in the shared store: `querier.query_store_after: 0`, `limits.query_ingesters_within: 0`, `bucket_store.sync_interval: 1m`, `ignore_blocks_within: 0`, `bucket_index.max_stale_period: 87600h` (a stopped account compactor never fails a query), `sync_dir: /data/tsdb-sync`; local blocks kept `retention_period: 15m` (issue 988: far fewer one-minute blocks per query; only local copies already in S3 go). The ingester cuts 1-minute blocks (`block_ranges_period: [1m]`, experimental and unvalidated in 3.2.1) and checks for compaction and shipping every 15s (`head_compaction_interval`, `ship_interval`), so a sample is in `mimir/<tenant>/` in about 2 minutes; a head idle for 2m is compacted (`head_compaction_idle_timeout`), and `flush_blocks_on_shutdown`. The TSDB (head, WAL, local blocks) is on the hostPath `/mnt/db1/mimir` (mounted at `/data`); grace period 600s. Multi-tenancy and tenant federation (`a|b`) are on; ingestion and label limits are raised, `max_global_series_per_user: 0`, `out_of_order_time_window: 10m`. `query_scheduler.max_outstanding_requests_per_tenant: 5000` (default 100) holds two full loads of the heaviest dashboard, about 150 queries of up to 14 parts each; past it the scheduler answers 429. The pod's second container is a memcached sidecar (`Constants.K8s.MIMIR_MEMCACHED_IMAGE`, `-m 2048`, listening on `127.0.0.1:${Constants.K8s.MIMIR_MEMCACHED_PORT}`, after the Mimir container so `kubectl logs` defaults to Mimir): the store-gateway's `index_cache`, `chunks_cache` and `metadata_cache` (the bucket index content, the tenant list, a tenant's block list and a missing meta.json for 30s, under the 1m sync: at the 5m default new blocks failed the querier's consistency check; immutable objects keep their defaults) and the query-frontend's `results_cache` (`cache_results: true`) use it, and the S3 client keeps idle connections (`idle_conn_timeout: 10m`, `max_idle_connections_per_host: 1000`) (issue 988). The query engine's range vector splitting cache stays off: Mimir 3.2.1 refuses to start with it. The account compactor reads the same `mimir.yaml` but uses none of these caches (its metadata cache is `compactor.scheduler-client.metadata_cache`, unset). `mimir.yaml` is expanded from env (`-config.expand-env=true`) fed from `cluster-config`; never write a dollar-brace in a comment there, the expansion reads comments too. `MimirIntegrationTest` runs the pinned image against LocalStack.

## Compactor Task Definition (`services/aws/`)

The compactor's task definition and its `tempo-backend.yaml` live in `services/aws` (they are AWS ECS), not in this package.

- **`CompactorTaskDefinition`** — the pure builder of the account compactor's ECS task definition from `(bucket, region, taskRoleArn, executionRoleArn, logGroup)`: a busybox `config` container (non-essential) writes the base64 configs from its environment to a shared volume, then `mimir-compactor` (cluster `mimir.yaml` + `-target=compactor -compactor.cleanup-interval=1m -compactor.partial-block-deletion-delay=0 -compactor.blocks-retention-period=0`), `loki-compactor` (cluster `loki.yaml` + `-target=compactor -compactor.compaction-interval=10m -compactor.retention-enabled=false`, user 0) and `tempo-backend-scheduler` / `tempo-backend-worker` (`tempo-backend.yaml`, user 0, own ports). ARM64 Fargate, 2 vCPU / 8 GiB / 100 GiB, `awslogs` to `/easy-db-lab/compactor`; `configHash()`, a SHA-256 over every field of the untagged `RegisterTaskDefinition` request, is tagged on the revision. `logStream(container, taskId)` is the awslogs stream name `status` reads. `mimir-compactor` mounts the task volume `mimir-data` at `/data`, where `mimir.yaml` writes its activity file and TSDB; without it the container exits at start. `CompactorTaskIntegrationTest` runs every compactor container from the real task definition in one network namespace against LocalStack, and proves a merged Mimir store keeps every sample and is readable by a new Mimir. Configs are read from the classpath, not `TemplateService`, because `observability compactor` runs outside a workspace. `CompactorTaskDefinitionTest` locks every retention and deletion setting.
- **Config resource** — `tempo-backend.yaml`: `backend_worker.compaction.block_retention: 876000h`, `backend_scheduler.provider.retention.interval: 87600h`, `overrides.defaults.global.max_bytes_per_trace: 0`, `storage.trace.empty_tenant_deletion_enabled: false`, no per-tenant override.

## Loki Subpackage (`loki/`)

- **`LokiManifestBuilder`** — builds Loki 3.7.8's ConfigMap + Service + Deployment on the control node (hostNetwork, HTTP 3100, gRPC 9098). A single binary (`target: all` — the only single-process target that both writes and reads the S3 index) with the compactor idle: `compaction_interval: 87600h`, `retention_enabled: false`, `retention_period: 0s`, `deletion_mode: disabled` (the delete API is not served). `object_prefix: loki`; tsdb v13 daily tables with the fixed `schema_config` (a permanent contract of the shared store: only append periods). The ingester is named `${TENANT}.${CLUSTER_NAME}`, so every index file names its tenant and cluster. OTLP index labels `cluster`, `host.name`, `node_role`, `source`; everything else is structured metadata. WAL (`flush_on_shutdown`), index head, index cache and compactor directory are on the hostPath `/mnt/db1/loki` (mounted at `/loki`, owned by uid 10001); grace period 600s. `multi_tenant_queries_enabled` allows `a|b`; `reject_old_samples_max_age: 8760h` accepts backdated lines. `ingester.max_chunk_age: 1h` flushes a chunk of a stream that keeps writing within about an hour, and sets the per-stream out-of-order window to 30 minutes (so a slow Loki restart does not drop logs); `tsdb_shipper.resync_interval: 1m` re-lists the index in S3 (the index itself uploads on Loki's fixed 15-minute rotation). The querier asks the ingester only for entries within `query_ingesters_within` (Loki's default, 3h); `AnnotationMirror` flushes the ingester (`POST /flush`) after pushing an annotation older than `Constants.Loki.INGESTER_QUERY_WINDOW_HOURS` (2h, deliberately earlier than that 3h cutoff, so a flush happens whenever an entry might be outside the ingester window) and waits until Loki answers it. `LokiIntegrationTest` runs the pinned image against LocalStack.

## Tempo Subpackage (`tempo/`)

- **`TempoManifestBuilder`** — builds Tempo ConfigMap + Service + Deployment. Tempo 3.0.3 runs on the control plane with native multi-tenancy and an S3 backend in the account bucket under `tempo` (Tempo makes the tenant directories). Config uses Tempo runtime env expansion (`${S3_BUCKET}`, `${AWS_REGION}`, `${TRACES_S3_PREFIX}`), fed from the `cluster-config` ConfigMap. Compaction — which runs retention in Tempo 3 — is disabled for every tenant (`overrides.defaults.compaction.compaction_disabled: true`), and no block retention is set: no block is ever deleted. The live store cuts a block every minute (`max_block_duration: 1m`). `down` never stops or restarts Tempo; `TempoTailFlush` waits for its own cut and upload. No ingestion limit discards spans: live traces are unlimited (`max_traces_per_user: 0`, `live_store.max_live_traces_bytes: 0`), there is no per-trace size limit, and the rate limit is 1 GB/s. No attribute value is truncated: `distributor.max_attribute_bytes: 0` (default 2048; a per-tenant override of 0 falls back to the distributor value, so only the distributor setting turns truncation off). Both WAL paths live on the hostPath `/mnt/db1/tempo` (mounted at `/var/tempo`), so a pod restart keeps received spans; `TempoBlockDurabilityIntegrationTest` proves it against LocalStack. Tempo acknowledges a push while the spans are only in memory. `live_store.flush_check_period: 1s`, `max_trace_idle: 1s` and `max_trace_live: 10s` (defaults 5s/5s/30s) put a trace in the WAL about 2s after its last span, so a killed Tempo loses at most those ~2s. The window cannot close in single-binary mode. A graceful restart loses nothing. The WAL is not fsynced, so a node crash can lose WAL data that was not yet on disk. `commit_interval` applies only to Kafka and is left unset.
- **Config resource** — `tempo.yaml` stored in `resources/.../configuration/tempo/`.

## Registry Subpackage (`registry/`)

- **`RegistryManifestBuilder`** — builds Docker Registry Deployment. Runs on control plane with TLS cert hostPath mount and HTTPS probes. No TemplateService needed.

## S3 Manager Subpackage (`s3manager/`)

- **`S3ManagerManifestBuilder`** — builds S3 Manager Deployment. Runs on control plane with IAM-based auth. No TemplateService needed.

## kube-state-metrics Subpackage (`kubestatemetrics/`)

- **`KubeStateMetricsManifestBuilder`** — builds kube-state-metrics ServiceAccount, ClusterRole, ClusterRoleBinding, Service (ClusterIP :8080), and Deployment. One replica on the control node in the **pod network** (not hostNetwork, no hostPort). RBAC is read-only: every rule grants only `list` and `watch` (`READ_ONLY_VERBS`), over the upstream default resource set (`WATCHED_RESOURCES`). Image pinned in `Constants.KubeStateMetrics.IMAGE`. Deployed by `ObservabilityStackService` on every cluster, in both telemetry modes. No ConfigMap/TemplateService needed. Scraped by the OTel collector through node-local pod discovery (see the OTel subpackage), not through the Service, so a DaemonSet of collectors yields one series set.

## YACE Subpackage (`yace/`)

- **`YaceManifestBuilder`** — builds YACE (Yet Another CloudWatch Exporter) ConfigMap + Deployment. Runs on control plane, scrapes AWS CloudWatch metrics for S3, EBS, EC2, and OpenSearch services. Exposes Prometheus metrics on port 5001, scraped by OTel collector. The EBS, EC2 and OpenSearch jobs search on `easy_cass_lab=1` and `ClusterId=__CLUSTER_ID__`, so each cluster's YACE reports only its own resources; the S3 job keeps `easy_cass_lab=1` alone, because the account bucket is shared (`YaceManifestBuilderTest`). (EMR metrics removed — replaced by direct OTel collection on Spark nodes.)
- **Config resource** — `yace-config.yaml` stored in `resources/.../configuration/yace/` with `__AWS_REGION__` template variable for region substitution.
- **Auto-discovery** — uses tag-based auto-discovery with the `easy_cass_lab=1` tag to find cluster resources in CloudWatch.
