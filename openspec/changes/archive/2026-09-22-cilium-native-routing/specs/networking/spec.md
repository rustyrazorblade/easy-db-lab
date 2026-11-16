## ADDED Requirements

### Requirement: Pod-network datapath (Cilium ENI native routing, selectable)
The system SHALL support Cilium in ENI IPAM native-routing mode as a selectable pod-network datapath with no encapsulation. When selected, pods SHALL receive VPC-routable IPv4 addresses allocated as secondary IPs on their node's ENIs, so cross-AZ pod-to-pod traffic is routed by the VPC without a tunnel. The CNI SHALL be selectable via `--cni=<cilium|flannel>` at init time; the default SHALL be `flannel` (K3s's built-in datapath). When Cilium is selected, kube-proxy is retained (Cilium's kube-proxy replacement is not enabled). This requirement does not alter REQ-NET-005 (SOCKS proxy) behavior. (Flipping the default to `cilium` is tracked separately.)

#### Scenario: Default provision uses Flannel
- **WHEN** a cluster is provisioned with no `--cni` option
- **THEN** the cluster uses K3s's built-in Flannel datapath

#### Scenario: --cni=cilium uses ENI native routing
- **WHEN** a cluster is provisioned with `--cni=cilium`
- **THEN** Cilium runs in ENI IPAM native-routing mode (`routing-mode: native`, no VXLAN tunnel) AND all worker-node K3s agents reach Ready without the API-server i/o-timeout bootstrap deadlock

#### Scenario: Cilium pods receive VPC-routable ENI IPs per AZ
- **GIVEN** a cluster provisioned with `--cni=cilium`
- **WHEN** the cluster is up
- **THEN** `kubectl get ciliumnode` shows ENIs with allocated IPs on each node AND each pod's IP falls within its own node's AZ subnet CIDR

#### Scenario: Cross-AZ pod-to-pod routing without a tunnel
- **GIVEN** a `--cni=cilium` cluster with a pod on a node in AZ-a and a pod on a node in AZ-b
- **WHEN** the AZ-a pod connects to the AZ-b pod by its pod IP (via curl/nc; ICMP is not permitted by the security group)
- **THEN** the connection succeeds, routed by the VPC with no encapsulation

#### Scenario: Cilium pod reaches a ClusterIP service and an external address
- **GIVEN** a `--cni=cilium` cluster
- **WHEN** a pod connects to a ClusterIP service, and separately to an external URL
- **THEN** both succeed (Services via retained kube-proxy; egress via masquerade)

#### Scenario: cilium-operator on a worker node can allocate ENIs
- **GIVEN** a `--cni=cilium` cluster where the cilium-operator pod is scheduled on a db/app (worker) node
- **WHEN** it requests EC2 credentials from IMDS to allocate ENIs
- **THEN** IMDS is reachable (worker nodes launch with IMDS hop-limit 2) AND ENIs are allocated so pods receive IPs

#### Scenario: OS leaves Cilium's secondary ENIs unmanaged
- **GIVEN** a node whose cilium-operator has attached a secondary ENI at runtime for pod IP allocation
- **WHEN** the interface appears (first as `eth0`, then renamed to `ens6`) and cloud-init receives the NIC hotplug event
- **THEN** cloud-init does NOT re-render `/etc/netplan/50-cloud-init.yaml` for it, because the base AMI's `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg` limits `updates.network.when` to `boot-new-instance` and `boot` (a hotplug rendering would list every pod IP as a static address on `ens5` and DHCP the new ENI with its own routing tables)
- **AND** the base AMI's `/etc/systemd/network/06-cilium-eni-unmanaged.network` drop-in matches the interface by `Driver=ena` under either name (sorting ahead of cloud-init's `10-netplan-*`) and marks it `Unmanaged=yes`, so the OS does NOT DHCP it, assign it an address, or add a competing default route, while the `05-cilium-eni-primary.network` drop-in keeps the primary ENI (`ens5`) OS-managed via DHCP — so the host stays single-homed and the node's IMDS, egress, and kubelet→apiserver paths keep working
- **AND** the node's ENA interfaces keep the MTU AWS offers (9001), because Cilium's devices are pinned to `ens+` and `tailscale0` (MTU 1280) is not a Cilium device

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
- **GIVEN** a cluster provisioned with `--cni=cilium`
- **WHEN** the observability stack is deployed
- **THEN** the `cilium install` command carries `prometheus.enabled=true` and `operator.prometheus.enabled=true`
- **AND** the OTel collector ConfigMap contains the `cilium-agent`, `hubble`, and `cilium-operator` scrape jobs
- **AND** the `cilium-operator` job uses pod discovery, not a static `localhost` target

#### Scenario: Flannel cluster renders no Cilium scrape jobs
- **GIVEN** a cluster provisioned with `--cni=flannel` (the default)
- **WHEN** the observability stack is deployed
- **THEN** the OTel collector ConfigMap contains none of `cilium-agent`, `hubble`, or `cilium-operator`

#### Scenario: Kit start keeps the Cilium scrape jobs
- **GIVEN** a `--cni=cilium` cluster with the observability stack deployed
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
