## ADDED Requirements

### Requirement: JDBC clients route through the tunnel via an in-process loopback bridge

The system MUST provide a mechanism for JDBC clients — which have no uniform native way to speak SOCKS5 — to reach cluster-private database endpoints through the existing SOCKS5 tunnel, without setting, clearing, or otherwise touching the JVM-global `socksProxyHost`/`socksProxyPort` properties.

When a SOCKS proxy port is published, the system MUST establish an in-process loopback listener that forwards each accepted connection through the existing tunnel to the target cluster node, and the JDBC client MUST connect to that loopback listener rather than to the private IP directly. The listener MUST bind to the loopback interface only, MUST reuse the existing tunnel (it MUST NOT start a new SSH process or a second tunnel), and MUST be torn down when the invoking command completes, including on failure, so that nothing survives the command.

When no SOCKS proxy port is published (Tailscale active, or no proxy running), the system MUST connect directly to the private IP with unchanged behavior and MUST NOT create a loopback listener.

#### Scenario: JDBC connects through the loopback bridge when the proxy is active
- **GIVEN** a provisioned cluster with Tailscale disabled and the SOCKS5 proxy running with its port published
- **WHEN** a `sql` query is executed against a kit's JDBC endpoint
- **THEN** the client SHALL connect to an in-process loopback listener
- **AND** the connection SHALL be forwarded through the existing SOCKS5 tunnel to the endpoint's private IP
- **AND** the query SHALL succeed

#### Scenario: Direct connection when no proxy port is published
- **WHEN** a `sql` query is executed and no SOCKS proxy port is published (Tailscale active, or no proxy)
- **THEN** the client SHALL connect directly to the endpoint's private IP
- **AND** no loopback listener SHALL be created
- **AND** the behavior SHALL be identical to the pre-existing direct-connection path

#### Scenario: Global proxy properties are never used
- **WHEN** a `sql` query routes through the loopback bridge
- **THEN** the JVM-global `socksProxyHost` and `socksProxyPort` properties SHALL NOT be set, cleared, or modified at any point
- **AND** the SOCKS proxy SHALL be scoped to the bridge's own sockets so that non-cluster traffic (including the AWS SDK) is unaffected

#### Scenario: The bridge reuses the existing tunnel and does not leak
- **WHEN** the loopback bridge is created for a command
- **THEN** it SHALL reuse the existing SSH SOCKS tunnel and SHALL NOT start a new SSH process or tunnel
- **AND** the listener, its sockets, and its threads SHALL be closed when the command completes, including on failure

#### Scenario: Tunnel unreachable surfaces as a query failure
- **GIVEN** the SOCKS5 tunnel is not reachable when a proxied `sql` connection is attempted
- **WHEN** the bridge tries to forward the connection
- **THEN** the driver SHALL surface a connection failure
- **AND** the command SHALL report it as a query error rather than reporting success

### Requirement: Local kubectl/helm invoked by kit shell steps route through the tunnel via a per-command kubeconfig proxy-url

The system MUST route the local `kubectl` and `helm` binaries invoked by kit `type: shell` steps through the existing SOCKS5 tunnel when a proxy port is published, without setting, clearing, or otherwise touching the JVM-global `socksProxyHost`/`socksProxyPort` properties.

When a SOCKS proxy port is published, the system MUST provide those shell steps a kubeconfig whose cluster entry carries a `proxy-url: socks5://127.0.0.1:<port>` so that only kubectl/helm traverse the tunnel while other tools the step runs (e.g. `aws`, `curl`) connect directly. The proxied kubeconfig MUST be a derived temporary copy — the canonical workspace kubeconfig MUST NOT be modified in place, because the in-process fabric8 client reads that same file. The temporary kubeconfig MUST be removed when the invoking command completes, including on failure.

When no SOCKS proxy port is published (Tailscale active, or no proxy running), the system MUST point the shell steps at the unmodified workspace kubeconfig, byte-for-byte identical to the prior behavior, and MUST NOT create a temporary kubeconfig.

#### Scenario: kubectl/helm are proxied when the SOCKS tunnel is published
- **GIVEN** a provisioned cluster with Tailscale disabled and the SOCKS5 proxy running with its port published
- **WHEN** a kit lifecycle phase runs a `type: shell` step that invokes `kubectl` or `helm`
- **THEN** the step's `KUBECONFIG` SHALL point at a temporary kubeconfig whose cluster entry carries `proxy-url: socks5://127.0.0.1:<published-port>`
- **AND** the canonical workspace kubeconfig SHALL remain unmodified
- **AND** the temporary kubeconfig SHALL be deleted when the command completes

#### Scenario: Direct kubeconfig when no proxy port is published
- **WHEN** a kit `type: shell` step runs and no SOCKS proxy port is published (Tailscale active, or no proxy)
- **THEN** the step's `KUBECONFIG` SHALL point at the unmodified workspace kubeconfig
- **AND** no temporary kubeconfig SHALL be created
- **AND** the behavior SHALL be identical to the pre-existing direct path

#### Scenario: Global proxy properties are never used for kubectl/helm routing
- **WHEN** kit shell steps are routed through the tunnel via the per-command kubeconfig `proxy-url`
- **THEN** the JVM-global `socksProxyHost` and `socksProxyPort` properties SHALL NOT be set, cleared, or modified at any point
- **AND** only `kubectl`/`helm` (which read the proxied kubeconfig) SHALL traverse the tunnel, leaving other tools the step runs unaffected
