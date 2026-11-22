## Context

Part of #1032 (Kubernetes-forward epic), phase A. Design source: `docs/plans/2026-11-11-kubernetes-forward-architecture.md`, section "Tenancy". Base: `origin/local-k8s-edbl` at `9dfb5d3b7` (#1226). Kotlin paths are under `src/main/kotlin/com/rustyrazorblade/easydblab/`; resource paths under `src/main/resources/com/rustyrazorblade/easydblab/`.

### Where the namespace comes from

- `ClusterContract.namespace` already exists. It is fixed per environment: `OWNED_NAMESPACE="default"` for Ec2K3s and K3sDocker, `BROUGHT_NAMESPACE="easy-db-lab"` for Kubeconfig (`configuration/environment/ClusterContract.kt:88,105,121`, `Constants.kt:1259-1262`). Its KDoc names #1094 as the change that adds the flag.
- No production code reads `contract.namespace`.

### Where `default` is written

- Three global constants: `Constants.K8s.NAMESPACE` (`Constants.kt:947`), `Constants.Stress.NAMESPACE` (`Constants.kt:1707`), `ContractDefaults.OWNED_NAMESPACE`.
- `Constants.K8s.NAMESPACE` is used in 28 files, for example `BackendEndpoints.kt:19`, `OtelEndpoints.kt:15-23`, `TelemetrySenders.kt:56`, `BackendWorkloads.kt:34`, `SidecarService.kt:76,87`, `MetricsRegistryService`, `OtelSyncService`, `KitEndpointServices.kt:24`.
- 12 private `NAMESPACE = "default"` constants: Yace, Pyroscope, Tempo, JournaldOtel, Grafana, GrafanaFront, Registry, Beyla, EbpfExporter, S3Manager builders, `ObservabilityStackService.kt:72` and `GrafanaDeployService.kt:108`.
- Literal default parameters: `K8sService.kt:32` (`PersistentVolumeConfig`), `:122`, `:127`; `K8sReadOperations.kt:45`; `KitConfig.kt:172` (`KitRuntime.namespace`); `InstallStep.kt:20,37,157,173`; `WorkloadStepExecutor.kt:181,187`; `KitWorkloadProbe.kt:139`; `KitEndpointServices.kt:68`; `KitStatusCommand.kt:90`; `DefaultK8sReadOperations.kt:136,161`.

### Outside Kotlin

- About 167 namespace-shaped `default` lines in kit files (`namespace: default`, `-n default`, `--namespace default`, `.default.svc.cluster.local`, Strimzi `watchNamespaces: "{default}"` at `kits/kafka/kit.yaml:131`).
- Shared resources: `configuration/edl-kit-lib.sh:294,299,340,453,464,493,500,505`; `configuration/env.sh:234,241`; `configuration/clickhouse/{config,keeper_config}.xml` (no Kotlin reference was found; check whether they are dead before converting them); `help/observability.md:14`.

### Cluster-scoped objects with fixed names

- ClusterRole and ClusterRoleBinding: `otel-collector` (`OtelManifestBuilder.kt:71,177,198`), the host collector's (`OtelHostCollectorManifestBuilder.kt:102,116`), `pyroscope-ebpf` (`PyroscopeManifestBuilder.kt:73`), `kube-state-metrics` (`KubeStateMetricsManifestBuilder.kt:106,126`).
- `edl-local-<kit>` StorageClasses already carry both labels (`KitLocalStorageClass.kt:35-36`).
- Kit PVs carry neither label (`DefaultK8sStorageOperations.kt:186-188`).

### Labels

- The constants exist: `Constants.K8s.MANAGED_BY_LABEL`, `MANAGED_BY_VALUE` and `CLUSTER_LABEL` (`Constants.kt:991-996`).
- The only users are `KitLocalStorageClass` and the `cluster-config` ConfigMap, which gets managed-by only, through a literal at `ObservabilityStackService.kt:312`.

### Apply path

Every Kotlin apply goes through `DefaultK8sManifestOperations.applyResource`, a force-conflicts server-side apply (`services/DefaultK8sManifestOperations.kt:99-124`). That is a single seam.

### How kit variables flow

`TemplateVariables.toMap()` (`services/TemplateVariables.kt:51-91`) feeds `${VAR}` in `kit.yaml` (`interpolateStepVars`, `WorkloadStepExecutor.kt:16`), `__VAR__` in templates, and the process environment (`KitProcessEnvironment.applyTo`, `services/KitProcessEnvironment.kt:57`). `RESERVED_NAMES` (`:99`) stops a kit argument from shadowing a cluster variable. No kit declares `NAMESPACE` today.

### EKS slice, Child A (`.agentlogs/plans/eks-kubeconfig-slice.md`)

- D4 there changes the Kubeconfig contract to `OWNED_NAMESPACE` "until #1094".
- Stage 2 puts the same two labels on every object applied by the stack deploy, the Grafana deploy and the collector resync. It also refuses to overwrite a foreign object and narrows `awaitStack` to the stack's own pods.
- Neither change is on this base yet.

### Approach

```
init --namespace NS ──► InitConfig.namespace (nullable, validated)
                              │
ClusterContract.of(state) ──► namespace = recorded ?: environment default
                              │
                    InstallScope(namespace, clusterId)   (configuration/tenancy/)
        ┌──────────────┬──────────────┼───────────────┬──────────────────┐
  manifest builders  ServiceDns   K8s services     TemplateVariables   ProxyEnvFile
  (param)            (backends,   (no default      NAMESPACE           EDL_NAMESPACE
                      collector)   namespace args)  → ${..} __..__ $..  → env.sh
                              │
                 DefaultK8sService.owned (decorators on applyResource, createJob, createConfigMap):
                 adds managed-by + cluster labels to every created object
```

- **Kotlin applies.** An object is created in `scope.namespace`, and the decorator labels it on the way out. Cluster-scoped objects are named with `ClusterScopedName.of(base, clusterId)`.
- **Kit objects** are created in `$NAMESPACE`. Kotlin does not label them. AC1 accepts objects that are in the namespace, and Child A Stage 6 already says kit objects leave through the kit's own `stop`.
- **`down` on a brought cluster** uses an `OwnedObjectSweep` that deletes by the cluster label, with a fixed list of kinds it never deletes (D9).

## Goals / Non-Goals

**Goals:**
- One namespace for everything easy-db-lab installs, recorded in the workspace; `default` on AWS and local, `easy-db-lab` on a cluster the user brings.
- Kits receive it as `NAMESPACE`, and every kit template and script uses it, Service DNS names included.
- Every object Kotlin applies, and every cluster-scoped object easy-db-lab creates, carries the managed-by and cluster labels; cluster-scoped names carry the cluster id.
- The CRDs present at `init --kubeconfig` are recorded.
- A sweep that removes only labelled objects and never PVs, PVCs, CRDs, StorageClasses, the namespace or data.
- No literal `default` namespace in `src/main`, enforced by a test.
- AWS lands in `default` as today.

**Non-Goals:**
- More than one easy-db-lab cluster per Kubernetes cluster (v1 runs one; owner default 2026-11-11). This change only removes the hard-coding that blocks it later.
- Namespaced installs without cluster-admin.
- Wiring the sweep into `down` (Child B).
- Deleting CRDs easy-db-lab added.
- A Helm post-renderer to label chart objects.
- Changing the `kubectl` wrapper's default namespace.

## Decisions

### D0. Delivery: three PRs on `local-k8s-edbl`

Approved by the owner, 2026-11-20.

- PR 1, the Kotlin foundation: `K8sNamespace`, `InstallScope`, `OwnershipLabels`, `ClusterScopedName`; `init --namespace` and the contract resolution; `ServiceDns`; every builder and K8s service taking the scope, with the constants and defaults removed; `OwnedManifestOperations`, labelled PVs, `NamespaceEnsurer`, `NAMESPACE` in `cluster-config`; the guard test over `src/main/kotlin`. The Kubeconfig default stays at Child A's `default`. AWS behaviour is unchanged.
- PR 2, kits and shell: `NAMESPACE` in `TemplateVariables`; nullable runtime and step namespaces; every kit file, `edl-kit-lib.sh`, `env.sh` with `EDL_NAMESPACE`, the ClickHouse XML (or its removal if dead); the ClickHouse operator out of `kube-system`; the guard extended to `src/main/resources`; the D10 flip; the kit integration test.
- PR 3, brought-cluster ownership: `CrdBaseline` at `init --kubeconfig`; `OwnedObjectSweep` with its K3s integration test; the spec and doc updates for removal. Child B consumes the sweep.
- Implementation starts only after #1231 (round-4 main merge) and #1227 (#1102 error handler) merge.

Rejected: one PR. It would conflict with both open PRs at once and be very large to review.

### D1. Where the namespace comes from

Approved by the owner, 2026-11-20.

- Add `init --namespace NAME`, recorded as `InitConfig.namespace: String? = null`.
- `ClusterContract.of` resolves `recorded ?: <environment default>`: `default` on EC2 and local, `easy-db-lab` on a brought cluster. The contract stays the only place that branches on the environment, so `EnvironmentBranchingTest` keeps holding.
- Validate at `init` like `--tenant` (`commands/Init.kt:678`): a DNS-1123 label of at most 63 characters; refuse `kube-system`, `kube-public` and `kube-node-lease`.
- Accept the flag in every environment. A K3s-in-Docker run and the TestContainers tests can then use a namespace other than `default`, at no extra cost.

Rejected:
- (a) Accept `--namespace` only with `--kubeconfig`. Stricter, but the non-default path could then be tested only through the brought-cluster harness.
- (b) Write the resolved value into state at `init`, never null. More explicit in `state.json`, but the default would live in `Init` as well as the contract: two places encoding one rule.

### D2. How the namespace reaches Kotlin

Approved by the owner, 2026-11-20.

A value type, `InstallScope(namespace: K8sNamespace, clusterId: String)`, where `K8sNamespace` is a `@JvmInline value class` that validates in `init {}`. It is built from the contract and the state and passed explicitly:
- through `ObservabilityDeployInputs.Reading` into `ObservabilityStageManifests.resourcesOf(stage, state, hosting, scope)`;
- from there into each builder's `buildAllResources(..., scope)`.

The `default` parameter values on `K8sService` and `K8sReadOperations`, the `TelemetrySenders` and `BackendWorkloads` constructor defaults, `Constants.K8s.NAMESPACE`, `Constants.Stress.NAMESPACE` and the 12 private constants are deleted, not repointed. The compiler then finds every caller, which no grep can do. `ContractDefaults.OWNED_NAMESPACE` becomes the single literal, and the guard allowlists it.

Rejected:
- (a) Set the Fabric8 client's default namespace in `KubernetesClientFactory` from the contract. Fewer edits, but the namespace becomes implicit: a builder that sets no namespace lands silently, and Service DNS names and RBAC subject namespaces still need the value. Two sources of truth.
- (b) Give builders a constructor dependency on `ClusterContractSource`. Works with Koin factories, but hides an input of a pure builder and makes the builder tests need a contract.
- (c) Have builders leave the namespace out and let a post-pass set `metadata.namespace`. Does not cover references inside objects: RoleBinding subjects, the scrape job namespace lists (`NodeScrapeJobs.kt:77`), and DNS names inside ConfigMaps.

### D3. Service DNS names

Approved by the owner, 2026-11-20.

- The `BackendEndpoints` and `OtelEndpoints` objects become one class, `ServiceDns(namespace)`, with the same methods. They keep the short `.svc` form their KDoc requires. There are 10 caller files: Grafana datasources, collector exporters, Tempo, Alloy, sidecar, stress.
- Kit templates use `__OTEL_*__`, and kits add `<svc>.${NAMESPACE}.svc`.
- Every hand-written `.default.svc.cluster.local` in a kit becomes `<svc>.${NAMESPACE}.svc` (for example `ferrosa`, `ignite3`, `cassandra-pods` seeds, `kafka-kafka-bootstrap`, and the presto and trino catalogs). The existing `BuiltinKitCollectorEndpointTest` style already rejects `svc.cluster.local`.

Rejected: keep `.svc.cluster.local`. It is safe only where the DNS domain is `cluster.local`, which the code's own comments already avoid assuming.

### D4. How the namespace reaches kits

Approved by the owner, 2026-11-20.

- Add `"NAMESPACE"` to `TemplateVariables` and `RESERVED_NAMES`, and nothing else.
- `KitRuntime.namespace` and the `namespace` fields on `InstallStep` become `String? = null`, where null means the cluster namespace. `WorkloadStepExecutor` resolves `step.namespace?.let(::interp) ?: scope.namespace`.
- `namespace: default` lines are deleted from `kit.yaml`. Explicit operator namespaces stay (D7).
- Shell steps use `"${NAMESPACE}"`.
- `edl-kit-lib.sh` fails fast on a missing value with `: "${NAMESPACE:?NAMESPACE is not set}"`, and stays bash-3.2 safe.
- Strimzi gets `watchNamespaces: "{${NAMESPACE}}"`.

Rejected:
- (a) Name it `EDL_NAMESPACE`, matching `EDL_KIT_LIB` and `EDL_NODE_SSH`. Avoids clashing with a user's shell variable, but the issue and the design doc both say `NAMESPACE`, next to `KIT_NAME` and `TENANT`.
- (b) Provide both names. Two names for one value.

### D5. Namespace for `env.sh` and other non-kit shells

Approved by the owner, 2026-11-20.

Add `EDL_NAMESPACE` to `<workspace>/.socks5-proxy.env` through `ProxyEnvFile`, written at `init` and `up`. This follows the standing rule that shell-side state comes only from that sourceable file. The `clickhouse-client` helpers in `env.sh` read it.

The `kubectl` wrapper does **not** add `-n`. A wrapper that silently changes the default namespace would surprise anyone typing `kubectl get pods`.

Rejected: hard-code `default` in the AWS-only `env.sh` helpers and exempt them from the guard. Simpler, but it leaves a literal the guard must allowlist.

### D6. The labelling scheme and where it is applied

Approved by the owner, 2026-11-20.

- Every object Kotlin applies, namespaced or cluster-scoped, gets `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>` on its top-level `metadata.labels`, and only there.
- The labels are added at one seam, `DefaultK8sService.owned`, which wraps each typed create path in a decorator: `OwnedManifestOperations` (`applyResource`), `OwnedJobOperations` (`createJob(namespace, Job)`) and `OwnedStorageOperations` (`createConfigMap`, merging the labels over the caller's). Each gets `InstallScope` per call from a `ClusterContractSource`-backed provider; Koin binds `K8sService` to `owned`. The seam covers the stack deploy, the Grafana deploy, the collector resync, the sidecar, stress jobs and `cluster-config`. `ObservabilityStackService.kt:312` drops its literal label.
- Labels never go into `spec.selector`, which is immutable and would force a delete and recreate.
- Labels never go into the pod template. `ConfigHashAnnotator` hashes the pod template, so a label there would roll every workload.
- Kit PVs (`DefaultK8sStorageOperations.kt:186`) and the namespace easy-db-lab creates are created outside `applyResource`, so they are labelled explicitly.
- `OwnershipLabels` is shared with EKS Child A Stage 2. #1094 owns the mechanism; Stage 2 adds only its foreign-object refusal and the narrowed wait. Whichever lands first defines `OwnershipLabels`, and the other reuses it.

Rejected:
- (a) Label only cluster-scoped objects, as the issue's literal scope says. Not enough for Child A's Stage 2 or for a sweep of namespaced objects in a shared `default`.
- (b) Add labels in each builder. Twelve places to forget; the decorator gives the same result once.

### D7. Operator charts and the first acceptance criterion

Approved by the owner, 2026-11-20.

The CNPG, Strimzi, TiDB and ClickHouse charts create ClusterRoles, webhooks and CRDs that carry `app.kubernetes.io/managed-by=Helm`. Setting `managed-by=easy-db-lab` on them needs a Helm post-renderer, which needs a YAML tool or the CLI on the control node under `ToolingForm.Ssh`; neither is portable.

- An easy-db-lab-installed Helm release is the ownership unit for chart objects. AC1 is amended to read "...or carries the managed-by and cluster labels, **or belongs to a Helm release easy-db-lab installed** (`meta.helm.sh/release-name`)".
- Operators stay in their own namespaces in v1 (`cnpg-system`, `strimzi`, `tidb-admin`), labelled when the `namespace` step creates them.
- The ClickHouse operator moves out of `kube-system` (`kits/clickhouse/kit.yaml:92`), the one install into a namespace that is not ours, into its own namespace, in PR 2.
- The CRDs present at `init --kubeconfig` are recorded in state as `InitConfig.preexistingCrds: List<String>?`, next to the capability probe, which already creates namespaces there.

Rejected:
- (a) Move every operator into `${NAMESPACE}`, watching its own namespace. "Exactly what we created" gets cleaner, but on AWS the operators would move out of their current namespaces, against "AWS lands as today", and it touches four kits' operator values.
- (b) Write a post-renderer. Not portable.
- (c) Leave the ClickHouse operator in `kube-system`.

### D8. Naming cluster-scoped objects

Approved by the owner, 2026-11-20.

`ClusterScopedName.of(base, clusterId) = "$base-$clusterId"`, using the full cluster id, for example `otel-collector-3f2a…` (51 characters). It applies in every environment, with no branch. The ClusterRoleBinding `roleRef` and its subject (`namespace = scope.namespace`) are built from the same values.

On AWS this renames the ClusterRoles once. An already running cluster brought `up` again leaves an orphan, unused ClusterRole behind, which is harmless on an ephemeral cluster.

Rejected:
- (a) The first 8 hex characters. Readable, with a small chance of collision in multi-install, which is out of scope for v1.
- (b) Suffix with the namespace (`otel-collector-easy-db-lab`). Readable, but unique only while one install uses one namespace.

### D9. What `down` removes on a brought cluster, and who wires it in

Approved by the owner, 2026-11-20.

- #1094 delivers `OwnedObjectSweep` (`services/tenancy/`). It lists objects by `easydblab/cluster=<id>`: namespaced kinds in `scope.namespace`, cluster-scoped kinds cluster-wide, found through API discovery. It deletes them in dependency order: workloads, then RBAC and Services, then ConfigMaps.
- It never deletes PersistentVolumeClaims, PersistentVolumes, CRDs, StorageClasses or the namespace. PVCs and PVs hold data, which the no-delete rule protects, and the namespace holds the PVCs.
- Child B wires it into `down` after the flush. Today `down` refuses a Kubeconfig workspace with `ProvisionerUnavailable`; that is unchanged here.
- CRDs are never deleted in v1. The recorded `preexistingCrds` satisfies "never deletes pre-existing CRDs" and enables a later explicit command.

Rejected:
- (a) Wire the sweep into `down` here. Collides with Child B's teardown work (`TeardownFlushService` without a control host).
- (b) Delete the CRDs we added. Deleting a CRD deletes every custom resource of that kind cluster-wide, too sharp an edge for a tool that runs one install per cluster.

### D10. When the brought cluster's default goes back to `easy-db-lab`

Approved by the owner, 2026-11-20.

Child A D4 sets the Kubeconfig default to `default` until #1094. It flips back to `BROUGHT_NAMESPACE` in PR 2, the PR that converts the kits. If the stack moved to `easy-db-lab` while the kits still wrote `default`, telemetry DNS and the runtime probes would split across two namespaces.

Rejected: flip it in PR 1, accepting broken kits on brought clusters between the two merges.

### D11. An existing namespace the user named

Approved by the owner, 2026-11-20.

`NamespaceEnsurer` creates the namespace with the labels when it is absent (`default` always exists). When the namespace exists, it adopts it and adds the labels: the user named it. `up` calls it before the stack applies. `CapabilityProbeService` reuses it in place of its inline `NamespaceBuilder` (`CapabilityProbeService.kt:119`).

Rejected: refuse an existing namespace that has no label.

### Structure and boundaries

Each item has one responsibility.

1. `configuration/tenancy/K8sNamespace`: a value class with the DNS-1123 check and the refusal of `kube-*` names. The only place that rule lives; `Init` uses it.
2. `configuration/tenancy/InstallScope`: `namespace` and `clusterId`, built by `InstallScope.of(state, contract)`. Pure.
3. `configuration/tenancy/OwnershipLabels`: `of(clusterId): Map<String,String>`, `selector(clusterId)`, and `ClusterScopedName.of(base, clusterId)`. Replaces the literal at `ObservabilityStackService.kt:312` and is reused by `KitLocalStorageClass`.
4. `configuration/ServiceDns(namespace)`: replaces the `BackendEndpoints` and `OtelEndpoints` objects.
5. `services/OwnedManifestOperations`, `OwnedJobOperations`, `OwnedStorageOperations`: decorators over `K8sManifestOperations`, `K8sJobOperations` and `K8sStorageOperations`, composed by `DefaultK8sService.owned`; the only place labels are added to created objects. Each depends on its interface, not on a client.
6. `services/tenancy/NamespaceEnsurer`: creates or adopts and labels the namespace (D11).
7. `services/tenancy/OwnedObjectSweep`: list and delete by label, with the never-delete kinds (D9).
8. `services/tenancy/CrdBaseline`: lists CRD names at `init --kubeconfig` and writes them to state.

### Data model and interfaces

- `state.json`, `InitConfig`: `namespace: String? = null`, `preexistingCrds: List<String>? = null`. kotlinx.serialization defaults; no migration, because clusters are ephemeral.
- CLI: `init --namespace NAME`. Help text: "Kubernetes namespace easy-db-lab installs into (default: `default` on AWS and local, `easy-db-lab` on your cluster)".
- Kit contract: reserved variable `NAMESPACE`; `runtime.namespace` and step `namespace:` optional, meaning the cluster namespace when absent; `edl-kit-lib.sh` requires `NAMESPACE`.
- Signatures: `ObservabilityStageManifests.resourcesOf(stage, state, hosting, scope: InstallScope)`; each builder's `buildAllResources(..., scope: InstallScope)`; `K8sService` read and write methods take `namespace: String` with no default.
- `cluster-config` ConfigMap: add `NAMESPACE`, next to `TENANT`, so in-cluster scripts can read it.
- Docs: `docs/` kit development (`NAMESPACE`), `docs/reference/commands.md`, `help/observability.md:14`, `configuration/CLAUDE.md:184,198,203,321` (they name `<backend>.default.svc` and `namespaces/default`), `kits/CLAUDE.md`.

### Test strategy

- **Guard test, `architecture/NoDefaultNamespaceLiteralTest`** (unit tier). Scans `src/main/kotlin` (PR 1) and `src/main/resources`, dashboards included (PR 2), for namespace-shaped patterns: `namespace: default`, `-n default`, `--namespace default`, `--namespace=default`, `.default.svc`, `namespaces/default/`, `{default}`, `withNamespace("default")`, `inNamespace("default")`, `NAMESPACE = "default"`, `namespace: String = "default"`. Exactly one hit is allowlisted: `ContractDefaults.OWNED_NAMESPACE`. It does not match other uses of `default`: the ServiceAccount (`CapabilityProbeService.kt:183`, `ObservabilityHosting.kt:234`), the AWS profile, the tenant, the ClickHouse database or storage policy named `default`, or `default:` in kit arguments. It must be shown to fail on a planted file under `architecture/fixtures`, as the existing boundary tests are.
- **Builder render test** (unit tier). Renders every stage with real builders and a real `TemplateService` at `InstallScope("edl-ns", id)`. Asserts every namespaced object's `metadata.namespace == "edl-ns"`, every RoleBinding and ClusterRoleBinding subject namespace is `edl-ns`, every cluster-scoped name ends in `-<id>`, and no rendered ConfigMap data contains `.default.svc`. The same test at the EC2 scope covers AC3.
- **Decorator test** (integration tier). Applies through `OwnedManifestOperations` over a real `DefaultK8sManifestOperations` on `SharedK3s`. Asserts the labels are on the live object, the selector and pod template are unchanged, and re-applying a Deployment does not change its pod-template hash.
- **AC1 integration test** (`src/integrationTest`). Its own K3s, not `SharedK3s`, because it creates cluster-scoped objects. Snapshots every API-discoverable object, runs the stack deploy plus the `cassandra-pods` kit `start` with namespace `easy-db-lab`, and diffs. Every new object must be in `easy-db-lab`, or carry both labels, or belong to an easy-db-lab-installed Helm release.
- **AC3.** `ClusterContractTest`: Ec2K3s with no recorded namespace resolves to `default`; a recorded namespace wins; Kubeconfig resolves to `easy-db-lab` after the D10 flip. Plus the builder render test at the EC2 scope.
- **Kits** (PR 2). Run `PostgresSysbenchAnywhereIntegrationTest` with namespace `easy-db-lab`; it covers the operator and Helm path and the `edl-kit-lib.sh` helpers. A `TemplateVariablesTest` case checks `RESERVED_NAMES` still equals the keys. A render test covers the Strimzi `watchNamespaces` interpolation.
- **Sweep** (PR 3). K3s integration test: plant a labelled Deployment, an unlabelled Deployment in the same namespace, a labelled PVC and a labelled PV, a CRD recorded in `preexistingCrds`, and a foreign ClusterRole. After the sweep, only the labelled Deployment and RBAC are gone.
- Gradle runs in a subagent, per the repo rule. `detekt` and `check` run on JDK 21.

## Risks / Trade-offs

- **#1227 is a high risk for PR 1.** It edits `ObservabilityStackService`, `BackendWorkloads`, `CapabilityProbeService`, `DefaultK8sNamespaceOperations`, `TelemetrySenders`, `StressJobService`, all stress commands, `GrafanaUpdateConfig`, `PlatformCreatePvs`, `configuration/CLAUDE.md` and `kubernetes/CLAUDE.md`, the files where the namespace is threaded through. Mitigation: start after it merges (D0).
- **#1231 is a high risk for PR 2, medium for PR 1.** It edits `Constants.kt`, `Init.kt`, `ClusterState.kt`, `ClusterContract.kt`, `KitConfig.kt`, `KitProcessEnvironment.kt` and `kits/CLAUDE.md`, and adds the `cqlite-flight` and `trino-loadtest` kits and `trino` template edits, which add new `default` literals to convert. Mitigation: start after it merges; the guard test catches any literal it brings.
- **Child A coordination.** Child A D4 and Stage 2 overlap D6 and D10. Whichever lands first defines `OwnershipLabels`. The decorator holds no extra client per object, which Child A's EKS exec-auth risk requires: one `applyResource` client per object stays as it is.
- **Size.** About 28 main Kotlin files use the constant; 12 builders, about 14 default parameters, about 40 kit files (about 167 lines), two shared shell resources, and about 109 test files. Large but shallow; most test edits are mechanical once the defaults go.
- **Selector and pod-template mistakes.** A label in a selector forces a delete and recreate through `requiresDeleteAndRecreate`; a label in a pod template rolls every workload. The decorator touches `metadata.labels` only, and a test pins it.
- **Renamed ClusterRoles.** An orphan from before the rename stays on clusters that are already up. Harmless on an ephemeral cluster.
- **Missing `NAMESPACE` in a kit script.** The `:?` guard fails fast, which is better than silently targeting `default`.
- **Strimzi watch namespace.** If `{${NAMESPACE}}` is not interpolated, Kafka never reconciles. A render test covers it.
- **`edl_push_gauges` proxy path** (`namespaces/<ns>/pods/...`). The pod lookup and the URL must use the same variable.
- **REPL and server.** They resolve the scope per command through `ClusterContractSource`, so an `init --namespace` inside the REPL takes effect on the next command.
- **Guard false positives.** `default` is also a ServiceAccount, an AWS profile, a tenant, a ClickHouse database and storage policy, and a kit-arg key. The guard matches namespace-shaped patterns only, and the planted-fixture test shows it still fails on a real one.
- **ClickHouse operator move.** Moving it out of `kube-system` changes where its pods run on AWS too. This is the one intended AWS namespace difference beyond labels and ClusterRole names.
