## Why

The Kubernetes services reach the cluster only through the workspace kubeconfig, yet 149 `controlHost: ClusterHost` parameters are threaded through 37 files (30 in `K8sService.kt`) to a client that uses the host in a single debug log line. Those parameters tie every cluster operation to a control node easy-db-lab owns, which blocks the Kubernetes-forward epic (#1032) from running kits and observability on a cluster the tool did not provision. Separately, `KubernetesClientFactory` loads the kubeconfig from its text (`Config.fromKubeconfig(content)`), dropping the file path and context, so relative certificate paths, exec auth plugins, and a chosen context do not work.

## What Changes

- The K8s services and `K8sClientProvider` take **no** `ClusterHost`. They reach the cluster only through the recorded kubeconfig and context. **BREAKING** to internal service signatures (acceptable — clusters are ephemeral, no migration path needed).
- A `KubeApi(kubeconfig: Path, context: String)` value carries the cluster address; a `KubeApiLocator` seam resolves it once per command from the workspace (the seam #1040's `Environment` later implements, with no compile-time dependency on #1040).
- `KubernetesClientFactory` loads the kubeconfig **by path with an explicit context** (`Config.fromKubeconfig(context, file)`), so relative certificate paths and exec auth plugins resolve against the kubeconfig's directory. `Config.autoConfigure` is forbidden — it reads the JVM-global `kubeconfig` property / `KUBECONFIG` env var.
- The context is recorded in `ClusterState.kubeContext` and pinned: later commands use the recorded context regardless of the user's current kubectl context. A workspace with no recorded context falls back to the kubeconfig's `current-context`.
- Per-client SOCKS is preserved exactly as today — `config.httpsProxy` set only in `ProxiedKubernetesClientFactory`. No JVM-global proxy property is touched.
- An ArchUnit rule stops a `ClusterHost` parameter from returning to any `services/` class that depends on the K8s client. Classes that genuinely still need the host (SSH or host-IP work out of scope here) sit on a named allow-list that #1090 and #1093 shrink.

## Capabilities

### New Capabilities
- `kubernetes-api-access`: how easy-db-lab reaches the Kubernetes API — a kubeconfig path plus a pinned context resolved once per command, loaded preserving the file path, with no control-host coupling and the per-client SOCKS tunnel preserved.

### Modified Capabilities
<!-- The SOCKS Proxy requirement in networking/spec.md is preserved unchanged (per-client proxy only); no spec-level behavior changes there. -->

## Impact

- **Main:** ~38 files declaring `controlHost: ClusterHost` lose the parameter (`K8sService` 30, `StressJobService` 15, the `DefaultK8s*Operations` classes, `BackendWorkloads`, `OtelSyncService`, `TelemetrySenders`, `MetricsRegistryService`, `ConfigChangeReport`); ~17 files make the 47 `createClient` calls that drop their argument. New: `KubeApi`, `KubeApiLocator`/`WorkspaceKubeApiLocator`, `ClusterState.kubeContext`.
- **Tests:** ~57 files (267 mentions) across `src/test` and `src/integrationTest`, mostly mechanical; one new ArchUnit test.
- **Classes that keep `ClusterHost`** (out of scope, allow-listed): `TempoTailFlush`, `CiliumService`, `CiliumInspectionService`, `K3sService`, `K3sClusterService`, `GrafanaDashboardTreeUploader` (SSH); `GrafanaClient`, `ObservabilityHttp`, `RoutedObservabilityHttp`, `SidecarService`, `DashboardDefaults` (host IP).
- **Dependencies:** ArchUnit 1.4.2 and Fabric8 7.8.0 already present. No hard dependency on other issues; #1040/#1082 build on this, #1090/#1093 shrink the allow-list.
- **Docs:** `kubernetes/CLAUDE.md` client patterns; root `CLAUDE.md` SOCKS invariant note (per-client proxy stays in `KubernetesClientFactory`).
