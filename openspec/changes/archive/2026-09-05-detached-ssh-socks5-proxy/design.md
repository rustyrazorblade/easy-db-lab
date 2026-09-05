## Context

All cluster traffic routes through a SOCKS5 proxy established via SSH dynamic port forwarding. `MinaSocksProxyService` managed this in-process using Apache MINA's `startDynamicPortForwarding()`. The proxy died with the JVM, so every command paid the SSH connection setup cost.

`tailscaleActive` was set at `init` time by running `tailscale status --json`. This checks whether Tailscale is currently connected at that moment — not whether the cluster was configured to use Tailscale. A user who configured Tailscale credentials but ran `init` from a machine that wasn't connected would get `tailscaleActive: false` permanently.

## Goals / Non-Goals

**Goals:**
- SOCKS proxy process persists across JVM restarts; reused on subsequent commands
- Tailscale routing based on whether credentials are configured in the user profile
- Proxy routing configured once at the JVM level (system properties) rather than per-client
- Cassandra driver continues to use explicit Netty SOCKS config (Netty does not use `java.net.Socket`)
- External-target OkHttp clients explicitly opt out of system proxy with `Proxy.NO_PROXY`

**Non-Goals:**
- Changing the `.socks5-proxy-state` file format (must remain compatible with `Down.kt` cleanup)
- Changing how `Down.kt` kills the proxy process on teardown
- Changing the Cassandra driver SOCKS proxy mechanism (`SocksProxySessionBuilder`, `SocksProxyDriverContext`)

## Decisions

### ProcessBuilder + nohup for detached SSH process

`nohup ssh -v -N -D <port> -F sshConfig control0` is launched via `ProcessBuilder` with:
- stdin → `/dev/null`
- stdout → `/dev/null`
- stderr appended to `socks5-proxy.log` in the cluster working directory (`-v` captures connection and keepalive events for diagnostics)

`inheritIO()` is NOT used. The process is not attached to the JVM's process group and will not be killed on JVM exit.

### `.socks5-proxy-state` for cross-invocation reuse

On `ensureRunning()`, the service reads `.socks5-proxy-state` and validates:
1. PID is alive (`ProcessHandle.of(pid).isPresent`)
2. `sshConfig` path matches `File(context.workingDirectory, "sshConfig").absolutePath`
3. `controlIP` matches `gatewayHost.privateIp`

If all three hold, the existing proxy is reused and system properties are set from the loaded state. Otherwise, a new process is started.

The `Socks5ProxyStateFile` data class is moved from an inner class in `Down.kt` (Jackson) to a shared class in the `proxy` package using kotlinx.serialization. Both `ProcessSocksProxyService` (writer) and `Down.kt` (reader) use this shared class.

### JVM system properties for proxy routing

When the proxy starts: `System.setProperty("socksProxyHost", "127.0.0.1")` and `System.setProperty("socksProxyPort", "$port")`. Java socket-layer networking routes through the SOCKS proxy via `ProxySelector.getDefault()`. OkHttp picks this up automatically when no explicit `.proxy()` is set on the client builder.

**Cassandra driver exception:** Netty does not use `java.net.Socket`. `SocksProxySessionBuilder` and `SocksProxyDriverContext` remain, with the port sourced from `socksProxyService.getLocalPort()`.

**External connection exception:** `VictoriaStreamService.directClient` connects to user-supplied external Victoria URLs. It sets `.proxy(Proxy.NO_PROXY)` explicitly to bypass the system proxy.

**fabric8 / K8s client:** `ProxiedKubernetesClientFactory` reads `System.getProperty("socksProxyPort")` and sets `config.httpsProxy = "socks5://127.0.0.1:$port"` when present. This handles the case where fabric8 overrides `ProxySelector.getDefault()` through its own HTTP client configuration.

### Profile-based Tailscale detection

`User.isTailscaleEnabled()` returns `tailscaleClientId.isNotBlank() && tailscaleClientSecret.isNotBlank()`. This is a stable configuration check. The `--no-tailscale` flag on `Init` allows override when intentionally bypassing VPN (e.g., debugging, or credentials configured but VPN not yet set up on cluster nodes).

### Remove `stop()`

The proxy is an OS process that outlives the JVM. `Down.kt` handles cleanup via `cleanupSocks5Proxy()`. There is no valid "stop this session's proxy" concept — the process belongs to the cluster, not to any individual command invocation.

### Remove `SocksProxyState.connectionCount`

`connectionCount: AtomicInteger` was added for server-mode usage tracking. With a persistent OS process there is no per-request lifecycle to track. An `AtomicInteger` inside a `data class` is also semantically odd since data class `equals`/`hashCode` operate on fields by reference.

## Risks / Trade-offs

- **Port 1080 already in use:** If another process holds port 1080, the service selects an OS-assigned port via `ServerSocket(0)`. The port is persisted in `.socks5-proxy-state` and set as a system property so all clients use the correct port.
- **Stale state file with PID reuse:** A PID in `.socks5-proxy-state` could be alive but belong to a different process (OS PID reuse). The proxy-accepting-connections verification after startup catches this edge case for new processes; for existing reuse, the tunnel either works or the first actual connection fails (same outcome as today with Mina).
- **External connections routed through proxy:** Any OkHttp client built without `.proxy(Proxy.NO_PROXY)` routes through the SOCKS proxy when system properties are set. `VictoriaStreamService.directClient` is the only known external-target client; it is explicitly opted out.
