## Why

A kit's `sql` subcommand (`postgres sql`, `clickhouse sql`, Presto/Trino `sql`) builds a JDBC URL against the endpoint's **private IP** and connects directly. On a SOCKS-only cluster (Tailscale disabled), the private IP is reachable **only** through the `ssh -D` SOCKS5 tunnel, so the connection fails — `sql` works today only when Tailscale provides direct private-IP access. This closes that gap without leaking proxy concerns into individual kits and without reintroducing the JVM-global proxy properties that caused the #725 incident.

## What Changes

- Introduce an **in-process SOCKS→loopback TCP bridge** that reuses the **existing** `ssh -D` tunnel (no new tunnel, no new `ssh` process). It binds a loopback `ServerSocket` and, per connection, dials the target cluster node through the tunnel via a scoped `java.net.Proxy(SOCKS, …)`, pumping bytes both directions.
- `KitJdbcSqlService` gates on `Constants.Proxy.PORT_PROPERTY`: when a SOCKS port is published it opens a bridge and rewrites **only** the URL authority (`privateIp:port` → `127.0.0.1:<ephemeralPort>`) so the driver connects to loopback with a plain, proxy-unaware URL; the bridge is closed in `finally`. When no port is published (Tailscale active, or no proxy), it connects directly to the private IP with **unchanged behavior**.
- The mechanism is **transport-only and driver-agnostic** — no `when(scheme)` branch, nothing about SOCKS in `kit.yaml`. It works for raw-TCP drivers (postgresql, mysql) and HTTP drivers (trino, presto, clickhouse) alike, because every JDBC driver opens a TCP socket to the host:port in its URL.
- Add `@RequiresProxy` (no `tolerateFailure`) to `KitSqlCommand` so the tunnel is established and verified before `execute()` runs.
- The JVM-global `socksProxyHost`/`socksProxyPort` properties are **never** touched — the SOCKS `Proxy` is scoped to the bridge's own sockets, so the AWS SDK and all other traffic are unaffected by construction.

## Capabilities

### New Capabilities
<!-- none — this is a transport-layer change to existing behavior -->

### Modified Capabilities
- `networking`: REQ-NET-005 (SOCKS Proxy) gains a requirement that the in-process loopback bridge is the sanctioned mechanism for routing raw-TCP (and generic) JDBC clients through the tunnel without global proxy properties, and that the Tailscale / no-proxy path connects directly, unchanged.
- `kit-capabilities`: the `sql` capability connects through the SOCKS tunnel (via the bridge) when a proxy port is published, and directly otherwise — a behavior change to how `sql` reaches the endpoint.

## Impact

- **Code**: new `proxy/SocksTcpBridge` + `SocksTcpBridgeFactory` (interface + default impl, Koin-wired); `services/sql/KitJdbcSqlService` (gate + URL rewrite + bridge lifecycle, kept inside the service); `commands/kit/KitSqlCommand` (`@RequiresProxy`, inject the factory, stay thin).
- **Behavior**: `sql` works over SOCKS-only clusters; Tailscale and no-proxy paths unchanged.
- **Constraints**: honors the absolute `socksProxyHost`/`socksProxyPort` rule (CLAUDE.md, REQ-NET-005 / #725).
- **Tests**: unit (URL-rewrite + gating via injectable `JdbcConnectionFactory`), bridge unit test (echo server behind a local SOCKS5), integration (Postgres + SOCKS5 TestContainers).
- **Out of scope**: changing the `ssh -D` tunnel; per-driver native SOCKS properties (rejected); global/address-scoped `ProxySelector` (rejected); the #741 follow-ups.
