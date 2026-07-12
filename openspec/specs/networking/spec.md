# Networking

## Purpose

Manages access methods for reaching cluster nodes: SSH aliases, remote command execution, Tailscale VPN, a SOCKS5 proxy, and host discovery.

## Requirements

### REQ-NET-001: SSH Access with Aliases

The system MUST provide SSH access to all nodes with convenient shell aliases.

#### Scenario: Aliases available after sourcing environment

- **GIVEN** a running cluster
- **WHEN** the user sources the environment file
- **THEN** shell aliases are available for each node (e.g., c0, c1, s0).

#### Scenario: Invoking an alias opens a session

- **GIVEN** SSH aliases
- **WHEN** the user invokes an alias
- **THEN** an SSH session opens to the corresponding node using cluster-specific configuration.

### REQ-NET-002: SSH Key Distribution

The system MUST allow uploading additional authorized SSH keys to cluster nodes.

#### Scenario: Upload authorized keys

- **GIVEN** authorized key files in the local keys directory
- **WHEN** the user uploads keys
- **THEN** the keys are added to all cluster nodes for shared access.

### REQ-NET-003: Remote Command Execution

The system MUST allow executing arbitrary commands on cluster nodes via SSH.

#### Scenario: Run a command on targeted nodes

- **GIVEN** a running cluster
- **WHEN** the user executes a remote command with host filtering
- **THEN** the command runs on the targeted nodes and output is displayed.

#### Scenario: Per-node output is distinguished

- **GIVEN** multiple target nodes
- **WHEN** a command is executed
- **THEN** output from each node is distinguished (e.g., color-coded).

### REQ-NET-004: Tailscale VPN

The system MUST support Tailscale mesh VPN for secure access to cluster nodes.

#### Scenario: Start Tailscale

- **GIVEN** Tailscale credentials configured in the user profile
- **WHEN** the user starts Tailscale
- **THEN** the VPN daemon starts on cluster nodes with authentication and subnet route advertising.

#### Scenario: Check Tailscale status

- **GIVEN** a running Tailscale connection
- **WHEN** the user checks status
- **THEN** the VPN state is displayed.

#### Scenario: Stop Tailscale

- **GIVEN** a running Tailscale connection
- **WHEN** the user stops it
- **THEN** the VPN daemon is stopped on all nodes.

### REQ-NET-005: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as an alternative to Tailscale for routing traffic to internal cluster services.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and Tailscale not enabled
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes.

#### Scenario: Proxy auto-restarts after being killed

- **GIVEN** the SOCKS5 proxy OS process has been killed since the last invocation
- **WHEN** any CLI command is invoked next
- **THEN** the proxy is automatically restarted without user intervention.

#### Scenario: Proxy persists for session lifetime

- **GIVEN** the REPL or server is running
- **WHEN** the proxy is needed
- **THEN** it persists for the lifetime of the session rather than per-command.

#### Scenario: Tailscale enabled bypasses the proxy

- **GIVEN** Tailscale is enabled for the cluster
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is not started and connections use direct private IP access.

#### Scenario: Proxy port exported to shell wrappers

- **GIVEN** a running cluster
- **WHEN** the user sources `env.sh`
- **THEN** `SOCKS5_PROXY_PORT` is populated from the state file written by the CLI so shell wrappers (kubectl, helm, curl) use the correct port.

### REQ-NET-006: Host Discovery

The system MUST provide commands to list cluster hosts and retrieve IP addresses.

#### Scenario: List hosts

- **GIVEN** a running cluster
- **WHEN** the user lists hosts
- **THEN** all nodes are displayed with their roles and addresses.

#### Scenario: Retrieve host IP

- **GIVEN** a host alias
- **WHEN** the user requests its IP
- **THEN** the public or private IP is returned.
