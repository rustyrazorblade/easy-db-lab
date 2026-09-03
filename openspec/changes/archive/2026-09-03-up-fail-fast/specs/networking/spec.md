## MODIFIED Requirements

### REQ-NET-005: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as an alternative to Tailscale for routing traffic to internal cluster services.

The proxy MUST be started only for commands that declare a dependency on it. A command that does not declare the dependency MUST NOT cause a proxy to be started.

The system MUST NOT publish a proxy port that is not accepting connections. Establishing the proxy MUST be verified before the port is advertised to any client, and a proxy that cannot be verified MUST fail the invoking command rather than allowing it to proceed against a dead tunnel.

#### Scenario: Proxy starts for commands that require it
- **WHEN** a command declaring a proxy dependency is invoked against a provisioned cluster with infrastructure UP and Tailscale not enabled
- **THEN** the SOCKS5 proxy SHALL be started, or reused if already running, before any command logic executes

#### Scenario: Proxy is not started for commands that do not require it
- **WHEN** a command that does not declare a proxy dependency is invoked
- **THEN** no SOCKS5 proxy SHALL be started, regardless of cluster state
- **AND** teardown SHALL NOT start a tunnel it then destroys

#### Scenario: Killed proxy is restarted automatically
- **WHEN** the SOCKS5 proxy OS process has been killed since the last invocation and a command requiring the proxy is invoked
- **THEN** the proxy SHALL be restarted without user intervention

#### Scenario: Proxy that cannot be established fails the command
- **WHEN** the SOCKS5 proxy is started but its local port never begins accepting connections within the verification window
- **THEN** the invoking command SHALL fail with a non-zero exit code
- **AND** the proxy port SHALL NOT be published to any client
- **AND** the error SHALL identify the proxy as the failing component and reference the SSH transcript log

#### Scenario: A live process with a dead tunnel is not reused
- **WHEN** a recorded proxy process is still alive but its local port is not accepting connections
- **THEN** the proxy SHALL NOT be reused
- **AND** its port SHALL NOT be published
- **AND** a fresh proxy SHALL be started, or the command SHALL fail if one cannot be established
- **AND** this SHALL hold for every path that may reuse a proxy, including an in-memory handle held by a long-lived REPL or server session

#### Scenario: A proxy that fails verification leaves no orphaned process
- **WHEN** a proxy process is spawned but its local port never begins accepting connections
- **THEN** the spawned process SHALL be terminated before the failure is reported
- **AND** no untracked SSH process SHALL survive the failed command

#### Scenario: Proxy connects to the recorded gateway host
- **WHEN** the SOCKS5 proxy is started for a given control host
- **THEN** the SSH tunnel SHALL be established to that host's alias as recorded in cluster state, not to a hardcoded alias

#### Scenario: Proxy persists for long-lived sessions
- **WHEN** the REPL or server is running and the proxy is needed
- **THEN** the proxy SHALL persist for the lifetime of the session rather than per-command

#### Scenario: Tailscale bypasses the proxy
- **WHEN** Tailscale is enabled for the cluster and any CLI command is invoked
- **THEN** the SOCKS5 proxy SHALL NOT be started
- **AND** connections SHALL use direct private IP access

#### Scenario: Shell wrappers read the proxy port
- **WHEN** the user sources `env.sh` for a running cluster
- **THEN** `SOCKS5_PROXY_PORT` SHALL be populated from the state file written by the CLI so shell wrappers (kubectl, helm, curl) use the correct port

## ADDED Requirements

### Requirement: Generated SSH configuration is self-contained

The SSH configuration the system generates for a cluster MUST fully determine how the `ssh` CLI verifies host keys, and MUST NOT depend on, read from, or write to the developer's personal `~/.ssh/known_hosts` file.

Cluster instances are ephemeral and are assigned fresh host keys on every provision, while AWS recycles public IP addresses. Host-key state carried across cluster lifetimes therefore causes spurious verification failures. The generated configuration MUST behave consistently with the library SSH path used everywhere else in the system.

#### Scenario: Generated config pins the known-hosts file
- **WHEN** the system writes the cluster's SSH configuration
- **THEN** the configuration SHALL set `UserKnownHostsFile` to `/dev/null` alongside `StrictHostKeyChecking=no`

#### Scenario: Recycled public IP does not break the tunnel
- **GIVEN** a cluster node is assigned a public IP previously recorded in the developer's `~/.ssh/known_hosts` under a different host key
- **WHEN** the system establishes the SOCKS5 tunnel to that node using the generated SSH configuration
- **THEN** the tunnel SHALL be established successfully
- **AND** `ssh` SHALL NOT fail host-key verification

#### Scenario: Developer known_hosts is not modified
- **WHEN** the system connects to any cluster node using the generated SSH configuration
- **THEN** no entry SHALL be added to the developer's `~/.ssh/known_hosts`
