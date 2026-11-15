## Context

CLI access to cluster Services is split across two route mechanisms today: `DefaultObservabilityHttp`/`RoutedObservabilityHttp` build `privateIp:port` URLs, and an injected global `OkHttpClient` (registered in `services/ServicesModule.kt`) carries a process-wide SOCKS selector. `GrafanaClient` (`services/GrafanaClient.kt:72`) uses that global client and builds URLs from `controlHost`. `KitJdbcSqlService` (`services/sql/KitJdbcSqlService.kt:57`) reads the SOCKS port from `Constants.Proxy.PORT_PROPERTY`. `CqlSessionService` (`services/CqlSessionService.kt:95-104`) maps host private IPs to contact points and routes through `SocksProxyNettyOptions` on AWS. `status`/`StatusCache` print `hosts[type].privateIp` for kit endpoints.

This change is phase A (portable core) of the Kubernetes-forward epic #1032; the cluster contract's `exposure` field is "how the CLI reaches a Service" (architecture doc, "Reaching the cluster"). Owner decisions K1–K12 are settled; K12: Grafana is reached only through a `127.0.0.1` port-forward on clusters easy-db-lab does not own. Owner decisions on this issue were made 2026-11-13 (issue #1090 comment).

This builds on #1089 (branch `issue-1089`, commit `2cf5dbb1`): `K8sClientProvider.createClient()` takes no `ClusterHost`, clients come from a `KubeApi(kubeconfig, context)` via `KubeApiLocator`, and an ArchUnit rule forbids `ClusterHost` in K8s-client-dependent `services` classes with a named allow-list this change shrinks.

## Goals / Non-Goals

**Goals:**
- One seam opens a connection to a named cluster Service, with Socks/Direct/PortForward implementations chosen from the recorded `exposure`.
- Port-forwards bind `127.0.0.1` only and close with the command; failures name the namespace, Service and Kubernetes error.
- HTTP, JDBC and CQL clients route through the seam; the AWS path's output is unchanged.
- The global-selector `OkHttpClient` is gone (fixes #1104).

**Non-Goals:**
- Changing the SOCKS tunnel (`ProcessSocksProxyService`) itself.
- The browser-facing Grafana forward on AWS (#943) — the seam is designed so #943 can reuse it.
- Ingress, LoadBalancer or Gateway API exposure.
- `KitEndpointResolver`/`KitEndpointAddresses` in-cluster `TARGET_*` injection (#1091).
- #1040's `Environment` type.

## Decisions

### D1. The `ServiceAccess` seam

```kotlin
/** A cluster Service the CLI reads: the K8s Service/port, plus the node role/port the Socks and Direct impls dial. */
data class ServiceTarget(
    val namespace: String, val service: String, val servicePort: Int,
    val nodeRole: ServerType, val nodePort: Int,
)

sealed interface ServiceEndpoint {
    data class Plain(val host: String, val port: Int) : ServiceEndpoint          // Direct, PortForward
    data class ViaSocks(val host: String, val port: Int, val socksPort: Int) : ServiceEndpoint
}

/** Opens (or reuses) access to a cluster Service for the current command. */
interface ServiceAccess : AutoCloseable {
    fun open(target: ServiceTarget): ServiceEndpoint
}
```

A small `ClusterServices` catalog holds the targets for Mimir, Loki, Tempo, Pyroscope and Grafana so no client builds addresses itself.

*Alternatives:* a bare `OkHttpClient` factory (rejected — JDBC and CQL need the endpoint, not an HTTP client); returning only a URL string (rejected — a port-forward needs an owner that closes it).

### D2. Three implementations, selected by Koin from `exposure`

Register `single<ServiceAccess>` chosen `when (clusterState.exposure)`, following the `SshRoute` pattern in `SSHModule`:
- `SocksServiceAccess` → `ViaSocks(hosts[role].first().privateIp, nodePort, socksPort)`, reading the tunnel port from the recorded route as the observability route does today. Never the JVM-global property.
- `DirectServiceAccess` → `Plain(privateIp, nodePort)`, no proxy.
- `PortForwardServiceAccess` → verifies the Service exists, then `services().inNamespace(ns).withName(svc).portForward(servicePort, InetAddress.getByName("127.0.0.1"), localPort)`, checks `errorOccurred()`/client throwables, returns `Plain("127.0.0.1", localPort)`. `localPort` from `LoopbackPortSelector` (20000–29999).

`PortForwardServiceAccess` is registered with `ResourceManager` so it closes on exit.

### D3. HTTP clients over the seam (fixes #1104)

One helper `OkHttpClient.forEndpoint(e)`: `ViaSocks` → `proxy(Proxy(SOCKS, InetSocketAddress("127.0.0.1", e.socksPort)))`; `Plain` → `NO_PROXY`. This is the explicit per-client pattern `RoutedObservabilityHttp` already uses. `DefaultObservabilityHttp` and `RoutedObservabilityHttp` merge into one `ObservabilityHttp` that takes a `ServiceAccess`; `metrics stream` keeps its "never start a tunnel" behaviour by injecting a non-starting Socks variant. `GrafanaClient` drops `controlHost` and builds its URL from the endpoint. The global-selector `OkHttpClient` singleton is deleted, so no client can carry a stale process-wide tunnel port — the #1104 root cause.

### D4. JDBC and CQL

- JDBC: `KitJdbcSqlService` asks the seam for the endpoint; `ViaSocks` keeps the `SocksTcpBridge`, `Plain` connects directly. The `System.getProperty(PORT_PROPERTY)` branch is removed.
- CQL (owner decision): on `PortForward`, a single-endpoint mode — forward one Cassandra StatefulSet pod by name on a **fixed** local port, set it as the sole contact point, add a `NodeDistanceEvaluator` that returns IGNORED for any node whose endpoint is not the forward, disable `advanced.metadata.token-map`, and set `basic.load-balancing-policy.local-datacenter`. A dead forward is restarted on the same port so the driver reconnects unaided. AWS (`Socks`) keeps today's multi-node session via `SocksProxyNettyOptions`.

*Why single-endpoint, not per-node forwards + an AddressTranslator:* the driver does not run contact points through the translator, per-node forwards must track `system.peers` as pods churn to new IPs, and token-aware routing buys nothing here — load generators run in-cluster; the CLI `cql` is for ad-hoc queries. Both the architect and the Cassandra domain advisor recommended single-endpoint independently.

### D5. `status` kit endpoints (AC5)

A live port-forward would close the moment `status` exits, so `PortForward` prints the Service DNS name plus a ready-to-run `bin/kubectl port-forward -n <ns> svc/<name> <port>` line (through the workspace wrapper). Socks/Direct print the reachable address as today. No new long-lived `forward` command (owner decision).

### D6. `exposure` on `ClusterState`

`ClusterState` gains `exposure: Exposure = Socks`. `init` records `Socks` by default; `Direct` when Tailscale is active or the `direct` env-file key (#1077) is set; `PortForward` when #1082's `init --kubeconfig` runs. #1040's `Environment` later produces it; no compile-time dependency now.

### D7. Grafana `ClusterIP` Service

`GrafanaManifestBuilder` gains a `ClusterIP` Service on port 3000 (`Constants.GRAFANA_PORT`), matching Mimir/Loki/Tempo/Pyroscope, so `PortForward` forwards to a Service rather than a pod.

## Risks / Trade-offs

- **Port-forward throughput under load is unverified.** These are small JSON reads and flush POSTs, but Loki's synchronous ingester shutdown holds one stream for minutes and EKS can close idle API connections. Needs one EKS lab run before AC sign-off; load clients run in-cluster, so this is not on the hot path.
- **Blast radius:** every Mimir/Loki reader and `TeardownFlushService` change signatures. Mitigated by one seam and the compiler finding every caller.
- **AC1 (AWS output unchanged):** covered by before/after assertions on the formatted URLs and the existing K3s TestContainer integration test.
- **`#1104` confirmation:** the fix presumes the stale global selector is the cause; a regression test reproduces a replaced tunnel port and asserts the per-client path uses the current one.

## Migration Plan

None. Clusters are ephemeral. A workspace with no recorded `exposure` reads `Socks` (the default), preserving AWS behaviour.

## Open Questions

None blocking. Owner decisions were made 2026-11-13 (issue #1090 comment). Merges after #1089.
