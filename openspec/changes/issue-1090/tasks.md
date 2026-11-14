## 1. The seam and its implementations

- [x] 1.1 Add `ServiceTarget`, the `ServiceEndpoint` sealed interface (`Plain`, `ViaSocks`) and the `ServiceAccess` interface (AutoCloseable), with class-level KDoc
- [x] 1.2 Add a `ClusterServices` catalog with the Mimir, Loki, Tempo, Pyroscope and Grafana targets
- [x] 1.3 Implement `SocksServiceAccess` (node private IP + port via the recorded tunnel route; never the JVM property)
- [x] 1.4 Implement `DirectServiceAccess` (node/Service address, no proxy)
- [x] 1.5 Implement `PortForwardServiceAccess`: verify the Service exists, open a Fabric8 port-forward bound to `127.0.0.1` on a `LoopbackPortSelector` port, check `errorOccurred()`/client throwables, fail fast naming namespace/Service/port/K8s error; hold one forward per target, reuse within a command, reopen a dead one; close all on `close()`
- [x] 1.6 Register `single<ServiceAccess>` in Koin chosen `when (clusterState.exposure)`, and register the PortForward impl with `ResourceManager` for exit close

## 2. Exposure in cluster state

- [x] 2.1 Add `ClusterState.exposure: Exposure = Socks` (sealed/enum Socks|Direct|PortForward)
- [x] 2.2 `init` records `Socks` by default, `Direct` when Tailscale is active or the #1077 `direct` env-file key is set
- [x] 2.3 Test: recorded exposure selects the matching implementation; absent exposure defaults to Socks

## 3. Route HTTP clients through the seam (fixes #1104)

- [x] 3.1 Add the `OkHttpClient.forEndpoint(endpoint)` helper (per-client SOCKS `Proxy` or `NO_PROXY`)
- [x] 3.2 Merge `DefaultObservabilityHttp` and `RoutedObservabilityHttp` into one `ObservabilityHttp` over the seam; keep `metrics stream`'s non-starting-Socks behaviour
- [x] 3.3 Drop `controlHost` from `GrafanaClient`; build its URL from the endpoint
- [x] 3.4 Update `MimirQueryService`, `LokiQueryService`, `LokiTailFlush`, `MimirTailFlush`, `AnnotationMirror`, `CycleQueries`, `TeardownFlushService` to the new signatures
- [x] 3.5 Remove the global-selector `OkHttpClient` singleton from `ServicesModule`
- [x] 3.6 Regression test for #1104: a replaced tunnel port is used by the per-client path (no stale process-wide port); add `Closes #1104` to the PR

## 4. JDBC and CQL

- [x] 4.1 `KitJdbcSqlService` obtains the endpoint from the seam; `ViaSocks` keeps `SocksTcpBridge`, `Plain` connects directly; remove the `System.getProperty(PORT_PROPERTY)` branch
- [x] 4.2 Add the single-endpoint CQL mode for PortForward: forward one Cassandra StatefulSet pod on a fixed local port, sole contact point, `NodeDistanceEvaluator` marking other nodes IGNORED, token map off, `local-datacenter` set; restart a dead forward on the same port
- [x] 4.3 Keep the AWS multi-node SOCKS session unchanged in `CqlSessionService`/`CqlSessionFactory`
- [x] 4.4 Tests: single-endpoint evaluator ignores non-forward nodes; AWS path unchanged

## 5. Status and Grafana Service

- [x] 5.1 `commands/Status.kt` and `mcp/StatusCache.kt` print kit endpoints via the seam: Socks/Direct reachable address; PortForward prints Service DNS + a `bin/kubectl port-forward` hint
- [x] 5.2 Add a `ClusterIP` Service for Grafana in `GrafanaManifestBuilder` on `Constants.GRAFANA_PORT`
- [x] 5.3 Dedupe the `Status.kt`/`StatusCache.kt` access-info builders

## 6. Architecture rule

- [x] 6.1 Remove `GrafanaClient`, `ObservabilityHttp`, `RoutedObservabilityHttp` from the #1089 ArchUnit allow-list; confirm the stale-entry check passes

## 7. Verification and docs

- [x] 7.1 Integration test (K3s TestContainer, `src/integrationTest/`): port-forward opens bound to `127.0.0.1`, a missing Service fails naming it, the forward closes with the command
- [x] 7.2 AC1 before/after test: AWS formatted URLs and output unchanged
- [ ] 7.3 `./gradlew test`, `./gradlew integrationTest`, `ktlintCheck`, `detekt` (JDK 21, no new suppressions) pass
- [x] 7.4 Update `kubernetes/CLAUDE.md` (service-access seam), `docs/reference/ports.md` / `docs/user-guide/monitoring.md` (port-forward reach on your-cluster), root `CLAUDE.md` seam note
