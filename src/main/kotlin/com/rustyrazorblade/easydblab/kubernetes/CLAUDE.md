# Kubernetes Package

This package provides Kubernetes client operations using the Fabric8 Kubernetes Client, with SOCKS5 proxy support for accessing private K3s clusters.

## Architecture

```
Command → SocksProxyService.ensureRunning(controlHost)
        → KubernetesClientFactory.createClient(kubeconfigPath)
        → KubernetesService.listPods() / ManifestApplier.applyManifest()
        ↕
  SSH tunnel (SOCKS5) → K3s API (10.0.x.x:6443)
```

## Files

| File | Purpose |
|------|---------|
| `KubernetesService.kt` | Interface for high-level K8s operations |
| `DefaultKubernetesService.kt` | Fabric8 implementation with lazy client |
| `ManifestApplier.kt` | Server-side apply for typed K8s resources |
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
    factory<KubernetesService> { (kubeconfigPath: String) ->
        DefaultKubernetesService(clientFactory = get(), kubeconfigPath = Paths.get(kubeconfigPath))
    }
}
```

Both registered as **factory** scope — client holds state tied to proxy session and must be recreated per use.

## K8s Resource Location

All K8s resources are built programmatically using Fabric8 manifest builders in
`src/main/kotlin/com/rustyrazorblade/easydblab/configuration/` subpackages.
XML/YAML config files are stored as classpath resources under the corresponding
`src/main/resources/com/rustyrazorblade/easydblab/configuration/` paths.
