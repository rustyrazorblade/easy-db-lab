## Context

The `ssh -D` SOCKS5 tunnel (managed by `ProcessSocksProxyService`) is the only path to cluster-private IPs when Tailscale is disabled. Existing clients opt into it explicitly and per-client — Fabric8 via `config.httpsProxy = "socks5://127.0.0.1:{port}"`, cluster OkHttp via `Socks5ProxySelector`, the CQL driver via `SocksProxyNettyOptions` — all reading the published port from `Constants.Proxy.PORT_PROPERTY`. None set the JVM-global `socksProxyHost`/`socksProxyPort` properties; doing so captures every socket in the process (including the AWS SDK) and was removed in #725. That prohibition is now an absolute rule in `CLAUDE.md` and encoded in networking spec REQ-NET-005.

`KitJdbcSqlService.execute` builds a JDBC URL against the endpoint's private IP (`KitEndpoint.formatUrl` → `jdbc:$scheme://$ip:$port$path`) and hands it to an injectable `JdbcConnectionFactory`. Unlike the clients above, a generic JDBC driver has **no uniform mechanism** to be told "speak SOCKS5 to 127.0.0.1:port" — raw-TCP drivers (pgjdbc, mysql) open plain sockets that ignore `ProxySelector`, and HTTP drivers (trino, presto, clickhouse) each expose different proxy properties. So the tunnel exists but `sql` never uses it.

## Goals / Non-Goals

**Goals:**
- `sql` reaches the database over the existing SOCKS tunnel on Tailscale-disabled clusters.
- One generic mechanism covering every JDBC driver (raw-TCP and HTTP) with no per-kit/per-driver code and no SOCKS concepts in `kit.yaml`.
- Zero use of the JVM-global proxy properties; AWS SDK and other traffic untouched by construction.
- Tailscale / no-proxy paths connect directly, byte-for-byte unchanged.

**Non-Goals:**
- Changing the `ssh -D` tunnel or `ProcessSocksProxyService`.
- Supporting drivers via their native SOCKS options.
- Any global or address-scoped `ProxySelector`.
- The #741 follow-ups (control-node readiness, zombie-ssh leak).

## Decisions

### D1 — In-process SOCKS→loopback TCP bridge (over native driver options / ProxySelector)
A new `SocksTcpBridge` binds a `ServerSocket` on `127.0.0.1:0` (loopback only, OS-assigned ephemeral port). An acceptor daemon thread accepts each inbound connection and opens `Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))`, connects it to `(clusterPrivateIp, endpoint.port)`, and runs two daemon pump threads copying bytes each direction. It is a userspace port-forward: pure TCP, oblivious to the protocol riding on top.

Rationale: it is the *only* mechanism generic across all drivers, because every JDBC driver ultimately dials the host:port in its URL. The SOCKS `Proxy` object is scoped to the bridge's own sockets, so the global-property prohibition is satisfied *by construction* rather than by a runtime bet on which clients read the global.

- **Alternative — per-driver native SOCKS props / `SocketFactory` (rejected):** property names differ per driver, forcing a `when(scheme)` branch (rejected by the owner) or SOCKS config in `kit.yaml` (also rejected); and a `SocketFactory` doesn't cover the HTTP drivers. Partial coverage + per-kit coupling.
- **Alternative — address-scoped default `ProxySelector` (rejected):** doesn't cover raw-TCP pgjdbc at all (plain sockets ignore `ProxySelector.getDefault()`), and reintroduces process-global mutable proxy state — the #725 risk class.

### D2 — Gate on `Constants.Proxy.PORT_PROPERTY`; build the bridged URL directly
`KitJdbcSqlService` reads the published port. Absent → today's exact path (`formatUrl(privateIp)`, direct connect). Present → open a bridge to `(privateIp, endpoint.port)`, then build the bridged URL directly via `formatUrl(host = "127.0.0.1", port = bridge.localPort)`. Because the service owns the endpoint's scheme/path/database fields, it regenerates the URL against the loopback listener rather than string-substituting the authority out of an already-emitted URL — no substring parsing or `String.replace` is needed, and the result is scheme-agnostic by construction. `bridge.use { … }` closes it in `finally`.

Rationale: keeps the change transport-only and reuses the same "read PORT_PROPERTY, degrade to direct when absent" pattern the other clients use.

### D3 — `@RequiresProxy` on `KitSqlCommand` (no `tolerateFailure`)
The bridge's correctness depends on the tunnel being up and the port published. `@RequiresProxy` makes `DefaultCommandExecutor.checkRequirements()` establish and verify the tunnel before `execute()`. `tolerateFailure` stays false — `sql` mutates state on the target and must not proceed against a broken transport.

- **Alternative — start the tunnel lazily in the service (rejected):** duplicates the command-executor pre-flight and hides a transport dependency inside a service.

### D4 — Bridge lifecycle bounded to one `execute()`; daemon threads
`Server`/`Repl` are long-lived, so a listener/threads that outlived a command would accumulate. The bridge is opened and closed within a single `execute()` via `use{}`/`finally`; all threads are daemon so JVM exit is never blocked; the listener binds loopback-only so no other host can use the tunnel.

### D5 — Inject `SocksTcpBridgeFactory` via Koin
A `SocksTcpBridgeFactory` interface + default impl, registered in the proxy Koin module and injected into `KitJdbcSqlService` with a default (mirroring the existing injectable `JdbcConnectionFactory`). Keeps the transport seam swappable and test-mockable; `KitSqlCommand` stays a thin orchestrator.

## Risks / Trade-offs

- **We own the byte-pump/socket-cleanup code** → bounded to one small class; covered by a bridge unit test (echo server behind a local SOCKS5) and integration test (Postgres + SOCKS5 TestContainers).
- **Thread/socket leak if cleanup is wrong** (critical for Server/Repl) → `use{}`/`finally` closes the listener and tracks/closes live sockets; daemon threads; explicit test that nothing outlives the command.
- **Bridged URL could target the wrong authority** → deterministic because the service rebuilds the URL from the endpoint's own fields via `formatUrl(host, port)` rather than substituting text; unit-tested for each scheme.
- **Tunnel down at connect time** → `Socket(Proxy).connect` throws → bridge closes the inbound socket → driver surfaces `SQLException` → `Result.failure` → `Event.Sql.QueryError`. `@RequiresProxy` verifies the tunnel up front, so this is the degenerate case.
- **Multiple driver connections** (rare for one-shot `sql`) → the acceptor loop handles each inbound connection; each gets its own proxied outbound socket.

## Migration Plan

Additive and behind the `PORT_PROPERTY` gate — no migration. When no proxy is published the code path is identical to today. Rollback is reverting the change; the tunnel and all other clients are untouched.

## Open Questions

- None blocking. During implementation, confirm the loopback-bridge requirement wording in REQ-NET-005 with the owner when the spec delta is written.
