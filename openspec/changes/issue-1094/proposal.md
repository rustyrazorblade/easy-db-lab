## Why

The Kubernetes namespace `default` is written into the code in many places: three global constants (`Constants.K8s.NAMESPACE`, `Constants.Stress.NAMESPACE`, `ContractDefaults.OWNED_NAMESPACE`), 12 private builder constants, about 14 `namespace = "default"` parameter defaults, and about 167 lines in kit files (`namespace: default`, `-n default`, `.default.svc.cluster.local`). `ClusterContract.namespace` already exists, but no production code reads it. Cluster-scoped objects have fixed names (the ClusterRoles `otel-collector`, `pyroscope-ebpf` and kube-state-metrics'), and almost nothing easy-db-lab creates carries a label that says easy-db-lab created it.

On a cluster the user brings (`init --kubeconfig`, epic #1032 phase A), easy-db-lab must install into its own namespace, and it must be able to find and remove exactly what it created, and nothing else. This change removes the hard-coding that blocks that. It does not add more than one easy-db-lab cluster per Kubernetes cluster.

## What Changes

- **`init --namespace NAME`** in every environment, recorded in the workspace as `InitConfig.namespace` (nullable). The cluster contract resolves the recorded value, else `default` on EC2 and local, and `easy-db-lab` on a cluster the user brings. The name must be a DNS-1123 label of at most 63 characters; `kube-system`, `kube-public` and `kube-node-lease` are refused.
- **One install scope.** Every Kotlin manifest builder, Fabric8 call and K8s service gets the namespace and the cluster id as an explicit `InstallScope(namespace, clusterId)` parameter. Every `namespace = "default"` default and the namespace constants are deleted.
- **Service DNS.** `ServiceDns(namespace)` replaces `BackendEndpoints` and `OtelEndpoints`, with short `<svc>.<namespace>.svc` names. Every kit's hand-written `.default.svc.cluster.local` becomes `<svc>.${NAMESPACE}.svc`.
- **Kits get `NAMESPACE`** as a reserved template variable (`${NAMESPACE}` in `kit.yaml`, `__NAMESPACE__` in templates, `$NAMESPACE` in scripts). `runtime.namespace` and a step's `namespace` become optional and mean the cluster namespace when absent. `edl-kit-lib.sh` fails fast when `NAMESPACE` is not set.
- **Shell tools** read `EDL_NAMESPACE` from `.socks5-proxy.env`. The `kubectl` wrapper adds no `-n`.
- **Ownership labels.** Every object Kotlin applies carries `app.kubernetes.io/managed-by=easy-db-lab` and `easydblab/cluster=<clusterId>` on its top-level metadata, never on a selector or a pod template. Kit PVs and the namespace easy-db-lab creates are labelled too. An existing namespace the user named is adopted and labelled.
- **Cluster-scoped names** get the full cluster id as a suffix (`<base>-<clusterId>`).
- **Operator charts.** Operators stay in their own namespaces, labelled when created. An object counts as easy-db-lab's when it belongs to a Helm release easy-db-lab installed. The ClickHouse operator moves out of `kube-system` into its own namespace.
- **CRD baseline.** `init --kubeconfig` records the CRDs already present as `InitConfig.preexistingCrds`.
- **Owned-object sweep.** A service that deletes by the cluster label and never deletes PVCs, PVs, CRDs, StorageClasses or the namespace. Wiring it into `down` belongs to Child B and is not part of this change.
- **Guard.** A unit test fails on any literal `default` namespace in `src/main`.
- **On AWS** everything still lands in `default`. The visible differences are the added labels and the suffixed ClusterRole names.
- **Amended acceptance criterion.** AC1 also accepts an object that belongs to a Helm release easy-db-lab installed (owner decision D7).

Delivered in three PRs on `local-k8s-edbl`, after #1231 and #1227 merge: the Kotlin foundation, then the kits and shell with the brought-cluster default flip, then the CRD baseline and the sweep.

## Capabilities

### New Capabilities

- `tenancy`: the cluster namespace (resolution, validation, creation and adoption), one namespace for everything easy-db-lab installs, kits' `NAMESPACE`, Service DNS names in the namespace, what counts as an easy-db-lab object, operator namespaces, the CRD baseline, the owned-object sweep, the no-literal guard, and AWS landing in `default`.

### Modified Capabilities

- `cluster-lifecycle`: `init --namespace` records and validates the namespace.
- `kubernetes-api-access`: every object the CLI applies carries the ownership labels; cluster-scoped objects are named with the cluster id.
- `workload-install-config`: `runtime.namespace` defaults to the cluster namespace, not `default`.
- `dynamic-otel-scrape-config`: the metrics registry is read in the cluster namespace.
- `clickhouse-benchmark-kit`: the runtime probe uses the cluster namespace.
- `kit-shell-library`: the library requires `NAMESPACE` and uses it for every cluster call.
- `networking`: the proxy env file also holds `EDL_NAMESPACE`.
- `kafka-kit`, `postgres`, `presto`, `telemetry-redirect`, `workload-status-command`: their requirements name `default.svc.cluster.local` or `-n default`; they now name the cluster namespace.

## Impact

- Code (PR 1): new `configuration/tenancy/` (`K8sNamespace`, `InstallScope`, `OwnershipLabels`, `ClusterScopedName`), `configuration/ServiceDns`, `services/OwnedManifestOperations`, `services/tenancy/NamespaceEnsurer`; `commands/Init.kt`, `ClusterState.kt` (`InitConfig.namespace`), `ClusterContract.kt`; every observability manifest builder, `ObservabilityStageManifests`, `ObservabilityStackService`, `GrafanaDeployService`, `TelemetrySenders`, `BackendWorkloads`, `SidecarService`, `StressJobService`, `MetricsRegistryService`, `OtelSyncService`, `K8sService`, `K8sReadOperations`, `DefaultK8sStorageOperations`, `CapabilityProbeService`; `Constants.kt`.
- Code (PR 2): `TemplateVariables`, `KitConfig`, `InstallStep`, `WorkloadStepExecutor`, `KitWorkloadProbe`, `KitEndpointServices`, `KitStatusCommand`, `ProxyEnvFile`; about 40 kit files; `configuration/edl-kit-lib.sh`, `configuration/env.sh`, the ClickHouse XML resources, `help/observability.md`.
- Code (PR 3): `services/tenancy/CrdBaseline`, `services/tenancy/OwnedObjectSweep`; `InitConfig.preexistingCrds`.
- Tests: about 109 test files name `"default"` or `K8s.NAMESPACE`; most change mechanically once the defaults go. New: the guard test, the builder render test, the decorator integration test, the AC1 integration test, the sweep integration test.
- Docs: `docs/` kit development (`NAMESPACE`), `docs/reference/commands.md` (`init --namespace`), `help/observability.md`, `configuration/CLAUDE.md`, `kits/CLAUDE.md`, `kubernetes/CLAUDE.md`.
- Coordination: EKS Child A Stage 2 reuses `OwnershipLabels`; Child B wires the sweep into `down`.
