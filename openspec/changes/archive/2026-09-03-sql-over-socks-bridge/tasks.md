## 1. SocksTcpBridge (transport)

- [x] 1.1 Add `proxy/SocksTcpBridge` (`AutoCloseable`): bind `ServerSocket` on `127.0.0.1:0`; expose `localPort`; acceptor daemon thread accepts inbound connections and, per connection, opens `Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))` connected to `(targetHost, targetPort)`; two daemon pump threads copy bytes each direction.
- [x] 1.2 Implement idempotent `close()` that shuts the `ServerSocket` and closes all live inbound/outbound sockets (track them); ensure all threads are daemon so JVM exit is never blocked.
- [x] 1.3 On outbound connect/IO failure, close the paired inbound socket so the driver observes a reset (no silent hang).
- [x] 1.4 Add class-level KDoc explaining it is a userspace port-forward that reuses the existing tunnel and never touches global proxy properties.

## 2. DI factory

- [x] 2.1 Add `SocksTcpBridgeFactory` interface (`open(socksPort, targetHost, targetPort): SocksTcpBridge`) and default impl. DEVIATION: mirrored the `JdbcConnectionFactory` pattern exactly — a top-level `val defaultSocksTcpBridgeFactory` rather than a named `DefaultSocksTcpBridgeFactory` class.
- [ ] 2.2 Register the factory in the proxy Koin module. DEVIATION (per parent DI note): intentionally NOT registered in Koin — nothing else consumes it and the bridge is per-command (closed via `use{}`), so a Koin singleton would be wrong and an unused binding is noise. Injected via a constructor default instead, mirroring `JdbcConnectionFactory`.

## 3. Wire into the SQL path

- [x] 3.1 Inject `SocksTcpBridgeFactory` into `KitJdbcSqlService` with a default (mirror the existing injectable `JdbcConnectionFactory` default).
- [x] 3.2 In `KitJdbcSqlService.execute`: read `Constants.Proxy.PORT_PROPERTY`; when absent, keep today's direct path exactly; when present, open a bridge to `(privateIp, endpoint.port)`, rewrite only the URL authority `"$privateIp:${endpoint.port}"` → `"127.0.0.1:${bridge.localPort}"`, connect, and close the bridge in `finally` (`use{}`).
- [x] 3.3 Add `@RequiresProxy` (no `tolerateFailure`) to `KitSqlCommand`; import the annotation; keep the command a thin orchestrator (no bridge logic in the command).
- [ ] 3.4 Pass the injected factory from `KitSqlCommand` into `KitJdbcSqlService`. DEVIATION (per parent note): the command keeps constructing `KitJdbcSqlService` and the bridge factory defaults in — no need to thread the factory through, keeping the command thin.

## 4. Tests

- [x] 4.1 Unit test `KitJdbcSqlService` URL-rewrite + gating via the injectable `JdbcConnectionFactory`: capture the final URL — asserts `127.0.0.1:<bridge port>` when `PORT_PROPERTY` is set, and the unchanged private-IP URL when unset. Must not touch `socksProxyHost`.
- [x] 4.2 Unit test `SocksTcpBridge`. DEVIATION (per parent guidance): a hand-rolled SOCKS5 stub exceeds ~40 lines, so this suite covers lifecycle (bind/loopback, close-refuses-connect, idempotent close, daemon threads) plus outbound-connect-failure cleanup (no stub needed). The byte round-trip is covered end-to-end by 4.3.
- [x] 4.3 Integration test (TestContainers): Postgres container + a `serjs/go-socks5-proxy` container on a shared network; set `PORT_PROPERTY` to the proxy; assert a real query succeeds through the bridge (raw-TCP pgjdbc end-to-end). Executed green. FIX: `serjs/go-socks5-proxy:latest` now defaults `REQUIRE_AUTH=true` and exits 1 at startup with no credentials; pinned the image by digest and set `REQUIRE_AUTH=false` so the proxy runs open (the bridge connects with no SOCKS credentials).
- [x] 4.4 Assert the JVM-global `socksProxyHost`/`socksProxyPort` are never set by any test path. No test touches those properties; only the sanctioned `Constants.Proxy.PORT_PROPERTY` is set/cleared.

## 5. Spec + docs

- [ ] 5.1 Confirm the networking delta (loopback-bridge routing requirement) and the kit-capabilities delta wording with the owner.
- [x] 5.2 Update user docs (`docs/` networking / kit `sql` usage) to note `sql` works on SOCKS-only clusters transparently. Added a "SQL over SOCKS" subsection to `docs/user-guide/network-connectivity.md`.

## 6. Quality gate

- [x] 6.1 `./gradlew ktlintFormat` (BUILD SUCCESSFUL).
- [x] 6.2 Ran the targeted unit suites green (`KitJdbcSqlServiceTest`, `SocksTcpBridgeTest`); `detekt` on JDK 21 (Corretto 21) BUILD SUCCESSFUL with no new issues in touched files. Integration suite not executed (Docker daemon down).
- [ ] 6.3 Live-validate on a Tailscale-disabled SOCKS cluster: `postgres sql "SELECT 1"` returns 1 through the bridge.

## 7. kubectl/helm over SOCKS (kit shell steps)

The bridge only fixes `sql`. On a SOCKS-only cluster `kit <name> start` still hangs *before* the
DB is up, because kit `type: shell` steps run `kubectl`/`helm` on the laptop against a kubeconfig
whose server is the control node's private IP with no `proxy-url` — local kubectl can't reach it,
so pod-wait loops spin forever. Route those local binaries through the existing tunnel with a
per-command kubeconfig `proxy-url` (never a global property, never `HTTPS_PROXY`).

- [x] 7.1 Add `services/KubeconfigProxyResolver`: when `Constants.Proxy.PORT_PROPERTY` is absent (or the workspace kubeconfig is missing) return the workspace path unchanged; when present, parse→mutate→write a TEMP copy of the workspace kubeconfig with `proxy-url: socks5://127.0.0.1:<port>` on each cluster entry, using the same Jackson yaml mapper approach as `K3sService.downloadAndConfigureKubeconfig`. Never patch the canonical workspace kubeconfig in place. Return an `AutoCloseable` `ResolvedKubeconfig` that deletes the temp file on `close()`.
- [x] 7.2 Wire into `KitRunnerCommand`: inject the resolver via a constructor default (mirrors the `JdbcConnectionFactory`/`SocksTcpBridgeFactory` pattern — not Koin-registered, nothing else consumes it). `execute()` resolves the kubeconfig inside a `use { }` block (create-before, delete-after on success and failure) and passes the resolved path into `buildAugmentedEnv` at the `KUBECONFIG` seam.
- [x] 7.3 Resolve the SOCKS port at COMMAND time from `Constants.Proxy.PORT_PROPERTY` (dynamic; republished each proxy start). Safe here because `KitRunnerCommand` runs under `@RequiresProxy`, so the tunnel + property are live before `execute()`.
- [x] 7.4 Honor the absolute rule: never set/clear/touch `socksProxyHost`/`socksProxyPort`, and do NOT use `HTTPS_PROXY`/`HTTP_PROXY` env (would route `aws s3` shell calls through the tunnel — the #725 failure class). The per-kubeconfig `proxy-url` reaches only kubectl/helm.
- [x] 7.5 Tests: `KubeconfigProxyResolverTest` — unset→unchanged path + canonical file untouched; set→temp path with `proxy-url` on the cluster entry, other fields preserved, canonical untouched; missing kubeconfig→unchanged; `close()` deletes the temp; no path sets `socksProxyHost`/`socksProxyPort` (real yaml parsing, no mocked mapper). `KitRunnerCommandTest` — shell step `KUBECONFIG` points at the proxied temp copy (content carries the `proxy-url`) when the port is set, and at the unmodified workspace kubeconfig when unset.
- [x] 7.6 Spec: added the networking requirement (local kubectl/helm route through the tunnel via a per-command kubeconfig `proxy-url` when a port is published, directly otherwise, global props never touched) with WHEN/THEN scenarios. Docs: broadened the network-connectivity "Kit commands over SOCKS" section to cover kit lifecycle (kubectl/helm routing), not just `sql`.
- [ ] 7.7 Live-validate on a Tailscale-disabled SOCKS cluster: `postgres start` completes (nodeport applied) and `postgres sql "SELECT 1"` returns 1.
