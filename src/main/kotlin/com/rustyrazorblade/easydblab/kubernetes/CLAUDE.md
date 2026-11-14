# Kubernetes Package

This package provides Kubernetes client operations using the Fabric8 Kubernetes Client, with SOCKS5 proxy support for accessing private K3s clusters.

## Architecture

```
Command → SocksProxyService.ensureRunning(controlHost)
        → KubernetesClientFactory.createClient(KubeApi(kubeconfig, context))
        → KubernetesService.listPods() / ManifestApplier.applyManifest()
        ↕
  SSH tunnel (SOCKS5) → K3s API (10.0.x.x:6443)
```

## Files

| File | Purpose |
|------|---------|
| `KubernetesService.kt` | Interface for high-level K8s operations |
| `DefaultKubernetesService.kt` | Fabric8 implementation; builds and closes a client per operation |
| `ManifestApplier.kt` | Server-side apply for typed K8s resources |
| `KubeApi.kt` | The cluster address: kubeconfig path + context; `KubeApi.loadConfig()` |
| `KubeApiLocator.kt` | Seam that finds the `KubeApi`; `currentContextOf(path)` helper |
| `KubernetesClientFactory.kt` | Client creation with SOCKS5 proxy config |
| `KubernetesModule.kt` | Koin DI registration |
| `KubernetesTypes.kt` | Data classes (KubernetesJob, KubernetesPod, KubernetesNode) |

## KubernetesService Interface

```kotlin
interface KubernetesService {
    fun listJobs(namespace: String? = null): Result<List<KubernetesJob>>
    fun listPods(namespace: String? = null): Result<List<KubernetesPod>>
    fun getNodes(): Result<List<KubernetesNode>>
    fun isReachable(): Result<Boolean>
}
```

All methods return `Result<T>`. Namespace `null` means all namespaces.

## Loading the client: `KubeApi`, by path with an explicit context

Every client is built from a `KubeApi(kubeconfig: Path, context: String)` through `KubeApi.loadConfig()`, the one place a Fabric8 `Config` is loaded: it checks that the kubeconfig holds the context (failing fast with the context, the path and the contexts it does hold) and loads it with `Config.fromKubeconfig(api.context, file)`. That keeps the file location on the config, so a relative certificate or exec-plugin path resolves against the kubeconfig's directory, and it never reads `Config.KUBERNETES_KUBECONFIG_FILE` or the `KUBECONFIG` env var. **`Config.autoConfigure` is forbidden.**

No K8s service takes a `ClusterHost`. `services/K8sClientProvider.createClient()` asks its `KubeApiLocator` for the address: `services/WorkspaceKubeApiLocator` reads `<workspace>/kubeconfig` and the context pinned in `ClusterState.kubeContext`, falling back to the kubeconfig's `current-context`. `DefaultKubernetesService` takes the same `KubeApiLocator` and asks it for the address on every operation, building a client and closing it afterwards, so `kit status`, the kit workload probe and the server's pod listing target the pinned context too, and the long-running `server` status cache picks up a rewritten kubeconfig, a newly pinned context or a new SOCKS port on its next refresh. The `metrics stream` stress-job lister (`services/metrics/Fabric8StressJobLister`) builds its per-route client from the same `KubeApiLocator` and `loadConfig()`. No other main code calls `currentContextOf`, and none calls `Config.fromKubeconfig` with kubeconfig text.

`up` records the context: `K3sService.downloadAndConfigureKubeconfig` returns the written kubeconfig's `current-context` (failing when it names none), `K3sClusterService` passes it back as `K3sSetupResult.kubeContext`, and `Up` sets it on its own `workingState` and saves that. The service never loads and saves `state.json` itself, so a later save of `up`'s in-memory state cannot erase the context.

## SOCKS5 Proxy Pattern

K3s API servers run on private IPs, so all access goes through an SSH tunnel:

1. `SocksProxyService.ensureRunning(controlHost)` — starts SSH dynamic port forwarding
2. `ProxiedKubernetesClientFactory` configures Fabric8 client with `socks5://127.0.0.1:{port}`
3. All K8s API traffic tunnels through SSH to the private cluster

```kotlin
// In ProxiedKubernetesClientFactory — the port is read from Constants.Proxy.PORT_PROPERTY,
// NOT passed in and NOT set as the global socksProxyHost (which would capture the AWS SDK too).
config.httpsProxy = "socks5://127.0.0.1:$port"
config.connectionTimeout = 30000  // Increased for SOCKS proxy
config.requestTimeout = 60000
```

## ManifestApplier

Applies K8s manifests using Fabric8's typed server-side apply (not generic resources).

**Supported resource kinds:** Namespace, ConfigMap, Service, DaemonSet, Deployment, StatefulSet, Secret, Job

```kotlin
// Apply a YAML file
ManifestApplier.applyManifest(client, File("deployment.yaml"))

// Apply YAML string
ManifestApplier.applyYaml(client, yamlContent)
```

Handles multi-document YAML (separated by `---`). Each document is parsed for its `kind` and routed to the appropriate typed Fabric8 loader.

## Koin Registration

```kotlin
val kubernetesModule = module {
    factory<KubernetesClientFactory> {
        // No proxy args — the factory reads the SOCKS port from Constants.Proxy.PORT_PROPERTY at
        // client-creation time (per-client, explicit; the global socksProxyHost is never set).
        ProxiedKubernetesClientFactory()
    }
    // The KubeApiLocator (services/WorkspaceKubeApiLocator) is bound in ServicesModule.
    factory<KubernetesService> { DefaultKubernetesService(clientFactory = get(), locator = get()) }
}
```

Both registered as **factory** scope. `DefaultKubernetesService` holds no client: each operation resolves the `KubeApi` and reads the proxy port afresh, so even a service kept for a process's lifetime never uses a stale kubeconfig, context or proxy port.

## K8s Resource Location

All K8s resources are built programmatically using Fabric8 manifest builders in
`src/main/kotlin/com/rustyrazorblade/easydblab/configuration/` subpackages.
XML/YAML config files are stored as classpath resources under the corresponding
`src/main/resources/com/rustyrazorblade/easydblab/configuration/` paths.
