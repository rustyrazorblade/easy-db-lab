## MODIFIED Requirements

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

## ADDED Requirements

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
