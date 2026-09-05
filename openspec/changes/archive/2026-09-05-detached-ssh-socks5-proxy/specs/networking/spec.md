## MODIFIED Requirements

### Requirement: SOCKS Proxy (REQ-NET-005)
The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path when Tailscale is not configured. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `down` is called.

When `isTailscaleEnabled()` is `true` in cluster state (Tailscale credentials configured at init time), the SOCKS proxy SHALL NOT be started; all traffic connects directly to cluster private IPs.

#### Scenario: Proxy started when Tailscale is not configured
- **GIVEN** a cluster whose `state.json` has `tailscaleActive: false`
- **WHEN** any component needs to reach internal cluster services
- **THEN** a SOCKS5 proxy is started via `ssh -N -D <port> -F sshConfig control0` as a detached OS process, and its PID and port are written to `.socks5-proxy-state`

#### Scenario: Proxy reused across invocations
- **GIVEN** `.socks5-proxy-state` exists with a live PID, matching `controlIP`, and matching `sshConfig` path
- **WHEN** a new easy-db-lab invocation calls `ensureRunning()`
- **THEN** the existing SSH process is reused rather than a new one being started

#### Scenario: Stale proxy replaced
- **GIVEN** `.socks5-proxy-state` exists but the recorded PID is no longer alive
- **WHEN** any component calls `ensureRunning()`
- **THEN** a new SSH proxy process is started and `.socks5-proxy-state` is updated

#### Scenario: Proxy skipped when Tailscale is configured
- **GIVEN** a cluster whose `state.json` has `tailscaleActive: true`
- **WHEN** any component needs to reach internal cluster services
- **THEN** no SOCKS proxy is started and connections are made directly to cluster private IPs

#### Scenario: Tailscale detection is profile-based
- **WHEN** the user runs `init`
- **THEN** `tailscaleActive` is set to `true` if and only if Tailscale credentials (`tailscaleClientId` and `tailscaleClientSecret`) are both configured in the user profile
- **AND** `--no-tailscale` flag overrides this, forcing `tailscaleActive: false` regardless of credentials

#### Scenario: Proxy cleaned up on teardown
- **GIVEN** `.socks5-proxy-state` exists with an active PID
- **WHEN** `down` is run
- **THEN** the SSH proxy process is killed and `.socks5-proxy-state` is deleted
