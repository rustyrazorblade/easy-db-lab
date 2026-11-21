# Kubernetes Package

This package provides Kubernetes client operations using the Fabric8 Kubernetes Client, with SOCKS5 proxy support for accessing private K3s clusters.

## Architecture

```
Command → SocksProxyService.ensureRunning(controlHost)
        → K8sService (services/) → K8sClientProvider.createClient()
        → WorkspaceKubeApiLocator.locate(): WorkspaceKubeconfig.require(workspace) + the pinned context
        → KubernetesClientFactory.createClient(KubeApi(kubeconfig, context))
        → client operations / ManifestApplier.applyManifest()
        ↕
  SSH tunnel (SOCKS5) → K3s API (10.0.x.x:6443)
```

## Files

| File | Purpose |
|------|---------|
| `WorkspaceKubeconfig.kt` | The one place that resolves the workspace kubeconfig path |
| `ManifestApplier.kt` | Server-side apply for typed K8s resources |
| `KubeApi.kt` | The cluster address: kubeconfig path + context; `KubeApi.loadConfig()` |
| `KubeApiLocator.kt` | Seam that finds the `KubeApi`; `currentContextOf(path)` helper |
| `KubeconfigFile.kt` | `parseKubeconfig(path)`: the only caller of Fabric8's internal `KubeConfigUtils`; an unparseable file fails naming its path and the parse cause |
| `KubernetesClientFactory.kt` | Client creation with SOCKS5 proxy config |
| `KubernetesModule.kt` | Koin DI registration (the client factory and `KubernetesErrorHandler` as a `ProviderErrorHandler`) |
| `KubernetesErrorHandler.kt` | Describes RBAC denials, 401/anonymous credential failures, admission rejections and a missing exec plugin as `Command.ProviderError` (see `providers/CLAUDE.md`, "Provider Errors") |
| `KubernetesDenial.kt` | Pure parsers of the API server's messages: `RbacDenial`, `AdmissionRejection` (PodSecurity, Webhook, ValidatingPolicy), and `Throwable.isKubernetesAuthRejection()` for poll predicates |
| `ApiPath.kt` | Resource, namespace, name and subresource of an API request path; the handler's fallback when a message does not match |
| `ExecPluginCheck.kt` | Fails `loadConfig()` with `ExecPluginNotFoundException` when the context's exec auth plugin is not on `PATH` (or at its kubeconfig-relative path); Fabric8 would otherwise send no credential |
| `KubernetesTypes.kt` | Data classes (KubernetesJob, KubernetesPod, KubernetesNode) |

## Workspace binding

The K8s module is bound to the cluster workspace. `K8sService` (in `services/`) takes no host: every
operation opens a client through `K8sClientProvider.createClient()`, which reads the kubeconfig that
`WorkspaceKubeconfig` resolves against `Context.workingDirectory`. Nothing else builds the kubeconfig
path; call `WorkspaceKubeconfig.file`, `exists` or `require`. The read queries (`listJobs`, `listPods`,
`listPodsByLabel`, `getNodes`, `isReachable`) are `K8sReadOperations`, part of `K8sService`; they return
`Result<T>`, and a `null` namespace means all namespaces.

## Loading the client: `KubeApi`, by path with an explicit context

Every client is built from a `KubeApi(kubeconfig: Path, context: String)` through `KubeApi.loadConfig()`, the one place a Fabric8 `Config` is loaded: it checks that the kubeconfig holds the context (failing fast with the context, the path and the contexts it does hold), checks that the context user's exec auth plugin can be found (`ExecPluginCheck`, failing with `ExecPluginNotFoundException`) and loads it with `Config.fromKubeconfig(api.context, file)`. That keeps the file location on the config, so a relative certificate or exec-plugin path resolves against the kubeconfig's directory, and it never reads `Config.KUBERNETES_KUBECONFIG_FILE` or the `KUBECONFIG` env var. **`Config.autoConfigure` is forbidden.**

No K8s service takes a `ClusterHost`. `services/K8sClientProvider.createClient()` asks its `KubeApiLocator` for the address: `services/WorkspaceKubeApiLocator` reads `<workspace>/kubeconfig` and the context pinned in `ClusterState.kubeContext`, falling back to the kubeconfig's `current-context`. `K8sClientProvider` asks it for the address on every operation, and `DefaultK8sService` builds a client and closes it afterwards, so `kit status`, the kit workload probe and the server's pod listing target the pinned context too, and the long-running `server` status cache picks up a rewritten kubeconfig, a newly pinned context or a new SOCKS port on its next refresh. The `metrics stream` stress-job lister (`services/metrics/Fabric8StressJobLister`) builds its per-route client from the same `KubeApiLocator` and `loadConfig()`. No other main code calls `currentContextOf`, and none calls `Config.fromKubeconfig` with kubeconfig text.

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


## Service Access Seam (`services/access/`)

Every CLI client that reads a cluster Service (Mimir, Loki, Tempo, Pyroscope, Grafana, a kit's JDBC
endpoint) asks `ServiceAccess.open(ServiceTarget)` for the endpoint it dials. No client builds a node
address or reads a proxy port from a JVM property. `ServiceTarget` names the namespace, Service,
Service port, node role and NodePort; `ClusterServices` holds the observability targets.
`ExposureServiceAccess` (Koin `serviceAccessModule`, registered with `ResourceManager`) picks the
implementation from `ClusterState.exposure`, read again on every `open`: it keeps the implementation
while the exposure is unchanged, and closes it and builds the new one when a later `init` (in a REPL
or server) recorded another:

- `Socks` (the default, AWS): `SocksServiceAccess` returns `ServiceEndpoint.ViaSocks` for the node's
  private IP and NodePort with the tunnel's current port. HTTP clients apply it with
  `OkHttpClient.forEndpoint` (per-client `Proxy`); JDBC goes through `SocksTcpBridge`.
- `Direct` (Tailscale): `DirectServiceAccess` returns `ServiceEndpoint.Plain` for the node address,
  with no proxy.
- `PortForward` (a cluster easy-db-lab does not own): `PortForwardServiceAccess` checks the Service
  exists, then opens a Fabric8 port-forward bound to `127.0.0.1` on a `LoopbackPortSelector` port
  (20000-29999). A kit's NodePort is mapped to the Service port behind it (`servicePortFor`). It holds
  one forward per target, reopens a dead one (closed, or `errorOccurred()`: Fabric8 keeps `isAlive`
  true after the pod a Service forward resolved is replaced, so `isAlive` alone is not enough), fails naming namespace/Service/port and the Kubernetes
  error, and closes every forward when the CLI exits.

A bench kit's `TARGET_HTTP_URL` (`services/KitEndpointResolver`) is opened through the same seam,
because its scripts `curl` it from this machine: the node address on Socks and Direct (the `curl`
wrapper routes it), a loopback forward on PortForward, held for the whole phase. `KitEndpoint.serviceTarget`
builds the target for it and for `KitJdbcSqlService`. When the seam cannot open it, the phase fails
with `Kit.TargetEndpointUnreachable` (stop and uninstall continue without it); it never falls back
to a `.svc` name this machine cannot resolve.

CQL on a port-forward cluster does not use the seam's Service forward. It uses
`CassandraPodForward`, which forwards one `cassandra-pods` StatefulSet pod on a fixed local port.
`CqlSessionFactory.createSingleEndpointSession` uses that port as the only contact point, with
`SingleEndpointDistanceEvaluator` (every other node IGNORED) and the token map off.

`status` cannot keep a forward open after it exits, so on a port-forward cluster `StatusAddresses`
prints each Service's DNS name and the `bin/kubectl port-forward` command that reaches it. A kit
endpoint names its NodePort, so `KitEndpointServices` resolves the Service it is published on from
the kit's own files, never from its name: the Service named on the endpoint's `kit.yaml` entry
(`service:`/`service-port:`, for a chart's or an operator's Service: presto, trino, kafka), else the
Service a manifest rendered into the kit's workspace directory declares with that NodePort, at the
Service port behind it (clickhouse: `svc/clickhouse-nodeport 30123:8123`, address
`...svc.cluster.local:8123`), else a Helm kit's release at an unknown port. `status` prints an
endpoint with no known Service or port without a forward command; `<kit> sql` on a port-forward
cluster fails naming the `service:` field. `KitEndpointServiceTest` resolves every packaged kit.

`createClient` names the kubeconfig path in every failure. An unreadable file throws an `IOException` with the cause kept (a missing file is still a cluster never reached, see `ClusterReach`). A file that does not parse throws an `IllegalStateException` with the path and the parser's exception class only: the parser's message quotes the start of the file, which holds client keys and tokens, so neither it nor the cause is kept. Print a failure with `Throwable.reason()`, which shows the cause behind the client's generic "An error has occurred." wrapper.
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
        // The factory reads the SOCKS port from Constants.Proxy.PORT_PROPERTY at client-creation
        // time (per-client, explicit; the global socksProxyHost is never set), and only while the
        // recorded environment's network mode is Tunnel: a direct cluster never gets httpsProxy.
        val contracts = get<ClusterContractSource>()
        ProxiedKubernetesClientFactory { contracts.current().networkMode }
    }
}
```

The factory is registered with **factory** scope. `K8sClientProvider` (a singleton, over the `WorkspaceKubeApiLocator` bound in `ServicesModule`) creates a new client per operation and the caller closes it: each operation resolves the `KubeApi` and reads the proxy port afresh, so even a provider kept for a process's lifetime never uses a stale kubeconfig, context or proxy port.

## K8s Resource Location

All K8s resources are built programmatically using Fabric8 manifest builders in
`src/main/kotlin/com/rustyrazorblade/easydblab/configuration/` subpackages.
XML/YAML config files are stored as classpath resources under the corresponding
`src/main/resources/com/rustyrazorblade/easydblab/configuration/` paths.
