## MODIFIED Requirements

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as an alternative to Tailscale for routing traffic to internal cluster services. The proxy MUST be started eagerly at command startup rather than lazily by individual services: when the cluster state exists, infrastructure is UP, and Tailscale is not enabled, the command executor SHALL start (or reuse) the proxy before any command logic executes. Because starting the proxy is idempotent and the process persists as a detached OS process, it MUST be started unconditionally on every command invocation under these conditions, and no individual service SHALL be responsible for starting it.

The `env.sh` environment file MUST NOT start the proxy itself. It SHALL read `SOCKS5_PROXY_PORT` from the proxy state file written by the CLI (via `jq`) so that shell wrappers use the same port as the Kotlin CLI, rather than hardcoding a port.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and Tailscale not enabled
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes.

#### Scenario: Proxy auto-restarts after being killed

- **GIVEN** the SOCKS5 proxy OS process has been killed since the last invocation
- **WHEN** any CLI command is invoked next
- **THEN** the proxy is automatically restarted without user intervention.

#### Scenario: Tailscale enabled bypasses the proxy

- **GIVEN** Tailscale is enabled for the cluster
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is not started and connections use direct private IP access.

#### Scenario: Proxy skipped when cluster is not provisioned

- **GIVEN** no cluster state file exists or infrastructure is not UP
- **WHEN** a CLI command is invoked
- **THEN** the SOCKS5 proxy is not started.

#### Scenario: Proxy persists for session lifetime

- **GIVEN** the REPL or server is running
- **WHEN** the proxy is needed
- **THEN** it persists for the lifetime of the session rather than per-command.

#### Scenario: Proxy port exported to shell wrappers from state file

- **GIVEN** a running cluster whose proxy state file records the active port
- **WHEN** the user sources `env.sh`
- **THEN** `SOCKS5_PROXY_PORT` is populated from the state file written by the CLI (not started by `env.sh`), so shell wrappers such as kubectl, helm, and curl use the correct port.
