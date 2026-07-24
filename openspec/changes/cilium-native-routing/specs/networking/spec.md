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
- **GIVEN** a node whose cilium-operator has attached a secondary ENI (ens6+) at runtime for pod IP allocation
- **WHEN** systemd-networkd evaluates the newly attached interface
- **THEN** the base AMI's `/etc/systemd/network/06-cilium-eni-unmanaged.network` drop-in (sorting ahead of cloud-init's `10-netplan-*`) marks ens6+ `Unmanaged=yes` so the OS does NOT DHCP it or add a competing default route, while the `05-cilium-eni-primary.network` drop-in keeps the primary ENI (ens5) OS-managed via DHCP — so the host stays single-homed and the node's IMDS, egress, and kubelet→apiserver paths keep working

#### Scenario: Invalid CNI value is rejected
- **WHEN** the user passes `--cni=<unsupported>`
- **THEN** init fails with an error listing the allowed values (`cilium`, `flannel`)
