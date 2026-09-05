# Networking

## Purpose

Manages access methods for reaching cluster nodes: SSH aliases, remote command execution, Tailscale VPN, a SOCKS5 proxy, and host discovery.
## Requirements
### Requirement: SSH Access with Aliases

The system MUST provide SSH access to all nodes with convenient shell aliases.

#### Scenario: Aliases available after sourcing environment

- **GIVEN** a running cluster
- **WHEN** the user sources the environment file
- **THEN** shell aliases are available for each node (e.g., c0, c1, s0).

#### Scenario: Invoking an alias opens a session

- **GIVEN** SSH aliases
- **WHEN** the user invokes an alias
- **THEN** an SSH session opens to the corresponding node using cluster-specific configuration.

### Requirement: SSH Key Distribution

The system MUST allow uploading additional authorized SSH keys to cluster nodes.

#### Scenario: Upload authorized keys

- **GIVEN** authorized key files in the local keys directory
- **WHEN** the user uploads keys
- **THEN** the keys are added to all cluster nodes for shared access.

### Requirement: Remote Command Execution

The system MUST allow executing arbitrary commands on cluster nodes via SSH.

#### Scenario: Run a command on targeted nodes

- **GIVEN** a running cluster
- **WHEN** the user executes a remote command with host filtering
- **THEN** the command runs on the targeted nodes and output is displayed.

#### Scenario: Per-node output is distinguished

- **GIVEN** multiple target nodes
- **WHEN** a command is executed
- **THEN** output from each node is distinguished (e.g., color-coded).

### Requirement: Tailscale VPN

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

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path to internal cluster services when Tailscale is not active. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `down` is called; its PID and port are recorded in `.socks5-proxy-state`.

The proxy MUST be started eagerly by the command executor rather than lazily by individual services: when cluster state exists, infrastructure is UP, and Tailscale is not active, the executor SHALL start (or reuse) the proxy before any command logic executes. Because starting the proxy is idempotent and the process persists, startup MUST be attempted unconditionally on every command invocation under those conditions, and no individual service SHALL be responsible for starting it.

When `tailscaleActive` is `true` in cluster state, the SOCKS proxy SHALL NOT be started or used; all traffic connects directly to cluster private IPs.

The `env.sh` environment file MUST NOT start the proxy itself. It SHALL read `SOCKS5_PROXY_PORT` from the proxy state file written by the CLI so that shell wrappers use the same port as the Kotlin CLI, rather than hardcoding a port.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and Tailscale not active
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes

#### Scenario: Proxy started as a detached process when Tailscale is not configured

- **GIVEN** a cluster whose `state.json` has `tailscaleActive: false`
- **WHEN** any component needs to reach internal cluster services
- **THEN** a SOCKS5 proxy is started via `ssh -N -D <port> -F sshConfig control0` as a detached OS process
- **AND** its PID and port are written to `.socks5-proxy-state`

#### Scenario: Proxy reused across invocations

- **GIVEN** `.socks5-proxy-state` exists with a live PID, matching `controlIP`, and matching `sshConfig` path
- **WHEN** a new easy-db-lab invocation calls `ensureRunning()`
- **THEN** the existing SSH process is reused rather than a new one being started

#### Scenario: Stale proxy replaced without user intervention

- **GIVEN** `.socks5-proxy-state` exists but the recorded PID is no longer alive
- **WHEN** any CLI command is invoked next
- **THEN** a new SSH proxy process is started automatically and `.socks5-proxy-state` is updated

#### Scenario: Proxy skipped when Tailscale is configured

- **GIVEN** a cluster whose `state.json` has `tailscaleActive: true`
- **WHEN** any component needs to reach internal cluster services
- **THEN** no SOCKS proxy is started and connections are made directly to cluster private IPs

#### Scenario: Tailscale detection is profile-based

- **WHEN** the user runs `init`
- **THEN** `tailscaleActive` is set to `true` if and only if `tailscaleClientId` and `tailscaleClientSecret` are both configured in the user profile
- **AND** the `--no-tailscale` flag overrides this, forcing `tailscaleActive: false` regardless of credentials

#### Scenario: Proxy skipped when cluster is not provisioned

- **GIVEN** no cluster state file exists or infrastructure is not UP
- **WHEN** a CLI command is invoked
- **THEN** the SOCKS5 proxy is not started

#### Scenario: Proxy persists for session lifetime

- **GIVEN** the REPL or server is running and `tailscaleActive` is `false`
- **WHEN** the proxy is needed
- **THEN** it persists for the lifetime of the session rather than per-command

#### Scenario: Proxy port exported to shell wrappers from state file

- **GIVEN** a running cluster whose proxy state file records the active port
- **WHEN** the user sources `env.sh`
- **THEN** `SOCKS5_PROXY_PORT` is populated from the state file written by the CLI (not started by `env.sh`), so shell wrappers such as kubectl, helm, and curl use the correct port

#### Scenario: Proxy cleaned up on teardown

- **GIVEN** `.socks5-proxy-state` exists with an active PID
- **WHEN** `down` is run
- **THEN** the SSH proxy process is killed and `.socks5-proxy-state` is deleted

### Requirement: Host Discovery

The system MUST provide commands to list cluster hosts and retrieve IP addresses.

#### Scenario: List hosts

- **GIVEN** a running cluster
- **WHEN** the user lists hosts
- **THEN** all nodes are displayed with their roles and addresses.

#### Scenario: Retrieve host IP

- **GIVEN** a host alias
- **WHEN** the user requests its IP
- **THEN** the public or private IP is returned.

### Requirement: SOCKS Proxy Routes Only Cluster-Internal Traffic

The SOCKS5 proxy MUST route only cluster-internal traffic (the K3s API and other private cluster services). It MUST NOT capture traffic to public endpoints — in particular AWS SDK calls (S3, EC2, IAM, STS) MUST connect directly, out the host's normal network path, regardless of whether the proxy is running.

The system MUST NOT enable the proxy by setting the standard JVM-global `socksProxyHost`/`socksProxyPort` properties, because those route every socket the process opens through the tunnel. Instead, the active proxy port is published privately, and only the clients that need the tunnel (the Kubernetes client and the cluster HTTP client) configure the SOCKS proxy explicitly.

#### Scenario: AWS calls go direct while the proxy is running

- **GIVEN** a provisioned cluster with infrastructure UP and the SOCKS5 proxy active
- **WHEN** the CLI makes an AWS SDK call (e.g. S3 backup, EC2 describe, IAM policy update)
- **THEN** the call connects directly to the AWS endpoint and is not routed through the SSH tunnel.

#### Scenario: Cluster-internal traffic still uses the tunnel

- **GIVEN** the SOCKS5 proxy is active and Tailscale is not enabled
- **WHEN** the CLI reaches the K3s API or a private cluster service (e.g. VictoriaMetrics/VictoriaLogs on the control node)
- **THEN** that traffic is routed through the SOCKS proxy via explicit per-client configuration.

#### Scenario: Tailscale active means no proxy and all-direct routing

- **GIVEN** Tailscale is enabled for the cluster, so the SOCKS5 proxy is never started and the proxy port is not published
- **WHEN** the CLI reaches the K3s API, a private cluster service, or any AWS endpoint
- **THEN** every connection is direct — the K8s client and the cluster HTTP client both select no proxy, using Tailscale's private network for cluster traffic.

#### Scenario: State backup/restore works on a network that blocks the tunnel path

- **GIVEN** a network where the account S3 bucket is reachable directly from the operator's machine but the cluster tunnel cannot reach it
- **WHEN** the CLI backs up or restores cluster state to/from S3
- **THEN** the S3 traffic connects directly and succeeds, because it is never forced through the tunnel.

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

