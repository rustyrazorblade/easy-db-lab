## MODIFIED Requirements

### Requirement: SOCKS Proxy
The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding for routing traffic to internal cluster services when Tailscale is not active. When `tailscaleActive` is `true` in cluster state, the SOCKS proxy SHALL NOT be started or used; all traffic routes directly to cluster private IPs instead.

#### Scenario: SOCKS proxy used without Tailscale
- **WHEN** a component needs to reach internal cluster services and `tailscaleActive` is `false`
- **THEN** a SOCKS proxy is established through an SSH tunnel to a cluster node

#### Scenario: SOCKS proxy skipped with Tailscale active
- **WHEN** a component needs to reach internal cluster services and `tailscaleActive` is `true`
- **THEN** no SOCKS proxy is started and connections are made directly to private IPs

#### Scenario: SOCKS proxy persists in long-running mode without Tailscale
- **GIVEN** the REPL or server is running and `tailscaleActive` is `false`
- **WHEN** the proxy is needed
- **THEN** it persists for the lifetime of the session rather than per-command
