## Why

`MinaSocksProxyService` creates an in-process SSH tunnel that dies when the JVM exits. Every command invocation pays the full SSH connection setup cost. The implementation also uses reflection to extract a private `ClientSession` field from the SSH abstraction layer.

Additionally, `tailscaleActive` was set at `init` time by running `tailscale status --json` — a runtime check. A user who configured Tailscale credentials but ran `init` while Tailscale was not connected would get `tailscaleActive: false` permanently. Detection should be profile-based: if Tailscale credentials are configured, the cluster uses Tailscale.

## What Changes

- `MinaSocksProxyService` is replaced with `ProcessSocksProxyService`, which launches `ssh -v -N -D <port> -F sshConfig control0` as a detached OS process. The process persists across JVM exits and is reused across invocations until `down` is called.
- On proxy start, JVM system properties `socksProxyHost` and `socksProxyPort` are set, routing all OkHttp and fabric8 connections through the SOCKS proxy without per-client configuration.
- Tailscale detection moves from a runtime check (`tailscale status --json`) to `User.isTailscaleEnabled()` (credentials present in profile). `Init` gains a `--no-tailscale` flag to override.
- `stop()` is removed from the `SocksProxyService` interface — the proxy is an OS process that lives until `down`, not a per-session resource.
- `SocksProxyState.connectionCount` is removed — it was a server-mode tracking field with no meaning for a persistent OS process.

## Capabilities

### Modified Capabilities

- `networking`: SOCKS proxy is now a persistent detached OS process; Tailscale routing is profile-based; proxy routing is configured at the JVM level via system properties rather than per-client.

## Impact

- `User` — new `isTailscaleEnabled(): Boolean` method
- `ClusterState` — new `isTailscaleEnabled(): Boolean` method
- `Init` command — replace `detectLocalTailscale()` with `userConfig.isTailscaleEnabled()`, add `--no-tailscale` flag, remove `detectLocalTailscale()` and `parseTailscaleOutput()`
- `SocksProxyService` interface — remove `stop()`, remove `SocksProxyState.connectionCount`
- `proxy/` package — new `ProcessSocksProxyService`, new shared `Socks5ProxyStateFile` data class (kotlinx.serialization, replaces Jackson inner class in `Down.kt`)
- `ProxyModule` — wire `ProcessSocksProxyService(get())`
- `ProxiedHttpClientFactory` — remove explicit SOCKS proxy configuration; OkHttp uses system properties via `ProxySelector.getDefault()`
- `ProxiedKubernetesClientFactory` / `K8sClientProvider` / `KubernetesModule` — remove `tailscaleActive` and `proxyPort` constructor params; read proxy port from system property
- `K3sService` — use `isTailscaleEnabled()`; remove proxy port passing to factory
- `VictoriaLogsService` — gate `ensureRunning()` on `!isTailscaleEnabled()`; remove `getLocalPort()` usage
- `VictoriaStreamService` — add `ClusterStateManager`; gate `ensureRunning()` on `!isTailscaleEnabled()`; `directClient` gets `.proxy(Proxy.NO_PROXY)` to bypass system proxy
- `VictoriaMetricsQueryService` — add `ClusterStateManager`; gate `ensureRunning()` on `!isTailscaleEnabled()`
- `CqlSessionService` — remove `proxyStarted` field and `stop()` call in `close()`; use `isTailscaleEnabled()`
- `Down.kt` — use shared `Socks5ProxyStateFile` with kotlinx.serialization
- `MinaSocksProxyService.kt` — deleted
