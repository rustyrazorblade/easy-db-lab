## 1. Cluster address and client loading

- [ ] 1.1 Add `KubeApi(kubeconfig: Path, context: String)` and the `KubeApiLocator` interface (with class-level KDoc)
- [ ] 1.2 Add `ClusterState.kubeContext: String? = null`
- [ ] 1.3 Implement `WorkspaceKubeApiLocator`: path from `<workspace>/kubeconfig`, context from `ClusterState.kubeContext`, falling back to the kubeconfig's `current-context`; fail fast when the kubeconfig is missing
- [ ] 1.4 Change `KubernetesClientFactory.createClient` to take `KubeApi` and load with `Config.fromKubeconfig(context, file)`; fail fast naming the context and path when the context is absent; keep the per-client `httpsProxy` and `applyClusterClientTimeouts()` unchanged
- [ ] 1.5 Change `K8sClientProvider` to `createClient()` with an injected locator and factory; register both in Koin
- [ ] 1.6 Unit tests: relative certificate path resolves against the kubeconfig directory; recorded context wins over `current-context`; no recorded context falls back to `current-context`; missing context fails with its name; a `KUBECONFIG` env var pointing elsewhere is ignored; proxy port published sets `socks5://127.0.0.1:<port>` and absent sets none

## 2. Record the context on provisioning

- [ ] 2.1 When `up` writes the workspace kubeconfig (`K3sService.downloadAndConfigureKubeconfig`), record its `current-context` as `ClusterState.kubeContext`
- [ ] 2.2 Test that provisioning records the context in cluster state

## 3. Remove `controlHost` from K8s services

- [ ] 3.1 Drop `controlHost` from `K8sService` and the `DefaultK8s*Operations` classes
- [ ] 3.2 Drop `controlHost` from `StressJobService`, `BackendWorkloads`, `OtelSyncService`, `TelemetrySenders`, `MetricsRegistryService`, `ConfigChangeReport` and the other pass-through classes
- [ ] 3.3 In the allow-listed classes (`TempoTailFlush`, `CiliumService`, `CiliumInspectionService`, `K3sService`, `K3sClusterService`, `GrafanaDashboardTreeUploader`, `GrafanaClient`, `ObservabilityHttp`, `RoutedObservabilityHttp`, `SidecarService`, `DashboardDefaults`), stop passing the host to K8s calls; keep it only for SSH or host-IP use
- [ ] 3.4 Update callers (`Up`, `KitRunnerCommand`, and the rest the compiler flags)
- [ ] 3.5 Update unit and integration tests for the new signatures

## 4. Architecture rule

- [ ] 4.1 Add an ArchUnit test beside `PackageBoundaryTest`: no `services` class depending on `KubernetesClient` or `K8sClientProvider` has a method or constructor taking `ClusterHost`, except a named allow-list
- [ ] 4.2 Make the test fail on a stale allow-list entry (class no longer takes `ClusterHost` or no longer depends on the client)
- [ ] 4.3 Add fixtures proving the condition catches a violation and a stale entry

## 5. Verification and docs

- [ ] 5.1 `K8sServiceIntegrationTest` (K3s TestContainer) passes, showing AWS-path client behaviour is unchanged
- [ ] 5.2 `./gradlew test`, `./gradlew integrationTest`, `ktlintCheck` and `detekt` pass (detekt on JDK 21, no new suppressions)
- [ ] 5.3 Update `kubernetes/CLAUDE.md` (client creation through `KubeApi`, load by path with explicit context, never `autoConfigure`) and the root `CLAUDE.md` references to `K8sClientProvider`
