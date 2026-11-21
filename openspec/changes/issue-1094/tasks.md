Start only after #1231 (round-4 main merge) and #1227 (#1102 error handler) merge into `local-k8s-edbl`. Each PR branches from `local-k8s-edbl` and lands there, in this order.

## 1. PR 1: Kotlin foundation (AWS behaviour unchanged; Kubeconfig default stays `default`)

- [x] 1.1 `configuration/tenancy/K8sNamespace`: `@JvmInline value class` that validates a DNS-1123 label of at most 63 characters and refuses `kube-system`, `kube-public`, `kube-node-lease`. `K8sNamespaceTest`: valid names, upper case, `_`, leading or trailing `-`, 63 and 64 characters, each reserved name
- [x] 1.2 `configuration/tenancy/InstallScope(namespace, clusterId)` with `InstallScope.of(state, contract)`; `OwnershipLabels.of(clusterId)` and `selector(clusterId)`; `ClusterScopedName.of(base, clusterId)`. Define `OwnershipLabels` once and agree with EKS Child A Stage 2 that it reuses it (or reuse Child A's if it landed first)
- [x] 1.3 `InitConfig.namespace: String? = null`; `init --namespace NAME` in every environment, validated through `K8sNamespace` before anything is written; help text names both defaults. `InitTest`: recorded, absent, invalid, too long, `kube-system`, accepted with `--local`
- [x] 1.4 `ClusterContract.of` resolves `recorded ?: environment default`; `ContractDefaults.OWNED_NAMESPACE` becomes the only `default` literal. `ClusterContractTest`: Ec2K3s and K3sDocker with none resolve to `default`; a recorded namespace wins; Kubeconfig keeps Child A's `default` in this PR; `init --namespace` inside the REPL takes effect on the next command
- [ ] 1.5 `configuration/ServiceDns(namespace)` replaces `BackendEndpoints` and `OtelEndpoints` (same methods, short `.svc` form); move the 10 caller files (Grafana datasources, collector exporters, Tempo, Alloy, sidecar, stress)
- [ ] 1.6 Thread `InstallScope` through `ObservabilityDeployInputs.Reading`, `ObservabilityStageManifests.resourcesOf(stage, state, hosting, scope)` and every builder's `buildAllResources(..., scope)`, including RoleBinding and ClusterRoleBinding subjects and `NodeScrapeJobs` namespace lists
- [ ] 1.7 Name every cluster-scoped object with `ClusterScopedName.of` (`OtelManifestBuilder`, `OtelHostCollectorManifestBuilder`, `PyroscopeManifestBuilder`, `KubeStateMetricsManifestBuilder`); `roleRef` and subjects from the same values
- [ ] 1.8 Delete `Constants.K8s.NAMESPACE`, `Constants.Stress.NAMESPACE`, the 12 private `NAMESPACE = "default"` constants, and every `namespace = "default"` default (`K8sService`, `K8sReadOperations`, `DefaultK8sReadOperations`, `TelemetrySenders`, `BackendWorkloads`, `PersistentVolumeConfig`); pass the scope's namespace at every caller the compiler names (`SidecarService`, `StressJobService` and the stress commands, `MetricsRegistryService`, `OtelSyncService`, `GrafanaDeployService`, `ObservabilityStackService`, `GrafanaUpdateConfig`, `PlatformCreatePvs`)
- [ ] 1.9 `services/OwnedManifestOperations`: decorator over `K8sManifestOperations` that adds both labels to top-level `metadata.labels` only, keeping existing labels; scope per call from a `ClusterContractSource`-backed provider; register in Koin in place of the bare default; drop the literal at `ObservabilityStackService.kt:312`. Unit test: selector and pod template untouched, existing labels kept
- [ ] 1.10 Decorator integration test on `SharedK3s` over a real `DefaultK8sManifestOperations`: labels on the live object; selector and pod template unchanged; re-apply leaves the pod-template hash unchanged
- [ ] 1.11 Label kit PVs in `DefaultK8sStorageOperations`; `KitLocalStorageClass` uses `OwnershipLabels`
- [ ] 1.12 `services/tenancy/NamespaceEnsurer`: create with labels when absent, adopt and label when present (`default` included); `up` calls it before the stack applies; `CapabilityProbeService` uses it in place of its inline `NamespaceBuilder`. K3s integration test: absent namespace created labelled; existing unlabelled namespace adopted, other labels kept
- [ ] 1.13 `cluster-config` holds `NAMESPACE` next to `TENANT`
- [ ] 1.14 Builder render test (unit tier, real builders and real `TemplateService`): at `InstallScope("edl-ns", id)` every namespaced object is in `edl-ns`, every binding subject names `edl-ns`, every cluster-scoped name ends in `-<id>`, no ConfigMap data contains `.default.svc`; at the EC2 scope with `default`, namespaces match today and the only differences are the labels and suffixes
- [ ] 1.15 `architecture/NoDefaultNamespaceLiteralTest` over `src/main/kotlin`: the namespace-shaped patterns in design "Test strategy", one allowlisted hit (`ContractDefaults.OWNED_NAMESPACE`); planted fixtures under `architecture/fixtures` show it fails on `-n default` and stays quiet on the `default` ServiceAccount and a kit arg's `default:`
- [ ] 1.16 Update the ~109 test files that named `"default"` or `K8s.NAMESPACE`
- [ ] 1.17 Docs: `docs/reference/commands.md` (`init --namespace`), `configuration/CLAUDE.md` (`<backend>.<namespace>.svc`, `namespaces/<namespace>`, labels, `ServiceDns`, the decorator), `kubernetes/CLAUDE.md`, root `CLAUDE.md` where it names `default`
- [ ] 1.18 `./gradlew check` on JDK 21 (in a subagent), detekt with no new suppressions

## 2. PR 2: kits, shell and the D10 flip

- [ ] 2.1 `TemplateVariables`: add `NAMESPACE` to the map and to `RESERVED_NAMES`. `TemplateVariablesTest`: `RESERVED_NAMES` equals the keys; a kit arg named `NAMESPACE` is refused
- [ ] 2.2 `KitRuntime.namespace` and the `InstallStep` namespace fields become `String? = null`; `WorkloadStepExecutor` resolves `step.namespace?.let(::interp) ?: scope.namespace`; `KitWorkloadProbe`, `KitEndpointServices`, `KitStatusCommand` take the cluster namespace. Tests: a step with no namespace acts in the cluster namespace; a runtime block with none probes the cluster namespace
- [ ] 2.3 Convert every built-in kit (about 40 files): delete `namespace: default`; `-n default` and `--namespace default` become `"${NAMESPACE}"`; every `.default.svc.cluster.local` becomes `<svc>.${NAMESPACE}.svc` (`cassandra-pods` seed, `ferrosa`, `ignite3`, presto and trino catalogs, `kafka` README); include the kits #1231 added (`cqlite-flight`, `trino-loadtest`)
- [ ] 2.4 `kafka`: `watchNamespaces: "{${NAMESPACE}}"`; render test that the value interpolates
- [ ] 2.5 `clickhouse`: move the operator from `kube-system` to its own namespace, created by a labelled `namespace` step; operator namespaces of `postgres`, `kafka`, `tidb` stay and are labelled when created
- [ ] 2.6 `configuration/edl-kit-lib.sh`: `: "${NAMESPACE:?NAMESPACE is not set}"` in each function that calls the cluster; gauge-push pod lookup and pod-proxy URL, Helm install, scale, uninstall and the `cluster-config` read use `$NAMESPACE`. Library shell tests under `/bin/bash` 3.2: lookup and URL use `lab-a`; Helm helpers pass `--namespace lab-a`; missing `NAMESPACE` exits non-zero with the message and no cluster call
- [ ] 2.7 `ProxyEnvFile` writes `EDL_NAMESPACE` at `init` and `up`; `env.sh` `clickhouse-client` helpers read it; the `kubectl` wrapper unchanged. Tests: the env file holds `EDL_NAMESPACE` and keeps the other keys; the wrapper adds no `-n`
- [ ] 2.8 `configuration/clickhouse/{config,keeper_config}.xml`: remove if no code reads them, else convert
- [ ] 2.9 `help/observability.md` and kit `METRICS.md`/README text use the cluster namespace
- [ ] 2.10 Extend `NoDefaultNamespaceLiteralTest` to `src/main/resources`, dashboards included
- [ ] 2.11 D10: the Kubeconfig contract default returns to `BROUGHT_NAMESPACE` (`easy-db-lab`). `ClusterContractTest`: Kubeconfig with none resolves to `easy-db-lab`
- [ ] 2.12 Run `PostgresSysbenchAnywhereIntegrationTest` with namespace `easy-db-lab`
- [ ] 2.13 AC1 integration test (own K3s, not `SharedK3s`): snapshot every API-discoverable object, run the stack deploy and the `cassandra-pods` kit `start` with namespace `easy-db-lab`, diff; every new object is in `easy-db-lab`, or carries both labels, or belongs to an easy-db-lab-installed Helm release
- [ ] 2.14 Docs: `docs/` kit development (`NAMESPACE`, optional step and runtime namespaces), `kits/CLAUDE.md`, the user guide pages for kafka, postgres, presto, trino
- [ ] 2.15 `./gradlew check` on JDK 21 (in a subagent); one AWS lab run of `up` and a kit `start` confirming everything lands in `default`

## 3. PR 3: CRD baseline and owned-object sweep

- [ ] 3.1 `InitConfig.preexistingCrds: List<String>? = null`; `services/tenancy/CrdBaseline` lists CRD names at `init --kubeconfig` and records them; nothing recorded on EC2 or local. K3s integration test with a planted CRD
- [ ] 3.2 `services/tenancy/OwnedObjectSweep`: list by `easydblab/cluster=<id>` through API discovery (namespaced kinds in the cluster namespace, cluster-scoped cluster-wide); delete workloads, then RBAC and Services, then ConfigMaps; never PVC, PV, CRD, StorageClass or the namespace; never an object without the label
- [ ] 3.3 Sweep integration test (own K3s): a labelled Deployment, an unlabelled Deployment in the same namespace, a labelled PVC, a labelled PV, a CRD recorded as pre-existing, a foreign ClusterRole, a labelled namespace and a labelled `edl-local-<kit>` StorageClass; after the sweep only the labelled Deployment and labelled RBAC are gone
- [ ] 3.4 Do not call the sweep from `down` (Child B wires it); hand Child B the entry point
- [ ] 3.5 Docs: `kubernetes/CLAUDE.md` and `docs/` on what the sweep removes and never removes
- [ ] 3.6 `./gradlew check` on JDK 21 (in a subagent)
