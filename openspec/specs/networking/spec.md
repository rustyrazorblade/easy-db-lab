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

#### Scenario: `up` waits for the tailnet route before K3s

- **GIVEN** a cluster with Tailscale enabled
- **WHEN** `up` has started Tailscale on the control node
- **THEN** it probes the control node's private IP on port 22 from this machine, up to 24 times 5 seconds apart, and emits a progress event on each retry
- **AND** it proceeds to K3s as soon as one probe succeeds
- **AND** if every probe fails, it aborts with a message that names the subnet route to approve

### Requirement: SOCKS Proxy

The system MUST support a SOCKS5 proxy via SSH dynamic port forwarding as the access path to internal cluster services when Tailscale is not active. The proxy runs as a detached OS process that persists across JVM restarts, shared across invocations until `down` or `socks stop` is called; its PID and port are recorded in `.socks5-proxy-state`. The proxy state file MUST be written atomically, so a reader never sees a partial file.

The proxy MUST be started eagerly by the command executor rather than lazily by individual services: when cluster state exists, infrastructure is UP, and Tailscale is not active, the executor SHALL start (or reuse) the proxy before any command logic executes. Because starting the proxy is idempotent and the process persists, startup MUST be attempted unconditionally on every command invocation under those conditions, and no individual service SHALL be responsible for starting it.

When `tailscaleActive` is `true` in cluster state, the SOCKS proxy SHALL NOT be started or used; all traffic connects directly to cluster private IPs.

The CLI is the only component that starts or stops the proxy. `env.sh` and the tool wrappers MUST NOT start or stop it. Shell-side tools learn the active port only from the proxy env file, which the CLI updates each time it verifies, starts, or stops the tunnel.

#### Scenario: Proxy starts before command logic

- **GIVEN** a provisioned cluster with infrastructure UP and Tailscale not active
- **WHEN** any CLI command is invoked
- **THEN** the SOCKS5 proxy is started (or reused if already running) before any command logic executes

#### Scenario: Proxy started as a detached process when Tailscale is not configured

- **GIVEN** a cluster whose `state.json` has `tailscaleActive: false`
- **WHEN** any component needs to reach internal cluster services
- **THEN** a SOCKS5 proxy is started as a detached OS process through `<workspace>/bin/edl-socks-tunnel`, which runs `ssh -N -D 127.0.0.1:<port> -F sshConfig control0`
- **AND** the PID of `edl-socks-tunnel` and the port are written to `.socks5-proxy-state`
- **AND** once the tunnel is verified, its port is written to the proxy env file

#### Scenario: Proxy reused across invocations

- **GIVEN** `.socks5-proxy-state` exists with a live PID, matching `controlIP`, and matching `sshConfig` path
- **WHEN** a new easy-db-lab invocation calls `ensureRunning()`
- **THEN** the existing SSH process is reused rather than a new one being started
- **AND** its port is written to the proxy env file

#### Scenario: Stale proxy replaced without user intervention

- **GIVEN** `.socks5-proxy-state` exists but the recorded PID is no longer alive
- **WHEN** any CLI command is invoked next
- **THEN** a new SSH proxy process is started automatically and `.socks5-proxy-state` is updated
- **AND** the proxy env file records the new port

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

#### Scenario: Shell tools read the port from the env file

- **GIVEN** a running cluster whose proxy env file records the active port
- **WHEN** the user sources `env.sh` and runs a wrapped tool or `with-proxy`
- **THEN** the port comes from the proxy env file written by the CLI
- **AND** `env.sh` does not start the proxy, does not read the JSON state file, and does not fall back to a default port

#### Scenario: Proxy state file is written atomically

- **WHEN** the CLI writes `.socks5-proxy-state`
- **THEN** it writes a temporary file in the workspace and renames it into place
- **AND** a concurrent reader sees either the old content or the new content, never a partial file

#### Scenario: Proxy cleaned up on teardown

- **GIVEN** `.socks5-proxy-state` exists with an active PID
- **WHEN** `down` is run
- **THEN** the SSH proxy process is killed and `.socks5-proxy-state` is deleted
- **AND** the port is removed from the proxy env file

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

The system MUST NOT enable the proxy by setting the standard JVM-global `socksProxyHost`/`socksProxyPort` properties, because those route every socket the process opens through the tunnel. Instead, the active proxy port is published privately, and only the clients that need the tunnel configure the SOCKS proxy explicitly: in the CLI, the Kubernetes client and the cluster HTTP client; on the shell side, the workspace tool wrappers, each for its own call only.

On the shell side, only the wrapped tools (`kubectl`, `helm`, `cilium`, `curl`, `skopeo`, `k9s`) use the tunnel. A wrapped tool sends all of its traffic through the tunnel, public URLs included. Every other tool, `aws` included, gets no proxy settings from the system and connects directly.

#### Scenario: AWS calls go direct while the proxy is running

- **GIVEN** a provisioned cluster with infrastructure UP and the SOCKS5 proxy active
- **WHEN** the CLI makes an AWS SDK call (e.g. S3 backup, EC2 describe, IAM policy update)
- **THEN** the call connects directly to the AWS endpoint and is not routed through the SSH tunnel.

#### Scenario: Cluster-internal traffic still uses the tunnel

- **GIVEN** the SOCKS5 proxy is active and Tailscale is not enabled
- **WHEN** the CLI reaches the K3s API or a private cluster service (e.g. Mimir or Loki on the control node)
- **THEN** that traffic is routed through the SOCKS proxy via explicit per-client configuration.

#### Scenario: Tailscale active means no proxy and all-direct routing

- **GIVEN** Tailscale is enabled for the cluster, so the SOCKS5 proxy is never started and the proxy port is not published
- **WHEN** the CLI reaches the K3s API, a private cluster service, or any AWS endpoint
- **THEN** every connection is direct — the K8s client and the cluster HTTP client both select no proxy, using Tailscale's private network for cluster traffic.

#### Scenario: State backup/restore works on a network that blocks the tunnel path

- **GIVEN** a network where the account S3 bucket is reachable directly from the operator's machine but the cluster tunnel cannot reach it
- **WHEN** the CLI backs up or restores cluster state to/from S3
- **THEN** the S3 traffic connects directly and succeeds, because it is never forced through the tunnel.

#### Scenario: Unwrapped tools in a shell step get no proxy settings

- **GIVEN** a SOCKS cluster with a recorded port and the operator's environment holding no proxy variables
- **WHEN** a kit shell step runs `aws sts get-caller-identity` or any other tool that is not wrapped
- **THEN** that tool's environment holds no `HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, or `NO_PROXY` variable in either case
- **AND** it connects directly

#### Scenario: Shell-side routing never touches the JVM proxy properties

- **WHEN** the CLI installs the wrappers, writes the proxy env file, or launches a kit shell step, phase script, or hook
- **THEN** the JVM-global `socksProxyHost` and `socksProxyPort` properties are not set, cleared, or read and restored at any point

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

### Requirement: Pod-network datapath (Cilium ENI native routing, selectable)
The system SHALL use Cilium in ENI IPAM native-routing mode as the default pod-network datapath, with no encapsulation. When Cilium is in use, pods SHALL receive VPC-routable IPv4 addresses allocated as secondary IPs on their node's ENIs, so cross-AZ pod-to-pod traffic is routed by the VPC without a tunnel. The CNI SHALL be selectable via `--cni=<cilium|flannel>` at init time; the default SHALL be `cilium`, and `--cni=flannel` SHALL select K3s's built-in Flannel datapath. When Cilium is selected, kube-proxy is retained (Cilium's kube-proxy replacement is not enabled). A cluster whose saved state records no CNI SHALL continue to be treated as Flannel. This requirement does not alter REQ-NET-005 (SOCKS proxy) behavior.

#### Scenario: Default provision uses Cilium ENI native routing
- **WHEN** a cluster is provisioned with no `--cni` option
- **THEN** the cluster's recorded CNI is `cilium` AND Cilium runs in ENI IPAM native-routing mode (`routing-mode: native`, no VXLAN tunnel)

#### Scenario: Flannel remains selectable
- **WHEN** a cluster is provisioned with `--cni=flannel`
- **THEN** the cluster's recorded CNI is `flannel` AND the cluster uses K3s's built-in Flannel datapath with no Cilium components installed

#### Scenario: --cni=cilium uses ENI native routing
- **WHEN** a cluster is provisioned with `--cni=cilium`
- **THEN** Cilium runs in ENI IPAM native-routing mode (`routing-mode: native`, no VXLAN tunnel) AND all worker-node K3s agents reach Ready without the API-server i/o-timeout bootstrap deadlock

#### Scenario: Cilium pods receive VPC-routable ENI IPs per AZ
- **GIVEN** a cluster provisioned with Cilium (the default, or `--cni=cilium`)
- **WHEN** the cluster is up
- **THEN** `kubectl get ciliumnode` shows ENIs with allocated IPs on each node AND each pod's IP falls within its own node's AZ subnet CIDR

#### Scenario: Cross-AZ pod-to-pod routing without a tunnel
- **GIVEN** a Cilium cluster with a pod on a node in AZ-a and a pod on a node in AZ-b
- **WHEN** the AZ-a pod connects to the AZ-b pod by its pod IP (via curl/nc and ping)
- **THEN** the connection succeeds, routed by the VPC with no encapsulation

#### Scenario: Cilium pod reaches a ClusterIP service and an external address
- **GIVEN** a Cilium cluster
- **WHEN** a pod connects to a ClusterIP service, and separately to an external URL
- **THEN** both succeed (Services via retained kube-proxy; egress via masquerade)

#### Scenario: cilium-operator on a worker node can allocate ENIs
- **GIVEN** a Cilium cluster where the cilium-operator pod is scheduled on a db/app (worker) node
- **WHEN** it requests EC2 credentials from IMDS to allocate ENIs
- **THEN** IMDS is reachable (worker nodes launch with IMDS hop-limit 2) AND ENIs are allocated so pods receive IPs

#### Scenario: OS leaves Cilium's secondary ENIs unmanaged
- **GIVEN** a node whose cilium-operator has attached a secondary ENI at runtime for pod IP allocation
- **WHEN** the interface appears (first as `eth0`, then renamed to `ens6`) and cloud-init receives the NIC hotplug event
- **THEN** cloud-init does NOT re-render `/etc/netplan/50-cloud-init.yaml` for it, because the base AMI's `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg` limits `updates.network.when` to `boot-new-instance` and `boot` (a hotplug rendering would list every pod IP as a static address on `ens5` and DHCP the new ENI with its own routing tables)
- **AND** the base AMI's `/etc/systemd/network/06-cilium-eni-unmanaged.network` drop-in matches the interface by `Driver=ena` under either name (sorting ahead of cloud-init's `10-netplan-*`) and marks it `Unmanaged=yes`, so the OS does NOT DHCP it, assign it an address, or add a competing default route, while the `05-cilium-eni-primary.network` drop-in keeps the primary ENI (`ens5`) OS-managed via DHCP — so the host stays single-homed and the node's IMDS, egress, and kubelet→apiserver paths keep working
- **AND** the node's ENA interfaces keep the MTU AWS offers (9001), because Cilium's devices are pinned to `ens+` and `tailscale0` (MTU 1280) is not a Cilium device

#### Scenario: Cluster with no recorded CNI is treated as Flannel
- **GIVEN** a cluster whose saved state has no `cni` field
- **WHEN** the observability stack is deployed or the OTel collector ConfigMap is regenerated
- **THEN** the cluster is treated as Flannel and no Cilium scrape jobs are rendered

### Requirement: Tailscale subnet routing to db nodes works on a Cilium cluster
When the CNI is Cilium and Tailscale is enabled, `up` SHALL install on the control node (the tailnet subnet router) an nftables NAT chain (`table ip edl_tailscale`, chain `postrouting`, `type nat hook postrouting priority 90`) that masquerades packets carrying Tailscale's forward mark (`meta mark & 0x00ff0000 == 0x00040000`) leaving an `ens*` interface. The chain SHALL run ahead of iptables' POSTROUTING, because Cilium's `CILIUM_POST_nat` ACCEPTs packets to cluster nodes before `ts-postrouting` can masquerade them, and Cilium re-inserts its feeder rule first on every restart. The install SHALL be idempotent (re-running replaces the table) and boot-persistent (the `edl-tailscale-masquerade.service` oneshot unit loads `/etc/easy-db-lab/tailscale-masquerade.nft` at boot). It SHALL run over `RemoteOperationsService`, after the Cilium install, and a failure SHALL abort `up`.

#### Scenario: db nodes reachable over the tailnet
- **GIVEN** a `--cni=cilium` cluster with Tailscale enabled and the VPC subnet route approved
- **WHEN** the operator's machine connects to a db node's private IP (SSH, a kit endpoint, the node's metrics port)
- **THEN** the connection succeeds, because the forwarded packet left `ens5` with the control node's source address and the reply routes back

#### Scenario: chain survives a control node reboot and a re-run of up
- **GIVEN** the chain is installed
- **WHEN** the control node reboots, or `up` runs again
- **THEN** `nft list table ip edl_tailscale` shows exactly one `postrouting` chain with the masquerade rule

#### Scenario: not installed without Tailscale
- **GIVEN** a `--cni=cilium` cluster with Tailscale disabled
- **WHEN** `up` completes
- **THEN** no `edl_tailscale` table exists on the control node

### Requirement: No LoadBalancer wildcards on node IPs under Cilium
When the CNI is Cilium, the K3s server SHALL be started with `--disable=traefik,servicelb` in addition to `--flannel-backend=none --disable-network-policy`. K3s ServiceLB publishes every node InternalIP as a LoadBalancer ingress IP, and Cilium installs a port-0 wildcard per LoadBalancer IP that rejects every pod packet to a node IP on a non-service port, which blocks kubelet probe replies and keeps every pod in the pod network from becoming Ready. The Flannel branch SHALL keep Traefik and ServiceLB.

#### Scenario: Cilium cluster runs without Traefik and ServiceLB
- **GIVEN** a cluster provisioned with `--cni=cilium`
- **WHEN** the K3s server starts
- **THEN** no `traefik` or `svclb-*` pods exist in `kube-system`, `cilium-dbg bpf lb list` shows no `<node ip>:0/ANY [LoadBalancer]` entries, and pods in the pod network reach Ready

#### Scenario: Flannel cluster keeps the K3s add-ons
- **GIVEN** a cluster provisioned with `--cni=flannel`
- **WHEN** the K3s server starts
- **THEN** Traefik and ServiceLB are installed as before

#### Scenario: `up` is re-runnable on a Cilium cluster
- **GIVEN** a `--cni=cilium` cluster where a previous `up` installed Cilium and then failed at a later step
- **WHEN** the operator runs `up` again and the K3s server-ready hook fires
- **THEN** the hook probes the helm release (`helm status cilium -n kube-system`, wrapped so the probe itself exits 0) and, finding it present, runs `cilium upgrade` with exactly the `--set` flag list `cilium install` uses, so a changed flag converges and the hook does not fail on "cannot reuse a name that is still in use"
- **AND** the Tailscale masquerade chain is re-applied idempotently afterwards

#### Scenario: Invalid CNI value is rejected
- **WHEN** the user passes `--cni=<unsupported>`
- **THEN** init fails with an error listing the allowed values (`cilium`, `flannel`)

### Requirement: Cilium metrics are collected only on a Cilium cluster
When the cluster's CNI is Cilium, the system SHALL install Cilium with the agent and operator Prometheus endpoints enabled, and the OTel collector SHALL scrape three Cilium jobs: `cilium-agent` (each node's agent at `localhost:9962`), `hubble` (each node's Hubble metrics at `localhost:9965`), and `cilium-operator` (the single operator pod, found by Kubernetes pod discovery in `kube-system` on the `io.cilium/app=operator` label and its metrics port 9963, scraped only by the collector on the operator's own node). When the CNI is Flannel, none of these jobs SHALL be rendered. Regenerating the collector ConfigMap on a kit start or stop SHALL preserve this behavior.

#### Scenario: Cilium cluster scrapes agent, operator, and Hubble
- **GIVEN** a cluster provisioned with Cilium (the default)
- **WHEN** the observability stack is deployed
- **THEN** the `cilium install` command carries `prometheus.enabled=true` and `operator.prometheus.enabled=true`
- **AND** the OTel collector ConfigMap contains the `cilium-agent`, `hubble`, and `cilium-operator` scrape jobs
- **AND** the `cilium-operator` job uses pod discovery, not a static `localhost` target

#### Scenario: Flannel cluster renders no Cilium scrape jobs
- **GIVEN** a cluster provisioned with `--cni=flannel`
- **WHEN** the observability stack is deployed
- **THEN** the OTel collector ConfigMap contains none of `cilium-agent`, `hubble`, or `cilium-operator`

#### Scenario: Kit start keeps the Cilium scrape jobs
- **GIVEN** a Cilium cluster with the observability stack deployed
- **WHEN** a kit `start` regenerates the OTel collector ConfigMap
- **THEN** the regenerated ConfigMap still contains the three Cilium scrape jobs

### Requirement: Hubble UI is reachable as a NodePort
When the cluster's CNI is Cilium, Hubble UI SHALL be exposed as a NodePort Service on the fixed port `Constants.Cilium.HUBBLE_UI_NODE_PORT` (31234), so it is reachable at `http://<node private IP>:31234` over the tailnet or the SOCKS tunnel with no port-forward. The port SHALL not collide with any NodePort a kit declares.

#### Scenario: Hubble UI URL
- **GIVEN** a `--cni=cilium` cluster
- **WHEN** the operator opens `http://<control node private IP>:31234`
- **THEN** Hubble UI is served

### Requirement: `platform cni` reads the datapath back from the cluster
The system SHALL provide a read-only `platform cni` command. On a Cilium cluster it SHALL read the `cilium-config` ConfigMap and the `CiliumNode` objects through `kubectl` on the control node, and print the CNI mode, routing mode, IPAM mode, kube-proxy replacement, masquerade interfaces, native routing CIDR, the Hubble UI URL, and for each node its name, ENI count, subnet CIDRs, and IPs allocated, used, and available. On a Flannel cluster it SHALL print one line stating the cluster uses Flannel and exit 0. It SHALL change no cluster state.

#### Scenario: Cilium read-back
- **GIVEN** a `--cni=cilium` cluster that is up
- **WHEN** the operator runs `platform cni`
- **THEN** the output shows `Routing mode: native`, `IPAM mode: eni`, `kube-proxy replacement: false`, and one block per node with its ENI count, subnet CIDRs, and IP counts

#### Scenario: Flannel read-back
- **GIVEN** a `--cni=flannel` cluster
- **WHEN** the operator runs `platform cni`
- **THEN** the command prints one line naming Flannel, runs nothing on the control node, and exits 0

### Requirement: Cilium install is marked on the Grafana timeline
When the cluster's CNI is Cilium and the cluster runs the local observability stack, `up` SHALL post two Grafana annotations tagged `cilium` (and the global tag): `Cilium install started` at the time the install began and `Cilium install finished` at the time it completed, or `Cilium install failed: <error>` if it did not. Because Cilium installs before Grafana exists, the timestamps SHALL be recorded at install time and the annotations posted once the observability stack is up. A failure to post SHALL be reported as an error event and SHALL NOT fail `up`. A telemetry-redirect cluster has no local Grafana and SHALL post nothing.

#### Scenario: Annotations land at the install's real times
- **GIVEN** a `--cni=cilium` cluster in local telemetry mode
- **WHEN** `up` completes
- **THEN** Grafana holds a `Cilium install started` and a `Cilium install finished` annotation tagged `cilium`, timestamped when the install ran, not when the stack came up

#### Scenario: Grafana unreachable when posting
- **GIVEN** a `--cni=cilium` cluster whose Grafana rejects the annotation request
- **WHEN** `up` posts the annotations
- **THEN** `up` reports the failure on stderr and still exits successfully

### Requirement: hostPort works on a Cilium cluster
On a Cilium cluster, a pod that declares a container `hostPort` SHALL be reachable on that port at its node's private IP, exactly as on a Flannel cluster. Kits that publish a scrape or client port through `hostPort` (Trino, Presto, Flink) SHALL keep working when Cilium is the datapath. If Cilium's default install does not provide this, the system SHALL install Cilium with the `portmap` CNI plugin chained (`cni.chainingMode=portmap`), applied identically by `cilium install` and `cilium upgrade`.

#### Scenario: hostPort kit is reachable and scraped on a Cilium cluster
- **WHEN** a kit that uses `hostPort` (Flink or Presto) is installed and started on a Cilium cluster
- **THEN** the port is reachable at the node's private IP AND the kit's scrape series appear in VictoriaMetrics

#### Scenario: portmap chaining survives an upgrade
- **WHEN** portmap chaining is required and `up` re-runs `cilium upgrade` on an existing Cilium cluster
- **THEN** the upgrade carries the same `cni.chainingMode=portmap` flag as the install, so `hostPort` keeps working

### Requirement: Cluster security group permits ICMP within the VPC
The cluster security group SHALL allow ICMP of every type and code from the VPC CIDR, alongside the existing TCP and UDP rules for the VPC CIDR. Cilium's health checker probes nodes with ICMP, so without this rule a healthy Cilium cluster reports its peer nodes as unreachable. The rule SHALL NOT allow ICMP from outside the VPC. An existing identical rule SHALL be detected and not added twice, and the rule SHALL be described to the user as "all ICMP types", not as a port number.

#### Scenario: Nodes answer ping inside the VPC
- **WHEN** a cluster is provisioned
- **THEN** its security group has an ICMP rule (all types, all codes) from the VPC CIDR
- **AND** a node can ping another node's private IP, and a pod can ping another pod's IP

#### Scenario: Cilium health reaches every node
- **WHEN** a Cilium cluster is provisioned
- **THEN** `cilium-dbg status` on every node reports all cluster nodes reachable

#### Scenario: ICMP rule is described correctly
- **WHEN** the ICMP rule is added, or found to exist
- **THEN** the log line describes it as "all ICMP types", and when the rule is added the `SecurityGroupRuleConfigured` event describes it the same way

### Requirement: SSH transport over SSM Session Manager

The system MUST support carrying every SSH connection to cluster nodes over AWS Systems Manager
Session Manager instead of a direct TCP connection to the node's public IP, selected by the user
profile's SSH transport setting (`direct` or `ssm`, default `direct`).

When the transport is `ssm`, both the generated SSH configuration (used by the SOCKS proxy and by
the shell helpers in `env.sh`) and the CLI's in-process SSH connections SHALL reach each node
through an SSM session targeting that node's instance ID. No inbound security group rule from the
operator SHALL be required for these connections to succeed.

When the transport is `direct`, SSH behaviour SHALL be unchanged.

The SSM sessions SHALL authenticate with the same AWS identity the profile is configured with: the
named AWS profile when one is set, otherwise the profile's static credentials. Static credentials
SHALL NOT be written into the generated SSH configuration, and when they are used the operator's
own AWS CLI config file SHALL NOT apply, so it cannot replace them with another identity.

#### Scenario: Generated SSH configuration routes through SSM

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the cluster's SSH configuration is generated
- **THEN** every host entry routes its connection through an SSM SSH session targeting that host's instance ID in the profile's region
- **AND** each host entry's `Hostname` line still immediately follows its `Host` line

#### Scenario: Direct transport leaves the SSH configuration unchanged

- **GIVEN** a profile whose SSH transport is `direct` or unset
- **WHEN** the cluster's SSH configuration is generated
- **THEN** no host entry routes through SSM

#### Scenario: Idle SSM connections are kept alive

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** a long-lived SSH connection such as the SOCKS proxy carries no traffic for longer than Session Manager's idle timeout
- **THEN** the connection stays usable, because the generated SSH configuration sends keepalives

#### Scenario: A session that passes no data does not hang ssh

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an OpenSSH connection's Session Manager session connects but passes no data
- **THEN** ssh fails within the configured connect timeout instead of waiting indefinitely

#### Scenario: Session processes do not outlive ssh

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an ssh process using the generated SSH configuration exits or is killed, even mid-handshake
- **THEN** the AWS CLI and session-manager-plugin processes its ProxyCommand started exit too

#### Scenario: Shell helpers work unchanged over SSM

- **GIVEN** a provisioned cluster and a profile whose SSH transport is `ssm`
- **WHEN** the user runs `ssh db0` or a `c0` alias after `source env.sh`, or starts the SOCKS proxy with `easy-db-lab socks start`
- **THEN** the connection is established over SSM with no change to the command

#### Scenario: In-process SSH connections use an SSM port forward

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the CLI opens an SSH connection to a cluster node
- **THEN** it connects through a local SSM port-forwarding session to that node's port 22, not to the node's public IP

#### Scenario: A refused direct connection is retried while the node boots

- **GIVEN** a profile whose SSH transport is `direct` and a freshly launched node whose sshd is not accepting connections yet
- **WHEN** `up` waits for SSH readiness
- **THEN** the refused connection is retried until sshd accepts it, and each retry notice carries the failure's message

#### Scenario: Other I/O failures are not retried

- **GIVEN** an SSH operation, `up`'s readiness wait included
- **WHEN** it fails with an I/O error that is not an SSH connection failure, such as a missing remote file
- **THEN** it fails at once, without waiting out the retry window

#### Scenario: Port forward that cannot start is retried while the node boots

- **GIVEN** a freshly launched node whose SSM agent has not yet registered
- **WHEN** `up` waits for SSH readiness over SSM
- **THEN** the failed session is treated like any other not-yet-ready SSH connection and retried
- **AND** each retry notice carries the failure's message
- **AND** a session that never becomes ready surfaces the Session Manager plugin's own output in the error

#### Scenario: A stuck port forward is replaced, not retried

- **GIVEN** a profile whose SSH transport is `ssm` and a port forward whose process is alive but carries no SSH data
- **WHEN** an SSH connection through it fails, and the operation is retried
- **THEN** the stuck forward is stopped and its session ended, and the retry connects through a new forward
- **AND** no later step reuses the failed connection

#### Scenario: Missing instance ID fails fast

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** an SSH connection is requested for a host with no recorded instance ID
- **THEN** the operation fails with an error naming the host, rather than attempting a direct connection

#### Scenario: Local SSM tooling is verified before provisioning

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user runs `up` on a machine missing the AWS CLI or the Session Manager plugin
- **THEN** `up` stops before creating any AWS resource
- **AND** the error names the missing tool and how to install it

#### Scenario: A local SSM tool that is installed but fails

- **GIVEN** a profile whose SSH transport is `ssm`
- **WHEN** the user runs `up` and the AWS CLI or the Session Manager plugin exits non-zero or hangs on `--version`
- **THEN** `up` stops before creating any AWS resource
- **AND** the error shows the tool's exit code and output, or that it timed out, and no install hint

#### Scenario: Port forwards do not outlive the CLI

- **GIVEN** SSM port-forwarding sessions started by a CLI invocation
- **WHEN** the invocation exits normally or is interrupted
- **THEN** the forwarding processes it started, and their child processes, are terminated, including any still waiting to become ready
- **AND** each of their Session Manager sessions is ended on the AWS side, rather than left open until the idle timeout

#### Scenario: SOCKS tunnel start-up allowance follows the transport

- **GIVEN** a SOCKS tunnel being started
- **WHEN** the profile's SSH transport is `ssm`
- **THEN** the tunnel is given about 30 seconds to become reachable
- **AND** under `direct` it is given about 5 seconds, as before

### Requirement: Operator manages SSH ingress on a running cluster

The system SHALL provide `ingress allow` and `ingress revoke`, run from a cluster workspace, that add and remove an SSH ingress rule (tcp, port 22 to 22) on the workspace cluster's security group, found through `InfrastructureState.securityGroupId` in the cluster state. Each action SHALL take a repeatable `--cidr <ip-or-cidr>` option. A bare IPv4 address SHALL mean that address `/32`. With no `--cidr`, the action SHALL use the operator's detected public IP as a `/32`. Every value SHALL be parsed and validated before any AWS call; one invalid value SHALL stop the whole command with nothing changed. Values that are the same CIDR after normalizing SHALL be treated as one value. Each action SHALL change only the tcp/22 rule for each given CIDR, never another SSH rule, the VPC-internal tcp, udp and ICMP rules, or egress. Each per-CIDR outcome and each failure SHALL be reported as a typed `Ingress` event. The command SHALL exit non-zero when any value fails or any precondition is not met. The commands SHALL NOT need the SOCKS tunnel.

#### Scenario: Allow with no value adds the detected public IP

- **WHEN** the operator runs `ingress allow` with no `--cidr` in a workspace whose cluster state has a security group
- **THEN** an SSH ingress rule for the detected public IP `/32` is added to that security group
- **AND** an `Ingress.RuleAdded` event names the CIDR, the security group, and that the CIDR is the detected public IP

#### Scenario: A bare IP becomes a /32

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7`
- **THEN** the rule added is for `203.0.113.7/32`

#### Scenario: A CIDR is used exactly

- **WHEN** the operator runs `ingress allow --cidr 198.51.100.0/24`
- **THEN** the rule added is for exactly `198.51.100.0/24`

#### Scenario: Repeated values each get a rule

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7 --cidr 198.51.100.0/24`
- **THEN** a rule is added for each CIDR
- **AND** one event is emitted for each CIDR

#### Scenario: Duplicate values collapse after normalizing

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7 --cidr 203.0.113.7/32`
- **THEN** one rule change is attempted for `203.0.113.7/32`
- **AND** one event is emitted for it

#### Scenario: Allowing an existing rule changes nothing

- **WHEN** the operator runs `ingress allow` for a CIDR that already has a tcp/22 rule on the security group
- **THEN** the command succeeds and the security group is not changed
- **AND** an `Ingress.RuleAlreadyPresent` event names the CIDR

#### Scenario: Revoke with no value removes the detected public IP

- **WHEN** the operator runs `ingress revoke` with no `--cidr` and the detected public IP `/32` has a tcp/22 rule
- **THEN** that rule is removed
- **AND** an `Ingress.RuleRemoved` event names the CIDR and that it is the detected public IP

#### Scenario: Revoke removes only the matching rule

- **GIVEN** a security group with tcp/22 rules for `203.0.113.7/32` and `198.51.100.0/24`, and the VPC-internal tcp, udp and ICMP rules
- **WHEN** the operator runs `ingress revoke --cidr 203.0.113.7`
- **THEN** only the tcp/22 rule for `203.0.113.7/32` is removed
- **AND** the tcp/22 rule for `198.51.100.0/24`, the VPC-internal rules and egress are unchanged

#### Scenario: Revoke of a missing rule fails and names it

- **WHEN** the operator runs `ingress revoke --cidr 192.0.2.1 --cidr 203.0.113.7` and only `203.0.113.7/32` has a tcp/22 rule
- **THEN** the rule for `203.0.113.7/32` is removed
- **AND** an `Ingress.RuleNotFound` event names `192.0.2.1/32`
- **AND** the command exits non-zero with a message that names `192.0.2.1/32`

#### Scenario: An invalid value stops the command before any AWS call

- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7 --cidr 300.1.1.1 --cidr foo`
- **THEN** no AWS call is made and no rule is added, not even for `203.0.113.7`
- **AND** one `Ingress.InvalidCidrs` event names `300.1.1.1` and `foo`, each with its reason
- **AND** the command exits non-zero

#### Scenario: No cluster security group in state

- **WHEN** the operator runs `ingress allow` or `ingress revoke` outside a workspace, or in a workspace whose cluster state has no security group
- **THEN** no AWS call is made
- **AND** an `Ingress.NotInWorkspace` or `Ingress.NoSecurityGroupInState` event is emitted and the command exits non-zero

#### Scenario: The security group no longer exists in AWS

- **WHEN** the cluster state names a security group that AWS reports does not exist
- **THEN** an `Ingress.SecurityGroupNotFound` event names the security group ID and the command exits non-zero
- **AND** the cluster state is not changed

#### Scenario: Public IP detection fails

- **WHEN** the operator runs `ingress allow` or `ingress revoke` with no `--cidr` and the public IP cannot be detected
- **THEN** no AWS call is made
- **AND** an `Ingress.PublicIpDetectionFailed` event says detection failed and that `--cidr` can be given
- **AND** the command exits non-zero

#### Scenario: An AWS error on one CIDR does not stop the others

- **WHEN** the operator runs `ingress allow` with several values and AWS rejects the rule for one of them
- **THEN** the rules for the other values are still added
- **AND** an `Ingress.RuleChangeFailed` event names the rejected CIDR, the action and AWS's reason
- **AND** the command exits non-zero

#### Scenario: Revoking the operator's own IP is allowed

- **WHEN** the operator runs `ingress revoke` for their own current public IP
- **THEN** the rule is removed with no confirmation prompt

#### Scenario: An open cluster's world rule is a normal CIDR

- **GIVEN** a cluster created with `init --open`
- **WHEN** the operator runs `ingress allow --cidr 203.0.113.7`, then `ingress revoke --cidr 0.0.0.0/0`
- **THEN** the rule for `203.0.113.7/32` is added and the `0.0.0.0/0` rule is removed

#### Scenario: Ingress commands do not start the tunnel

- **WHEN** the operator runs `ingress allow` or `ingress revoke` and no SOCKS tunnel is running
- **THEN** the command does not start a tunnel and makes only AWS API calls

### Requirement: IPv4 CIDR values are parsed strictly

The system SHALL parse every IPv4 CIDR value given by the operator with one shared parser. A value SHALL be four decimal octets, each 0 to 255 with no sign, no leading zero and no whitespace, optionally followed by `/` and a prefix length 0 to 32. A value with host bits set beyond its prefix SHALL be rejected, and the error SHALL name the network form. A rejected value's error SHALL name the value. IPv6 SHALL be rejected. `ingress allow` and `ingress revoke` SHALL accept a bare address as `/32`. `init --cidr` (the VPC block) SHALL require an explicit prefix and SHALL keep its `/20`-or-larger rule.

#### Scenario: Malformed values are rejected

- **WHEN** a value is one of `300.1.1.1`, `10.0.0.0/33`, `foo`, `+1.2.3.4/32`, `01.2.3.4/32`, `1.2.3/24`, ` 1.2.3.4/32` or `::1`
- **THEN** the parser rejects it with an error that names the value

#### Scenario: Host bits set are rejected with the network form

- **WHEN** a value is `198.51.100.7/24`
- **THEN** the parser rejects it with an error that names `198.51.100.0/24`

#### Scenario: init --cidr rejects a bare address with a clear message

- **WHEN** the operator runs `init --cidr 10.0.0.0`
- **THEN** `init` fails before any AWS call with an "Invalid CIDR" message that names `10.0.0.0`, not a number-format error

#### Scenario: init --cidr rejects host bits

- **WHEN** the operator runs `init --cidr 10.0.0.5/16`
- **THEN** `init` fails before any AWS call with a message that names `10.0.0.0/16`

### Requirement: Public IP detection is bounded in time

Detection of the operator's public IP SHALL use a bounded connect timeout and a bounded read timeout, both defined in `Constants`. A timeout SHALL be reported as a detection failure, never as a wait without end.

#### Scenario: A stalled detection server fails in bounded time

- **WHEN** the public IP service accepts the connection and then sends nothing
- **THEN** detection fails after the read timeout
- **AND** `ingress allow` with no `--cidr` reports `Ingress.PublicIpDetectionFailed`

### Requirement: Cluster rule changes are reported once

When `up` adds a rule to the cluster security group, the system SHALL emit `Infra.SecurityGroupRuleConfigured` once for that rule, and SHALL emit nothing for a rule that already exists. The provider layer (`VpcService`) SHALL NOT emit user events for rule changes; it SHALL return whether each rule was added or already present. The same holds for the Packer and account-compactor security group paths.

#### Scenario: up reports each added rule once

- **WHEN** `up` creates the cluster security group and adds its SSH, VPC tcp, VPC udp and ICMP rules
- **THEN** one `Infra.SecurityGroupRuleConfigured` event is emitted for each added rule, with the same description as before this change

#### Scenario: ingress allow prints one line per CIDR

- **WHEN** `ingress allow` adds a rule
- **THEN** only the `Ingress.RuleAdded` event is emitted for it, not also `Infra.SecurityGroupRuleConfigured`

### Requirement: The changed-IP hint names ingress allow

On the `direct` transport, when the cluster is not open, the tunnel connect-failure hint SHALL say that the cluster's security group allows SSH only from the IPs it lists, that a changed public IP is the likely cause, and that `easy-db-lab ingress allow` adds the current public IP.

#### Scenario: The hint names the fix

- **WHEN** a fresh tunnel on the `direct` transport of a cluster that is not open is still connecting when the verify window ends
- **THEN** the error names `easy-db-lab ingress allow` as the way to add the current public IP
- **AND** the error does not say that SSH is allowed only from the IP recorded when `up` ran

### Requirement: Proxy env file for shell-side tools

The CLI MUST write a sourceable file, `.socks5-proxy.env`, in the workspace. It holds only fixed `KEY=value` lines:

- `EDL_TAILSCALE_ACTIVE=true` or `EDL_TAILSCALE_ACTIVE=false`, from the cluster state.
- `EDL_SOCKS_PORT=<port>`, only while a verified tunnel is recorded.
- `EDL_SOCKS_PID=<pid>`, the PID of the recorded `edl-socks-tunnel` script, written and removed together with `EDL_SOCKS_PORT`.
- `EDL_NETWORK_MODE=direct`, only while direct network mode is active (see "Wrappers use no tunnel in direct network mode").

This file is the only source of proxy state for shell-side tools. Any value that a shell-side tool needs MUST be written by the CLI to this file. No script in the system MAY parse JSON to read proxy state, and `jq` is not required.

The CLI MUST update keys without dropping the others, and MUST write the file atomically, with a unique temporary file in the workspace renamed into place.

#### Scenario: up records the Tailscale flag

- **WHEN** `up` completes on a SOCKS cluster or on a Tailscale cluster
- **THEN** the env file holds `EDL_TAILSCALE_ACTIVE` with the value of `tailscaleActive` in cluster state

#### Scenario: Tailscale up leaves a usable env file

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** `up` completes
- **THEN** the env file holds `EDL_TAILSCALE_ACTIVE=true` and no `EDL_SOCKS_PORT`

#### Scenario: Port written only after the tunnel is verified

- **GIVEN** a SOCKS cluster
- **WHEN** the CLI starts or reuses the tunnel and verifies it
- **THEN** the env file holds `EDL_SOCKS_PORT` with the tunnel's port
- **AND** `EDL_TAILSCALE_ACTIVE` keeps its value

#### Scenario: Port removed when the tunnel stops

- **GIVEN** an env file that records a port
- **WHEN** `down` or `socks stop` stops the tunnel
- **THEN** the env file no longer holds `EDL_SOCKS_PORT`
- **AND** `EDL_TAILSCALE_ACTIVE` keeps its value

#### Scenario: Command executor records the Tailscale flag when it skips the proxy

- **GIVEN** cluster state exists and the executor decides not to start the proxy because Tailscale is active
- **WHEN** a CLI command runs
- **THEN** the env file holds `EDL_TAILSCALE_ACTIVE=true`

#### Scenario: Concurrent writers never leave a partial file

- **WHEN** two CLI processes in one workspace update the env file at the same time
- **THEN** a reader sources a complete file each time
- **AND** the file holds only the fixed keys

#### Scenario: No shell code parses JSON for proxy state

- **WHEN** the repository's shell scripts and packaged shell resources are searched
- **THEN** none reads `.socks5-proxy-state` or parses JSON to learn the proxy port or the Tailscale state

### Requirement: SOCKS tunnel reconnects on its own

The CLI MUST start the SOCKS tunnel through a packaged POSIX script, `<workspace>/bin/edl-socks-tunnel`, written with the tool wrappers. The script MUST run `ssh -N -D 127.0.0.1:<port> -F <sshConfig> <host>` with `ExitOnForwardFailure=yes` (IPv4 loopback only, so no other process can hold the port on `::1` and split it), wait for it to exit, and start it again on the same port after a short backoff, for as long as the script runs. The script MUST restart `ssh` only when the workspace's proxy env file records the script's own port as `EDL_SOCKS_PORT`; the CLI writes that port only after it verifies the tunnel end to end, so a recorded port means the tunnel came up. When the env file does not record its port, the tunnel never came up, and the script MUST exit with `ssh`'s status and not restart it, so a start that cannot succeed (a port already in use, a refused key, a changed host key) still fails fast and the CLI can retry on a new port. No timer decides this. When a restarted `ssh` cannot bind its port (for example, another process took it during the backoff), the script MUST exit after that one attempt and MUST NOT loop. The script MUST stop only on TERM; it MUST ignore INT, so a Ctrl-C in the terminal that launched an easy-db-lab command does not end the shared tunnel. Each line the script writes to the tunnel log MUST start with a UTC timestamp, and the script MUST log a line before each `ssh` run. The CLI MUST append to the tunnel log on each launch, with a timestamped header line, and MUST NOT truncate it. When a fresh tunnel's `ssh` is still alive at the end of the verify window and never connected, the CLI's error MUST name the host alias, the address and port `ssh` was dialing (or, when `ssh` reaches the node through a ProxyCommand, as on `ssm`, that it was connecting through it), and the wait.

The CLI MUST choose the tunnel's local port at random from free ports and MUST NOT prefer a fixed port, so two workspaces are unlikely to want the same port. Because the port does not change, the proxy env file stays correct across a reconnect, and wrapped tools in an open shell work again once the connection comes back. The SSH keepalives in the generated `sshConfig` (every transport) make `ssh` exit when a connection dies, so the script can reconnect.

The PID recorded in `.socks5-proxy-state` MUST be the script's. The CLI MUST verify that a recorded PID is an `edl-socks-tunnel` process for the recorded port and `sshConfig` before it signals it. `socks stop`, `down`, and the replacement of a stale tunnel MUST stop the script and its `ssh` child, and MUST leave no `ssh` process of that tunnel running. The CLI still verifies a reused tunnel end to end, and replaces it when the check fails.

#### Scenario: A dropped connection reconnects on the same port

- **GIVEN** a running tunnel whose `ssh` exits because its connection died
- **WHEN** the connection to the control node is available again
- **THEN** `edl-socks-tunnel` starts `ssh` again on the same port within a few seconds
- **AND** a wrapped tool in a shell that sourced `env.sh` works again with no CLI command

#### Scenario: A first start that fails is not retried by the script

- **GIVEN** a tunnel whose port the env file does not record yet, because the CLI has not verified it
- **WHEN** its `ssh` exits, for example because its port is already in use
- **THEN** the script exits with the same status and does not start `ssh` again
- **AND** the CLI reports the failed start or retries on a new port

#### Scenario: A fresh tunnel still connecting when the verify window ends names the stage

- **GIVEN** a fresh tunnel whose `ssh` is still alive when the CLI's verify window ends
- **AND** its part of the tunnel log shows `ssh` started to connect but never logged that the connection was established
- **WHEN** the CLI reports the failed start
- **THEN** the error names the host alias, the address and port `ssh` was dialing (or, when `ssh` reaches the node through a ProxyCommand, as on `ssm`, that it was connecting through it), and how long the CLI waited
- **AND** on the `direct` transport, when the cluster is not open (`init --open`), the error says the cluster's security group allows SSH only from the public IP recorded when `up` ran
- **AND** the error still names the tunnel log, and still quotes the non-debug lines `ssh` wrote

#### Scenario: A verified tunnel reconnects even right after it started

- **GIVEN** a tunnel that the CLI verified and recorded in the env file a few seconds ago
- **WHEN** its `ssh` exits
- **THEN** the script starts `ssh` again on the same port

#### Scenario: A restart that cannot bind its port ends the script

- **GIVEN** a verified tunnel whose port another process took while its `ssh` was in backoff
- **WHEN** the restarted `ssh` fails to bind the port
- **THEN** the script exits after that one attempt and does not loop
- **AND** wrapped tools fail with the `easy-db-lab socks start` message and never use the port

#### Scenario: Ctrl-C does not end the shared tunnel

- **GIVEN** a running tunnel started by an easy-db-lab command in a terminal
- **WHEN** the user presses Ctrl-C in that terminal during a later easy-db-lab command
- **THEN** the tunnel script and its `ssh` keep running

#### Scenario: The tunnel log keeps the history of every launch

- **GIVEN** a tunnel log from an earlier tunnel
- **WHEN** the CLI launches a new tunnel, or the script restarts `ssh`
- **THEN** the earlier lines remain, and each new line starts with a UTC timestamp

#### Scenario: A replaced tunnel is reported with its new port

- **GIVEN** a recorded tunnel that fails the reuse check
- **WHEN** any CLI command replaces it
- **THEN** the CLI emits a typed event that names the old port, the new port, and the reason
- **AND** if the old tunnel does not stop, the CLI emits a typed error event that names its PID and how to end it

#### Scenario: A killed script leaves no orphaned ssh behind

- **GIVEN** a tunnel script killed from outside with KILL, whose `ssh` child still runs with the recorded port and `sshConfig`
- **WHEN** the CLI next starts or replaces the tunnel, or `socks stop` or `down` runs
- **THEN** that orphaned `ssh` is stopped

#### Scenario: Stopping the tunnel stops the loop and its ssh

- **WHEN** `socks stop` or `down` stops the tunnel
- **THEN** the `edl-socks-tunnel` process and its `ssh` child have both exited
- **AND** no new `ssh` is started afterwards

#### Scenario: The script is written with the wrappers

- **WHEN** `up` completes, a kit process is launched, or a workspace is restored from a VPC
- **THEN** `<workspace>/bin/edl-socks-tunnel` exists, is executable, and matches the packaged script

#### Scenario: A reused PID that is not the tunnel script is not signaled

- **GIVEN** `.socks5-proxy-state` records a PID now held by a process that is not `edl-socks-tunnel` for the recorded port
- **WHEN** the CLI stops or replaces the tunnel
- **THEN** it does not signal that process

### Requirement: The generated sshConfig applies its settings to cluster hosts only

The generated `<workspace>/sshConfig` MUST put every cluster-wide option (user, identity file, host-key settings, keepalives, connect timeout) under one host pattern built from the server types, `Host db* app* control* !*.*`. The `!*.*` excludes any name that contains a dot, including IP addresses, because OpenSSH host patterns have no character classes. Each cluster host MUST keep its own block with its `Hostname` and, under `ssm`, its `ProxyCommand`.

The user's personal `~/.ssh/config` and the system `/etc/ssh/ssh_config` MUST NOT apply to cluster hosts, so no personal option (for example `ControlMaster`, `ControlPersist`, `ProxyJump`, `ProxyCommand`, `ForwardAgent`, or an extra `IdentityFile`) can reach a cluster host or the SOCKS tunnel. A cluster host is any name the cluster pattern matches: every dot-free name that starts with a server-type prefix (for example `db0`, but also `appserver` or `dbprod`), because the workspace wrappers are for reaching the cluster, not other hosts (owner decision). Every other host MUST resolve exactly as it would without `-F`: the file MUST include the personal config and then the system config only for names that are not cluster hosts.

#### Scenario: Another host resolves as without the workspace sshConfig

- **WHEN** `ssh -F <workspace>/sshConfig -G` resolves `github.com`, an IP address, or a dotted name such as `db.example.com`
- **THEN** the result equals what `ssh -G` gives for the same name with no `-F`

#### Scenario: Cluster hosts get the cluster settings

- **WHEN** `ssh -F <workspace>/sshConfig -G` resolves `db0`, `app0`, or `control0`
- **THEN** each gets the cluster user, identity file, host-key settings, keepalives, connect timeout, and its own `Hostname`
- **AND** under `ssm`, `control0` gets its `ProxyCommand`

#### Scenario: A dot-free name with a cluster prefix counts as a cluster host

- **WHEN** `ssh -F <workspace>/sshConfig -G` resolves `appserver`
- **THEN** it gets the cluster user and identity file
- **AND** it gets none of the personal or system settings

#### Scenario: Personal settings never reach cluster hosts

- **GIVEN** a personal `~/.ssh/config` whose `Host *` sets `ControlMaster`, `ControlPersist`, `ProxyJump`, `ForwardAgent`, and an `IdentityFile`
- **WHEN** `ssh -F <workspace>/sshConfig -G` resolves `control0` or `db0`
- **THEN** none of those personal settings appear
- **AND** they still appear for `github.com`

### Requirement: SSH wrappers use the workspace SSH configuration

The `ssh`, `scp`, `sftp`, and `rsync` wrappers in `<workspace>/bin/` MUST behave as the old `env.sh` functions did, as real executables that work from kit steps, nested scripts, `xargs`, and `/bin/sh` scripts:

- `ssh`, `scp`, `sftp`: run the real binary with `-F <workspace>/sshConfig` before the caller's arguments.
- `rsync`: run the real `rsync` with `-ave "ssh -F <workspace>/sshConfig"` before the caller's arguments.

They MUST find the real binary the same way the other wrappers do, never themselves. They MUST NOT set or clear any proxy variable or `KUBECONFIG`, because SSH reaches the nodes through `sshConfig` on both transports. If `<workspace>/sshConfig` does not exist, they MUST exit 1 with a message that names the missing file and MUST NOT run the real binary.

#### Scenario: ssh to a node by its alias with no setup

- **GIVEN** a running cluster and a shell that has not sourced `env.sh`
- **WHEN** the user runs `<workspace>/bin/ssh control0 hostname`
- **THEN** the real `ssh` runs with `-F <workspace>/sshConfig` and reaches the node

#### Scenario: rsync uses the workspace SSH configuration

- **WHEN** a kit step runs `rsync somefile db0:/tmp/`
- **THEN** the real `rsync` runs with `-ave "ssh -F <workspace>/sshConfig"` before the caller's arguments

#### Scenario: SSH wrappers set no proxy variables

- **GIVEN** a SOCKS cluster with a recorded tunnel and an inherited `HTTPS_PROXY`
- **WHEN** an SSH wrapper runs
- **THEN** the real binary sees the inherited environment unchanged

#### Scenario: Missing workspace SSH configuration

- **GIVEN** a workspace with no `sshConfig`
- **WHEN** an SSH wrapper runs
- **THEN** it exits 1 with a message that names the missing file
- **AND** the real binary does not run

### Requirement: Workspace tool wrappers

The CLI MUST write executable wrapper scripts named `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, and `k9s` into `<workspace>/bin/`, and SSH wrapper scripts named `ssh`, `scp`, `sftp`, and `rsync`, plus the tunnel script `edl-socks-tunnel` and a marker file `.easy-db-lab-tool-wrappers` in that directory. The foreign-file check below covers `edl-socks-tunnel` and the SSH wrappers as well as the six proxy wrappers. The wrappers MUST come from resources packaged in the distribution, so they work from a Homebrew install with no source checkout. They MUST run under the POSIX `/bin/sh` of both Linux (dash) and macOS.

The CLI MUST write them at `up`, before every kit process it launches, and when it restores a workspace from a VPC. Writing MUST be idempotent, MUST replace a wrapper whose content changed, and MUST be atomic. If `<workspace>/bin/` holds one of the wrapper names or `edl-socks-tunnel` and has no marker, the CLI MUST fail with a clear message and write nothing.

A call that bypasses `PATH` (an absolute path to the real binary, `env -i`, or a replaced `PATH`) is not wrapped, and the kit docs MUST say so.

#### Scenario: Wrappers written at up

- **WHEN** `up` completes
- **THEN** `<workspace>/bin/` holds the ten executable wrappers, `edl-socks-tunnel`, and the marker

#### Scenario: Wrappers written from a Homebrew install

- **GIVEN** the tool is installed with Homebrew and no repository exists on the machine
- **WHEN** `up` runs or a kit process is launched
- **THEN** the ten wrappers are written from packaged resources and work

#### Scenario: Wrappers rewritten when their content changed

- **GIVEN** a workspace whose wrappers were written by an older version
- **WHEN** a kit process is launched
- **THEN** each wrapper whose content differs from the packaged script is replaced
- **AND** a wrapper that already matches is left as it is

#### Scenario: A foreign file blocks the wrappers

- **GIVEN** `<workspace>/bin/kubectl` exists and `<workspace>/bin/` has no marker
- **WHEN** the CLI installs the wrappers
- **THEN** it fails with a message naming the file
- **AND** it writes no wrapper and no marker

#### Scenario: Wrappers written on VPC restore

- **WHEN** the CLI restores a workspace from a VPC
- **THEN** the wrappers, the marker, and the proxy env file are written

#### Scenario: Calls that bypass PATH are not wrapped

- **WHEN** a script runs a tool by its absolute path, under `env -i`, or with `PATH` replaced
- **THEN** the wrapper does not run for that call
- **AND** the kit docs state this limit

### Requirement: Wrappers run the real binary, never themselves

Each wrapper MUST pick its tool from its own file name and MUST run the first executable of that name on `PATH` that is not in a directory holding the wrapper marker and is not the wrapper itself. It MUST find its workspace from its own location, with symlinks resolved. It MUST pass arguments, stdin, and the exit code through unchanged, and MUST pass `PATH` on unchanged so the tool's children also use the wrappers.

#### Scenario: Wrapper runs the real binary

- **GIVEN** `<workspace>/bin` first on `PATH` and a real `kubectl` later on `PATH`
- **WHEN** `kubectl get ns` runs
- **THEN** the wrapper runs the real `kubectl` with `get ns`

#### Scenario: Two workspaces on PATH do not call each other

- **GIVEN** the `bin/` directories of two workspaces both on `PATH`, ahead of the real binary
- **WHEN** a wrapped tool runs
- **THEN** the real binary runs once, with no recursion

#### Scenario: Real binary missing

- **WHEN** a wrapper runs and no real binary of its name is on `PATH`
- **THEN** it prints a message naming the tool to stderr and exits 127

#### Scenario: Symlinked workspace

- **GIVEN** a workspace reached through a symlink
- **WHEN** a wrapper runs
- **THEN** it finds and reads the workspace's env file

#### Scenario: Arguments, stdin, and exit code pass through

- **WHEN** a wrapped tool runs with arguments that contain spaces, reads stdin, and exits non-zero
- **THEN** the real binary gets the same arguments and stdin
- **AND** the wrapper exits with the real binary's exit code

### Requirement: Wrappers use the workspace kubeconfig

The `kubectl`, `helm`, `cilium`, and `k9s` wrappers MUST always set `KUBECONFIG` to the absolute path of `<workspace>/kubeconfig` for the call they run, on SOCKS and Tailscale clusters alike. They MUST override any inherited `KUBECONFIG`, because a wrapper reaches only its own workspace's cluster and no other kubeconfig can work through it. If `<workspace>/kubeconfig` does not exist, the wrapper MUST fail with exit code 1 and a message that names the missing file, and MUST NOT run the real binary. The `curl` and `skopeo` wrappers MUST NOT set or change `KUBECONFIG`.

#### Scenario: A bare wrapper call works with no setup

- **GIVEN** a running cluster and a shell that has not sourced `env.sh` and has no `KUBECONFIG` set
- **WHEN** the user runs `<workspace>/bin/kubectl get ns`
- **THEN** `kubectl` uses `<workspace>/kubeconfig` and succeeds

#### Scenario: An inherited KUBECONFIG is overridden

- **GIVEN** a shell that exports `KUBECONFIG=~/.kube/config`
- **WHEN** a `kubectl`, `helm`, `cilium`, or `k9s` wrapper runs
- **THEN** the real binary runs with `KUBECONFIG` set to the absolute `<workspace>/kubeconfig`

#### Scenario: Missing workspace kubeconfig

- **GIVEN** a workspace with no `kubeconfig` file
- **WHEN** a `kubectl`, `helm`, `cilium`, or `k9s` wrapper runs
- **THEN** it exits 1 with a message that names the missing `<workspace>/kubeconfig`
- **AND** the real binary does not run

#### Scenario: curl and skopeo leave KUBECONFIG alone

- **GIVEN** an inherited `KUBECONFIG` value
- **WHEN** the `curl` or `skopeo` wrapper runs
- **THEN** the real binary sees the inherited `KUBECONFIG` unchanged

### Requirement: Wrappers route through the tunnel on a SOCKS cluster

On every call, each wrapper MUST ignore inherited `EDL_TAILSCALE_ACTIVE`, `EDL_SOCKS_PORT`, `EDL_SOCKS_PID` and `EDL_NETWORK_MODE` values and source the workspace's proxy env file. When `EDL_TAILSCALE_ACTIVE` is not `true`, the env file does not hold `EDL_NETWORK_MODE=direct`, and a port is recorded, the wrapper MUST clear every inherited proxy variable (`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, upper and lower case) and set its own, in both cases, for its own call only:

- `kubectl`, `helm`, `cilium`, `k9s`: `HTTPS_PROXY=socks5://127.0.0.1:<port>`.
- `curl`: `ALL_PROXY=socks5h://127.0.0.1:<port>` and `NO_PROXY=localhost,127.0.0.1`.
- `skopeo`: `ALL_PROXY`, `HTTP_PROXY`, and `HTTPS_PROXY` set to `socks5h://127.0.0.1:<port>`, and `NO_PROXY=localhost,127.0.0.1`.

When no port is recorded, or the recorded `EDL_SOCKS_PID` is not a live process, the wrapper MUST fail with exit code 1 and a message that names the workspace and `easy-db-lab socks start`, and MUST NOT use the port.

#### Scenario: Wrapped tools reach the cluster through the tunnel

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a shell step runs `kubectl`, `helm`, `cilium`, `curl`, or `skopeo`
- **THEN** the call resolves to the wrapper and reaches the cluster through the tunnel with that tool's proxy variables

#### Scenario: Indirect calls go through the wrapper

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a shell step runs a wrapped tool through `timeout`, `xargs`, `env`, a nested script in a kit `bin/`, or a `/bin/sh` script
- **THEN** the call goes through the wrapper and through the tunnel

#### Scenario: Inherited proxy variables do not bypass the tunnel

- **GIVEN** a SOCKS cluster with a recorded port and an operator environment that sets `NO_PROXY`, `no_proxy`, `http_proxy`, or similar variables
- **WHEN** a wrapped tool runs
- **THEN** the real binary sees only the wrapper's proxy variables
- **AND** the call goes through the tunnel

#### Scenario: Recorded tunnel process is gone

- **GIVEN** an env file that records a port and an `EDL_SOCKS_PID` whose process has exited
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`
- **AND** it does not connect to the recorded port

#### Scenario: Inherited EDL values are ignored

- **GIVEN** an environment that already sets `EDL_SOCKS_PORT` or `EDL_TAILSCALE_ACTIVE`
- **WHEN** a wrapped tool runs
- **THEN** the wrapper uses only the values in the workspace's env file

#### Scenario: New port after a proxy restart

- **GIVEN** the tunnel restarts on a new port during a session and the CLI records it
- **WHEN** the next wrapped call runs
- **THEN** it uses the new port

#### Scenario: No port recorded

- **GIVEN** a SOCKS cluster whose env file records no port
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`
- **AND** it does not fall back to a default port

#### Scenario: Tunnel died on its own

- **GIVEN** a recorded port whose tunnel process has died without `down` or `socks stop`
- **WHEN** a kit command that runs shell steps is invoked
- **THEN** the CLI verifies or restarts the tunnel and records its port before the first step runs

### Requirement: Wrappers connect directly on a Tailscale cluster

When the env file holds `EDL_TAILSCALE_ACTIVE=true`, each wrapper MUST run the real binary with the environment unchanged, except that the `kubectl`, `helm`, `cilium`, and `k9s` wrappers set `KUBECONFIG` as the "Wrappers use the workspace kubeconfig" requirement states. No proxy variable is set or cleared.

#### Scenario: Tailscale cluster

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** a wrapped tool runs in a kit shell step or in a shell that sourced `env.sh`
- **THEN** the real binary runs with the same environment the wrapper received, apart from `KUBECONFIG` for the four Kubernetes tools
- **AND** it connects directly

#### Scenario: env.sh right after a Tailscale up

- **GIVEN** `up` has just completed on a Tailscale cluster and no other command has run
- **WHEN** the user sources `env.sh` and runs `kubectl get ns`
- **THEN** `kubectl` connects directly and succeeds

### Requirement: Wrappers use no tunnel in direct network mode

A workspace MAY record that the cluster API is reachable from this machine with no tunnel. The CLI records it as `EDL_NETWORK_MODE=direct` in the proxy env file, through `ProxyEnvFile.recordDirect`, and removes the key when direct mode is turned off. The key MUST be kept by every other update of the env file (recording or removing a tunnel, recording the Tailscale flag), because the file is rewritten whole from its known fields. `EDL_TAILSCALE_ACTIVE` MUST NOT be reused for this mode.

Only the env file decides. Each wrapper MUST unset an inherited `EDL_NETWORK_MODE` before it sources the env file, so a value exported in the caller's environment has no effect. When the env file holds `EDL_NETWORK_MODE=direct`, each wrapper MUST run the real binary with no proxy variable set or cleared, and MUST NOT require a recorded tunnel, exactly as on a Tailscale cluster, including the `KUBECONFIG` rule of the "Wrappers use the workspace kubeconfig" requirement. Proxy variables the caller exported (`HTTP_PROXY`, `HTTPS_PROXY`, `ALL_PROXY`, `NO_PROXY`, upper and lower case, for example a corporate `http_proxy`) MUST pass through to the real binary untouched, as on a Tailscale cluster. `with-proxy` and `socks5-status` in `env.sh` follow the same mode (see "env.sh puts the workspace tool wrappers on PATH"). When the key is absent, the env file and the wrappers MUST behave as they did before this mode existed.

Nothing in production writes the key until the local execution environment (#1040) records it.

#### Scenario: Direct mode runs the real tool with no proxy variables

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and no recorded tunnel
- **WHEN** a wrapped tool runs
- **THEN** the real binary runs with the environment the wrapper received, apart from `KUBECONFIG` for the four Kubernetes tools
- **AND** the wrapper does not exit with the `socks start` message

#### Scenario: Direct mode ignores a recorded tunnel

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and a recorded tunnel
- **WHEN** a wrapped tool runs
- **THEN** the real binary sees none of the wrapper's proxy variables

#### Scenario: The key survives tunnel and Tailscale updates

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct`
- **WHEN** the CLI records a tunnel, removes the tunnel, or records the Tailscale flag
- **THEN** the env file still holds `EDL_NETWORK_MODE=direct`

#### Scenario: Turning direct mode off removes the key

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct` and other keys
- **WHEN** the CLI records direct mode as inactive
- **THEN** the env file no longer holds `EDL_NETWORK_MODE`
- **AND** the other keys keep their values

#### Scenario: A value exported in the caller's environment has no effect

- **GIVEN** an env file that does not hold `EDL_NETWORK_MODE=direct`, and a caller that exports `EDL_NETWORK_MODE=direct`
- **WHEN** a wrapped tool runs
- **THEN** it exits 1 with a message that names the workspace and `easy-db-lab socks start`

#### Scenario: No key, no change

- **GIVEN** an env file with no `EDL_NETWORK_MODE`
- **WHEN** a wrapped tool runs
- **THEN** the wrapper behaves as the "Wrappers route through the tunnel on a SOCKS cluster" and "Wrappers connect directly on a Tailscale cluster" requirements state

### Requirement: Wrappers work on both SSH transports

The tool wrappers and the proxy env file MUST behave the same under the `direct` and `ssm` SSH transports. Under both, the tunnel is `ssh -N -D` using the generated SSH configuration; under `ssm` that configuration carries the connection over Session Manager.

#### Scenario: SSM transport

- **GIVEN** a SOCKS cluster on the `ssm` transport
- **WHEN** a kit shell step or an `env.sh` shell runs a wrapped tool
- **THEN** the call reaches the cluster through the tunnel, the same as on the `direct` transport

#### Scenario: Direct transport

- **GIVEN** a SOCKS cluster on the `direct` transport
- **WHEN** a kit shell step or an `env.sh` shell runs a wrapped tool
- **THEN** the call reaches the cluster through the tunnel

### Requirement: socks start and socks stop commands

The CLI MUST provide a `socks` command group with `socks start` and `socks stop` subcommands, following the project's command-group convention (for example `tailscale start`). Both emit typed lifecycle events.

- `socks start` starts or reuses the tunnel, records its port in the proxy env file, and prints the port so the user can set up a browser. It has no `--port` option. On a Tailscale cluster it reports that no tunnel is needed and starts nothing.
- `socks stop` stops the tunnel without tearing down the cluster and removes the port from the proxy env file.

#### Scenario: socks start on a SOCKS cluster

- **GIVEN** a SOCKS cluster with no tunnel running
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** the tunnel starts, the env file records its port, and the port is printed

#### Scenario: socks start reuses a running tunnel

- **GIVEN** a SOCKS cluster with a verified tunnel running
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** no new tunnel starts and the existing port is recorded and printed

#### Scenario: socks start on a Tailscale cluster

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** the user runs `easy-db-lab socks start`
- **THEN** it reports that no tunnel is needed and starts nothing

#### Scenario: socks stop

- **GIVEN** a running tunnel
- **WHEN** the user runs `easy-db-lab socks stop`
- **THEN** the tunnel process stops, the env file no longer records a port, and the cluster keeps running
- **AND** the next wrapped call fails with the `easy-db-lab socks start` message

### Requirement: env.sh puts the workspace tool wrappers on PATH

`env.sh` MUST put `<workspace>/bin` first on `PATH`, so `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, `k9s`, `ssh`, `scp`, `sftp`, and `rsync` resolve to the same wrappers that kit shell steps use. It MUST first remove any shell function of those ten names left by an earlier `env.sh`, without errors in bash or zsh and without failing under `set -e`. It MUST NOT define those functions and MUST NOT start or stop the proxy.

`with-proxy` and `socks5-status` stay. They read the port from the proxy env file. `with-proxy` fails with the same `easy-db-lab socks start` message as the wrappers when no port is recorded, and runs its command directly on a Tailscale cluster.

Both helpers MUST follow direct network mode exactly as the wrappers do (see "Wrappers use no tunnel in direct network mode"). They MUST read `EDL_NETWORK_MODE` from the proxy env file only and ignore a value inherited from the shell. When the env file holds `EDL_NETWORK_MODE=direct`, `with-proxy` MUST run its command with no proxy variable set or cleared, so inherited proxy variables (for example `http_proxy`) reach the command untouched. It MUST NOT require a recorded tunnel, and it MUST NOT use one that is recorded: direct mode wins. In the same case `socks5-status` MUST report that the workspace uses direct network mode, not that no tunnel is recorded.

#### Scenario: Sourced shell resolves the wrappers

- **WHEN** a user sources `env.sh` in bash
- **THEN** `type -P kubectl` is `<workspace>/bin/kubectl`
- **AND** no shell function named `kubectl`, `helm`, `cilium`, `curl`, `skopeo`, or `k9s` exists

#### Scenario: Sourced shell on a SOCKS cluster

- **GIVEN** a SOCKS cluster with a recorded port
- **WHEN** a user sources `env.sh` and runs `kubectl get ns` or `k9s`
- **THEN** the tool runs through the wrapper and through the tunnel

#### Scenario: Re-sourcing replaces old functions

- **GIVEN** a shell that sourced an older `env.sh` that defined `kubectl()` and the other functions
- **WHEN** the user sources the new `env.sh`
- **THEN** the old functions are gone and the names resolve to the wrappers

#### Scenario: Old proxy functions are gone

- **WHEN** a user sources `env.sh`
- **THEN** `start-socks5`, `stop-socks5`, `socks5-start`, and `socks5-stop` are not defined

#### Scenario: with-proxy with no port recorded

- **GIVEN** a SOCKS cluster whose env file records no port
- **WHEN** the user runs `with-proxy curl http://10.0.1.50:8080/`
- **THEN** it fails with a message that names `easy-db-lab socks start`

#### Scenario: with-proxy on a Tailscale cluster

- **GIVEN** a cluster with `tailscaleActive: true`
- **WHEN** the user runs `with-proxy <command>`
- **THEN** the command runs with no proxy variables added

#### Scenario: with-proxy in direct network mode

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct`, with or without a recorded tunnel, and a shell that exports `http_proxy`
- **WHEN** the user runs `with-proxy <command>`
- **THEN** the command runs with no proxy variables added or removed, and sees the inherited `http_proxy` unchanged

#### Scenario: socks5-status in direct network mode

- **GIVEN** an env file that holds `EDL_NETWORK_MODE=direct`
- **WHEN** the user runs `socks5-status`
- **THEN** it reports that the workspace uses direct network mode and exits 0

#### Scenario: The helpers ignore an inherited EDL_NETWORK_MODE

- **GIVEN** an env file with no `EDL_NETWORK_MODE` and no recorded tunnel, and a shell that exports `EDL_NETWORK_MODE=direct`
- **WHEN** the user runs `with-proxy <command>` or `socks5-status`
- **THEN** `with-proxy` fails with the `easy-db-lab socks start` message, and `socks5-status` reports that no tunnel is recorded

