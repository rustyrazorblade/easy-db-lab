## Context

`ProcessSocksProxyService.applySystemProperties()` sets the JVM-standard `socksProxyHost=127.0.0.1`
and `socksProxyPort=<port>`. `java.net` applies SOCKS at the **socket layer**, so once these are set
*every* socket the JVM opens — including all AWS SDK clients — is routed through the tunnel. Neither
HTTP client implementation (Apache or url-connection) escapes this; it is below the HTTP layer.

This was latent until a command ran on an already-UP cluster (where the proxy starts before the
command body). Then AWS control-plane calls and the S3 state backup got force-tunnelled. On a
corporate network (Zscaler) the S3 hostname resolves locally to a synthetic `100.64.x` address; the
control node can't reach it, so the tunnelled call times out and the SSH session dies. Verified that
the same machine reaches S3 fine directly (`aws s3 ls` succeeds with no tunnel) — the hijack is the
sole cause.

Only two consumers actually need the tunnel, and the codebase already shows the right pattern:
- `KubernetesClientFactory` reads the port and sets `Config.httpsProxy = socks5://...` explicitly.
- `VictoriaStreamService` already uses `Proxy.NO_PROXY` for external URLs.
The only consumer relying on the global property is `ProxiedHttpClientFactory` (OkHttp via
`ProxySelector.getDefault()`).

## Goals / Non-Goals

**Goals:**
- AWS SDK (and any non-cluster traffic) connects directly, always.
- Cluster-internal traffic (K3s API, VictoriaMetrics/Logs) still tunnels when the proxy is active.
- No change to *when* the proxy starts (REQ-NET-005 timing is unchanged).

**Non-Goals:**
- Reworking how the proxy process is launched or reused.
- Remote-DNS-over-SOCKS for S3 (unnecessary — S3 is reachable directly).
- Changing the shell `env.sh` wrappers (they use `SOCKS5_PROXY_PORT` from the state file).

## Decisions

### Publish the port under a private property; never set `socksProxyHost`
`ProcessSocksProxyService` sets only `easydblab.socks5Port` (a private name `java.net` ignores).
Setting the port alone under the standard `socksProxyPort` without `socksProxyHost` would also be
inert, but a private name is unambiguous and prevents any library from interpreting it as a global
SOCKS directive.

*Alternative considered:* inject `SocksProxyService.getLocalPort()` into the two factories instead of
a system property. Cleaner long-term, but a larger DI change; the current code already reads a system
property, so swapping the property name is the minimal, low-risk change. Left as future cleanup.

### OkHttp configures SOCKS via an explicit `ProxySelector`
`ProxiedHttpClientFactory` installs a `ProxySelector` that returns a SOCKS proxy built from
`easydblab.socks5Port` when present, else `NO_PROXY`. This is per-client and dynamic (mirrors the old
global `ProxySelector` behavior) so it survives the proxy starting after the client is built, while
affecting only these cluster HTTP clients.

### AWS clients need no change
With no global `socksProxyHost`, the AWS SDK's default sockets go direct. No HTTP-client pinning or
proxy configuration is required on the AWS clients.

### Tailscale: no proxy, all direct (falls out of the design)
When Tailscale is enabled, `ensureProxyRunning()` already returns early, so the proxy never starts
and `easydblab.socks5Port` is never published. Both the K8s client and the OkHttp `ProxySelector`
treat an absent port as `NO_PROXY`, so all traffic — cluster-internal and AWS — connects directly,
using Tailscale's private network for cluster access. No extra Tailscale-specific code is needed; the
"absent port ⇒ direct" rule covers it.

## Risks / Trade-offs

- [A new ad-hoc `OkHttpClient` created elsewhere without going through the factory would not be
  tunnelled] → audit for stray OkHttp clients; route cluster HTTP through the factory.
- [Something external reads the standard `socksProxyPort`] → we no longer set it; shell wrappers use
  `SOCKS5_PROXY_PORT` from the state file (unaffected), and no other in-repo reader exists.

## Migration Plan

None required — clusters are ephemeral. `Down` clears the private property; a stale standard
`socksProxyHost`/`socksProxyPort` from a previous build is harmless and also cleared on `Down`.
