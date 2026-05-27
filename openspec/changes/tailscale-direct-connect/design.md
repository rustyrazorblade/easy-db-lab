## Context

All cluster traffic (CQL, HTTP to Victoria metrics/logs, and K8s API calls via Fabric8) currently routes through a SOCKS5 proxy established via SSH dynamic port forwarding (`MinaSocksProxyService`). This was the only viable path when the developer's machine has no direct route to cluster private IPs.

Tailscale changes this: when Tailscale is running locally and the cluster control node has Tailscale started (via `tailscale start`), the local machine is on the VPN mesh and private IPs are directly reachable. The SSH SOCKS tunnel is then redundant.

The `tailscaleActive` flag is detected once at `init` time and persisted in `state.json`. All subsequent commands read this flag and skip the proxy.

## Goals / Non-Goals

**Goals:**
- Never start the SOCKS proxy when `tailscaleActive = true` in cluster state
- Apply to all three proxy consumers: CQL driver, OkHttp (Victoria metrics/logs), Fabric8 K8s client
- Zero behavior change when `tailscaleActive = false` (the default)

**Non-Goals:**
- Detecting Tailscale at runtime or per-connection (snapshot at init is sufficient)
- Handling Tailscale disconnecting mid-session
- Starting or managing Tailscale — that's already handled by `TailscaleService`

## Decisions

### Detection: `tailscale status --json` via ProcessBuilder

Run `tailscale status --json` locally (no SSH) and check `BackendState == "Running"`. Falls back to `false` if tailscale isn't installed or the process fails.

**Why not check a network interface (e.g., `utun`, `tailscale0`)?** Interface names vary by OS and Tailscale version; the CLI is more stable and gives authoritative state.

**Why not check Tailscale on the remote host?** We need to know if *the local machine* can reach cluster private IPs. The cluster node's Tailscale state is a separate concern.

### Storage: `tailscaleActive` field on `ClusterState`

The field goes directly on `ClusterState` (not inside `InitConfig`) because it affects connection routing at command execution time, not cluster provisioning parameters.

Default value `false` ensures backwards compatibility with existing `state.json` files.

### Factory pattern: conditional inside existing classes, not new implementations

Rather than introducing `DirectCqlSessionFactory`, `DirectHttpClientFactory`, etc., the existing factory classes each get `ClusterStateManager` injected and check `tailscaleActive` at creation time. This keeps the number of classes stable and avoids a parallel class hierarchy.

**`CqlSessionFactory`**: Add `createDirectSession(contactPoints, datacenter)` alongside existing `createSession(..., proxyPort)`. `CqlSessionService` selects which to call.

**`ProxiedHttpClientFactory`**: Returns a plain `OkHttpClient` (no proxy) when `tailscaleActive`. The "Proxied" prefix in the class name becomes misleading — acceptable given this is an internal implementation class.

**`ProxiedKubernetesClientFactory`**: Skips `config.httpsProxy` assignment when `tailscaleActive`.

### `CqlSessionService.close()`: skip `socksProxyService.stop()` when Tailscale active

If the proxy was never started, calling `stop()` is a no-op on the current implementation — but it's semantically wrong. The service should track whether it started the proxy and only stop it if it did.

## Risks / Trade-offs

- **Stale flag**: If Tailscale is started after `init`, the flag won't reflect it. User must re-init. → Acceptable; the pattern matches how other init-time decisions (instance type, region) work.
- **Misleading class name**: `ProxiedHttpClientFactory` sometimes returns an unproxied client. → Low impact; the interface `HttpClientFactory` is what consumers depend on. Can rename in a follow-up if it becomes confusing.
- **K8s module wiring**: `ProxiedKubernetesClientFactory` is currently created via a Koin factory lambda with `proxyPort` from `SocksProxyService`. It will need `ClusterStateManager` added to the lambda. → Straightforward Koin change.
