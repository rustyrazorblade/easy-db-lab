## 1. Cluster State

- [x] 1.1 Add `tailscaleActive: Boolean = false` field to `ClusterState` data class

## 2. Local Tailscale Detection

- [x] 2.1 Add a `detectLocalTailscale(): Boolean` function (private, in `Init.kt` or a companion) that runs `tailscale status --json` via `ProcessBuilder`, parses `BackendState`, and returns `false` on any failure
- [x] 2.2 Call `detectLocalTailscale()` in `Init.prepareEnvironment()` and set `tailscaleActive` on the `ClusterState` before saving

## 3. CQL Session — Direct Path

- [x] 3.1 Add `createDirectSession(contactPoints: List<String>, datacenter: String): CqlSession` to `CqlSessionFactory` interface and `DefaultCqlSessionFactory` (uses plain `CqlSession.builder()`, same driver config, no proxy)
- [x] 3.2 Update `CqlSessionService.getOrCreateSession()` to check `clusterState.tailscaleActive`: if `true`, skip `socksProxyService.ensureRunning()` and call `sessionFactory.createDirectSession()`
- [x] 3.3 Update `CqlSessionService.close()` to only call `socksProxyService.stop()` when the proxy was actually used

## 4. HTTP Client — Direct Path

- [x] 4.1 Inject `ClusterStateManager` into `ProxiedHttpClientFactory`
- [x] 4.2 In `ProxiedHttpClientFactory.createClient()`, check `clusterStateManager.load().tailscaleActive`: if `true`, return a plain `OkHttpClient` with no proxy

## 5. Kubernetes Client — Direct Path

- [x] 5.1 Inject `ClusterStateManager` into `ProxiedKubernetesClientFactory`
- [x] 5.2 In `ProxiedKubernetesClientFactory.createClient()`, check `tailscaleActive`: if `true`, skip `config.httpsProxy` assignment
- [x] 5.3 Update `KubernetesModule.kt` Koin factory lambda to inject and pass `ClusterStateManager`

## 6. Tests

- [x] 6.1 Unit test `detectLocalTailscale()` for: tailscale running, tailscale not connected, tailscale not installed (verify returns `false` without throwing)
- [x] 6.2 Unit test `DefaultCqlSessionFactory.createDirectSession()` produces a session config without SOCKS proxy options
- [x] 6.3 Unit test `CqlSessionService` with `tailscaleActive=true`: verify `socksProxyService.ensureRunning()` is never called
- [x] 6.4 Unit test `ProxiedHttpClientFactory` with `tailscaleActive=true`: verify returned `OkHttpClient` has no proxy set
- [x] 6.5 Unit test `ProxiedKubernetesClientFactory` with `tailscaleActive=true`: verify `config.httpsProxy` is `null`
