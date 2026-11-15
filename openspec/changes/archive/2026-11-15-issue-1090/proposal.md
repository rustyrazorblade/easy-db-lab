## Why

Every CLI client of a cluster Service dials a node's private IP. `DefaultObservabilityHttp` and `RoutedObservabilityHttp` (`services/ObservabilityHttp.kt:61`, `services/RoutedObservabilityHttp.kt:26`) and `GrafanaClient` (`services/GrafanaClient.kt:72,105`) build `controlHost.privateIp:<port>` URLs over an injected global `OkHttpClient` that carries a process-wide SOCKS selector; `commands/Status.kt` and `mcp/StatusCache.kt` print `privateIp` kit endpoints; `KitJdbcSqlService` reads the SOCKS port from a JVM property (`services/sql/KitJdbcSqlService.kt:57`). That works only where the operator's machine can route to the nodes — AWS through the SOCKS tunnel, or a local `direct` cluster. On a cluster the user brings (EKS), the nodes are not routable, which blocks the Kubernetes-forward epic (#1032, phase A) from running the stack there.

The injected global `OkHttpClient` is also the likely cause of #1104: its SOCKS selector can carry a stale or replaced tunnel port, so the `down` save's Grafana annotation call fails with "Malformed reply from SOCKS server."

## What Changes

- A single **`ServiceAccess`** seam opens a connection to a named cluster Service and port and returns an endpoint the caller dials. It has three implementations, selected from the recorded environment:
  - `Socks`: today's path — the node address through the per-client SOCKS tunnel (AWS). The JVM-global proxy properties are never touched.
  - `Direct`: the node or Service address with no proxy (Tailscale, or the `direct` network mode from #1077).
  - `PortForward`: a Fabric8 port-forward to the Service bound to `127.0.0.1` only (your cluster). Verifies the Service exists and fails fast naming the namespace, Service, port and the Kubernetes error.
- HTTP clients route through the seam: the two `ObservabilityHttp` implementations merge into one over the seam, and `GrafanaClient` drops `controlHost`. A single `OkHttpClient.forEndpoint(endpoint)` helper sets the per-client SOCKS `Proxy` or `NO_PROXY`; the global-selector `OkHttpClient` singleton is removed. **Closes #1104** — no client carries a stale process-wide tunnel port.
- The JDBC `sql` command routes through the seam: a `ViaSocks` endpoint keeps the `SocksTcpBridge`, a `Plain` endpoint connects directly.
- **CQL** gains a single-endpoint PortForward mode for your-cluster (owner decision): one Cassandra StatefulSet pod is forwarded on a fixed local port and used as the sole contact point, with a `NodeDistanceEvaluator` that marks every other node IGNORED, the token map disabled, and `local-datacenter` set. AWS keeps today's multi-node SOCKS session unchanged.
- `status` prints each kit endpoint in the form that works from the operator's machine: Socks/Direct unchanged; PortForward prints the Service DNS name plus a ready-to-run `bin/kubectl port-forward …` line (a live forward would close when `status` exits).
- `exposure` is recorded in `ClusterState` (`Socks` default; `Direct` from Tailscale/#1077; `PortForward` set by #1082 `init --kubeconfig`). #1040's `Environment` absorbs it later with no compile-time dependency now.
- A `ClusterIP` Service is added for Grafana (none exists today). Mimir, Loki, Tempo and Pyroscope already have `ClusterIP` Services.
- Local forward ports come from `LoopbackPortSelector` (20000–29999).

## Capabilities

### New Capabilities
- `service-access`: how the CLI reaches a cluster Service — one seam with Socks, Direct and PortForward implementations chosen from the recorded `exposure`, per-client proxy only, port-forwards bound to `127.0.0.1` and closed with the command.

### Modified Capabilities
<!-- The SOCKS Proxy requirement in networking/spec.md is preserved unchanged (per-client proxy only, CLI-only lifecycle); no spec-level behaviour changes there. -->

## Impact

- **Main:** `ObservabilityHttp` (two impls → one), `GrafanaClient`, `MimirQueryService`, `LokiQueryService`, `LokiTailFlush`, `MimirTailFlush`, `AnnotationMirror`, `CycleQueries`, `TeardownFlushService`, `KitJdbcSqlService`, `CqlSessionService`/`CqlSessionFactory`, `commands/Status.kt`, `mcp/StatusCache.kt`, `GrafanaManifestBuilder` (new Service). New: `ServiceAccess` + `ServiceTarget`/`ServiceEndpoint`, `SocksServiceAccess`/`DirectServiceAccess`/`PortForwardServiceAccess`, `ClusterServices` catalog, `ClusterState.exposure`.
- **#1089 allow-list:** removes `GrafanaClient`, `ObservabilityHttp`, `RoutedObservabilityHttp`. Cannot remove `SidecarService` (in-cluster agent IP → #1091), `DashboardDefaults` (browser URL → #943), the SSH classes.
- **Dependencies:** hard on #1089 (host-free `K8sClientProvider.createClient()`, branch `issue-1089`/PR #1107); #1090 merges after #1089. Not blocked by #1091 to build/test (ClusterIP Services already exist for the backends; forwarding into a hostNetwork pod works), but an end-to-end your-cluster run needs #1091/#1101 placement. #1040 not needed. #1091 depends on #1090 landing first.
- **Out of scope:** `KitEndpointResolver`/`KitEndpointAddresses` `privateIp` users that inject in-cluster `TARGET_*` variables (#1091 Service-DNS territory); the SOCKS tunnel itself; Ingress/LoadBalancer/Gateway.
- **Docs:** `kubernetes/CLAUDE.md` service-access patterns; `docs/reference/ports.md` / `docs/user-guide/monitoring.md` for the port-forward reach on your-cluster; root `CLAUDE.md` seam note.
