## Context

`K8sClientProvider.createClient(controlHost)` (`services/K8sClientProvider.kt:29-43`) reads only the workspace kubeconfig; it uses `controlHost` in one debug log line. Even so, 38 main files declare `controlHost: ClusterHost` (149 parameters; 30 in `K8sService.kt`, 15 in `StressJobService.kt`, 11 in `DefaultK8sNamespaceOperations.kt`), threading the host down to that client. The provider also constructs its factory inline (`K8sClientProvider.kt:39`), so tests cannot supply one.

`ProxiedKubernetesClientFactory` (`kubernetes/KubernetesClientFactory.kt:35-49`) reads the kubeconfig as text and calls `Config.fromKubeconfig(content)`. That drops the file location and always uses `current-context`, so relative certificate paths, exec auth plugins and a chosen context do not work. It then sets the per-client `httpsProxy` from `Constants.Proxy.PORT_PROPERTY` — the SOCKS invariant in the root `CLAUDE.md` and `openspec/specs/networking/spec.md`.

On AWS, `K3sService.downloadAndConfigureKubeconfig` (`services/K3sService.kt:195-229`) downloads `/etc/rancher/k3s/k3s.yaml`, rewrites the server address with Fabric8's typed model, and writes `<workspace>/kubeconfig`. `ClusterState` has no context field.

This is phase A (portable core) of the Kubernetes-forward epic #1032; the cluster contract's `api` field is "kubeconfig path and context" (architecture doc, "The cluster contract"). Owner decisions K1–K12 in that doc are settled.

## Goals / Non-Goals

**Goals:**
- K8s services and `K8sClientProvider` take no `ClusterHost`.
- The kubeconfig is loaded by path with an explicit, recorded, pinned context.
- An ArchUnit rule keeps `ClusterHost` out of K8s-client-dependent `services` classes.
- Per-client SOCKS behaviour and AWS behaviour are unchanged.

**Non-Goals:**
- SSH-based node operations (`RemoteOperationsService`, node commands #1039) — they keep `ClusterHost`.
- How CLI HTTP clients reach cluster Services (#1090).
- Remote tooling (`ClusterTooling`, #1078) — unchanged.
- Splitting classes that mix K8s calls with SSH or host-IP work (owner decision 2, below).
- `init --kubeconfig --context` (#1082) and the `Environment` type (#1040).

## Decisions

### D1. A `KubeApi` value and a `KubeApiLocator` seam

```kotlin
/** Where the Kubernetes API is: the kubeconfig file and the context pinned in it. */
data class KubeApi(val kubeconfig: Path, val context: String)

/** Resolves the cluster's [KubeApi] for the current command. */
fun interface KubeApiLocator { fun locate(): KubeApi }

interface KubernetesClientFactory { fun createClient(api: KubeApi): KubernetesClient }

class K8sClientProvider(locator: KubeApiLocator, factory: KubernetesClientFactory) {
    fun createClient(): KubernetesClient
}
```

`WorkspaceKubeApiLocator` reads the path `<workspace>/kubeconfig` (`Constants.K3s.LOCAL_KUBECONFIG`) and the context from `ClusterState.kubeContext`, falling back to the file's `current-context` (D3). The factory is injected through Koin.

*Alternatives:* pass `Path` + `String` separately (rejected — two loose values travel together everywhere; one value names the contract's `api` field); wait for #1040's `Environment` to carry it (rejected — owner decision 1). When #1040 lands, its `Environment` produces the `KubeApi` and the locator becomes a thin adapter; there is no compile-time dependency now.

### D2. Load by path: `Config.fromKubeconfig(context, file)`

Fabric8 7.8.0 provides `Config.fromKubeconfig(String context, File kubeconfig)`. It keeps the file on the config, so relative certificate and exec-plugin paths resolve against the kubeconfig's directory. If the context is not in the file, the factory fails fast naming the context and path.

*Rejected:* `Config.autoConfigure(context)` — it reads the JVM-global `kubeconfig` system property and `KUBECONFIG` env var, the same class of process-wide state behind the SOCKS incident (#725). The per-client `httpsProxy` and `applyClusterClientTimeouts()` stay exactly where they are in `ProxiedKubernetesClientFactory`.

### D3. Context stored in `ClusterState.kubeContext` (owner decision 1); fallback to `current-context` (owner decision 3)

`ClusterState` gains `kubeContext: String? = null`. When `up` writes the kubeconfig (`K3sService.downloadAndConfigureKubeconfig`), it records that kubeconfig's `current-context`. #1082 will later write it from `init --context`. A workspace with no recorded context (older state, or before `up` writes one) falls back to the kubeconfig's `current-context` — clusters are ephemeral, so no migration.

*Alternatives:* store it on #1040's `Environment` (rejected for now — creates a dependency); a sidecar file next to the kubeconfig (rejected — a second source of truth outside `state.json`); fail when absent (rejected — owner decision 3).

### D4. Remove `controlHost` from pass-through classes; allow-list the mixed ones (owner decision 2)

Every class that only forwards `controlHost` to the client loses the parameter: `K8sService`, `StressJobService`, the `DefaultK8s*Operations` classes, `BackendWorkloads`, `OtelSyncService`, `TelemetrySenders`, `MetricsRegistryService`, `ConfigChangeReport`, and their callers (`Up`, `KitRunnerCommand`, and others). Their K8s calls drop the argument.

Classes that genuinely use the host keep it and are **not split** in this change:
- SSH: `TempoTailFlush` (WAL listing), `CiliumService`, `CiliumInspectionService`, `K3sService`, `K3sClusterService`, `GrafanaDashboardTreeUploader` (#1093 removes it).
- Host IP: `GrafanaClient`, `ObservabilityHttp`, `RoutedObservabilityHttp`, `SidecarService`, `DashboardDefaults` (#1090 removes these).

Where such a class also calls the K8s client, the K8s calls stop passing the host; only the SSH or IP use keeps it. The ones that depend on the K8s client go on the ArchUnit allow-list (D5).

*Alternative:* split the mixed classes now so the rule has no exceptions (rejected — owner decision 2; widens this patch into #1090/#1093's territory).

### D5. ArchUnit rule scoped to every K8s-client-dependent `services` class (owner decision 4)

A new test beside `architecture/PackageBoundaryTest.kt`, using the same `ClassFileImporter` with `DoNotIncludeTests`. A custom `ArchCondition` on `codeUnits()` in `..services..` fails any method or constructor that takes `ClusterHost` in a class that depends on `KubernetesClient` or `K8sClientProvider`, unless the class is on a named allow-list. The test also fails when an allow-list entry is stale (the class no longer takes `ClusterHost` or no longer depends on the client), so #1090 and #1093 are forced to shrink it. The test includes a fixture-based check that the condition catches a violation, matching `PackageBoundaryTest`'s fixtures.

*Alternative:* a list of named classes the rule covers (rejected — owner decision 4; goes stale as classes are added).

## Risks / Trade-offs

- [The allow-list holds ~11 classes, so the rule is weaker than "no exceptions"] → it is named, must shrink (stale entries fail), and #1090/#1093 own the removals.
- [`fromKubeconfig(context, file)` behaves differently from the text overload for the existing K3s kubeconfig] → that file uses absolute, embedded certificate data and context `default`; the AWS-unchanged scenario is covered by the existing `K8sServiceIntegrationTest` (K3s TestContainer) and a unit test loading a kubeconfig with a relative cert path.
- [Large mechanical diff across ~38 main and ~57 test files] → signatures change only by dropping a parameter; the compiler finds every caller.
- [A user's kubeconfig can hold production contexts (#1082)] → the context is pinned, never read from the shell's `KUBECONFIG`; printing the API server on `init`/`up` stays with #1082.

## Migration Plan

None. Clusters are ephemeral; a workspace with no recorded context falls back to `current-context`. Rollback is reverting the change.

## Open Questions

None blocking. Owner decisions 1–4 were made on 2026-11-13 (issue #1089 comment).
