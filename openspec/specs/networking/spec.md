# Networking

Manages access methods for reaching cluster nodes: SSH aliases and Tailscale VPN.

## Requirements

### REQ-NET-001: SSH Access with Aliases

The system MUST provide SSH access to all nodes with convenient shell aliases.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user sources the environment file, **THEN** shell aliases are available for each node (e.g., c0, c1, s0).
- **GIVEN** SSH aliases, **WHEN** the user invokes an alias, **THEN** an SSH session opens to the corresponding node using cluster-specific configuration.

### REQ-NET-002: SSH Key Distribution

The system MUST allow uploading additional authorized SSH keys to cluster nodes.

**Scenarios:**

- **GIVEN** authorized key files in the local keys directory, **WHEN** the user uploads keys, **THEN** the keys are added to all cluster nodes for shared access.

### REQ-NET-003: Remote Command Execution

The system MUST allow executing arbitrary commands on cluster nodes via SSH.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user executes a remote command with host filtering, **THEN** the command runs on the targeted nodes and output is displayed.
- **GIVEN** multiple target nodes, **WHEN** a command is executed, **THEN** output from each node is distinguished (e.g., color-coded).

### REQ-NET-004: Tailscale VPN

The system MUST support Tailscale mesh VPN for secure access to cluster nodes.

**Scenarios:**

- **GIVEN** Tailscale credentials configured in the user profile, **WHEN** the user starts Tailscale, **THEN** the VPN daemon starts on cluster nodes with authentication and subnet route advertising.
- **GIVEN** a running Tailscale connection, **WHEN** the user checks status, **THEN** the VPN state is displayed.
- **GIVEN** a running Tailscale connection, **WHEN** the user stops it, **THEN** the VPN daemon is stopped on all nodes.

### REQ-NET-005: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as an alternative to Tailscale for routing traffic to internal cluster services.

**Scenarios:**

- **GIVEN** a running cluster without Tailscale, **WHEN** a component needs to reach internal cluster services (e.g., CQL, HTTP APIs), **THEN** a SOCKS proxy is established through an SSH tunnel to a cluster node.
- **GIVEN** the REPL or MCP server is running, **WHEN** the proxy is needed, **THEN** it persists for the lifetime of the session rather than per-command.

### REQ-NET-006: Host Discovery

The system MUST provide commands to list cluster hosts and retrieve IP addresses.

**Scenarios:**

- **GIVEN** a running cluster, **WHEN** the user lists hosts, **THEN** all nodes are displayed with their roles and addresses.
- **GIVEN** a host alias, **WHEN** the user requests its IP, **THEN** the public or private IP is returned.
