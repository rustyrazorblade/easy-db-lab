## 1. Cluster address and client loading

- [x] 1.1 Add `KubeApi(kubeconfig: Path, context: String)` and the `KubeApiLocator` interface (with class-level KDoc)
- [x] 1.2 Add `ClusterState.kubeContext: String? = null`
- [x] 1.3 Implement `WorkspaceKubeApiLocator`: path from `<workspace>/kubeconfig`, context from `ClusterState.kubeContext`, falling back to the kubeconfig's `current-context`; fail fast when the kubeconfig is missing
- [x] 1.4 Change `KubernetesClientFactory.createClient` to take `KubeApi` and load with `Config.fromKubeconfig(context, file)`; fail fast naming the context and path when the context is absent; keep the per-client `httpsProxy` and `applyClusterClientTimeouts()` unchanged
- [x] 1.5 Change `K8sClientProvider` to `createClient()` with an injected locator and factory; register both in Koin
- [x] 1.6 Unit tests: relative certificate path resolves against the kubeconfig directory; recorded context wins over `current-context`; no recorded context falls back to `current-context`; missing context fails with its name; a `KUBECONFIG` env var pointing elsewhere is ignored; proxy port published sets `socks5://127.0.0.1:<port>` and absent sets none

## 2. Record the context on provisioning

- [x] 2.1 When `up` writes the workspace kubeconfig (`K3sService.downloadAndConfigureKubeconfig` returns its `current-context`), record it as `ClusterState.kubeContext` on the state `up` holds and save that
- [x] 2.2 Test that provisioning records the context in cluster state

## 3. Remove `controlHost` from K8s services

- [x] 3.1 Drop `controlHost` from `K8sService` and the `DefaultK8s*Operations` classes
- [x] 3.2 Drop `controlHost` from `StressJobService`, `BackendWorkloads`, `OtelSyncService`, `TelemetrySenders`, `MetricsRegistryService`, `ConfigChangeReport` and the other pass-through classes
- [x] 3.3 In the allow-listed classes (`TempoTailFlush`, `CiliumService`, `CiliumInspectionService`, `K3sService`, `K3sClusterService`, `GrafanaDashboardTreeUploader`, `GrafanaClient`, `ObservabilityHttp`, `RoutedObservabilityHttp`, `SidecarService`, `DashboardDefaults`), stop passing the host to K8s calls; keep it only for SSH or host-IP use
- [x] 3.4 Update callers (`Up`, `KitRunnerCommand`, and the rest the compiler flags)
- [x] 3.5 Update unit and integration tests for the new signatures

## 4. Architecture rule

- [x] 4.1 Add an ArchUnit test beside `PackageBoundaryTest`: no `services` class depending on `KubernetesClient` or `K8sClientProvider` has a method or constructor taking `ClusterHost`, except a named allow-list
- [x] 4.2 Make the test fail on a stale allow-list entry (class no longer takes `ClusterHost` or no longer depends on the client)
- [x] 4.3 Add fixtures proving the condition catches a violation and a stale entry

## 5. Verification and docs

- [ ] 5.1 `K8sServiceIntegrationTest` (K3s TestContainer) passes, showing AWS-path client behaviour is unchanged
- [ ] 5.2 `./gradlew test`, `./gradlew integrationTest`, `ktlintCheck` and `detekt` pass (detekt on JDK 21, no new suppressions)
- [x] 5.3 Update `kubernetes/CLAUDE.md` (client creation through `KubeApi`, load by path with explicit context, never `autoConfigure`) and the root `CLAUDE.md` references to `K8sClientProvider`
