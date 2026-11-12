## MODIFIED Requirements

### Requirement: Pod-network datapath (Cilium ENI native routing, selectable)
The system SHALL use Cilium in ENI IPAM native-routing mode as the default pod-network datapath, with no encapsulation. When Cilium is in use, pods SHALL receive VPC-routable IPv4 addresses allocated as secondary IPs on their node's ENIs, so cross-AZ pod-to-pod traffic is routed by the VPC without a tunnel. The CNI SHALL be selectable via `--cni=<cilium|flannel>` at init time; the default SHALL be `cilium`, and `--cni=flannel` SHALL select K3s's built-in Flannel datapath. When Cilium is selected, kube-proxy is retained (Cilium's kube-proxy replacement is not enabled). Cilium's `devices` and `egressMasqueradeInterfaces` SHALL be `en+`, so they match the primary ENI under any name the instance family gives it (`ens5`, `ens52`, `enp39s0`). A cluster whose saved state records no CNI SHALL continue to be treated as Flannel. This requirement does not alter REQ-NET-005 (SOCKS proxy) behavior.

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
- **WHEN** the interface appears and is renamed by udev to its slot-derived name, and cloud-init receives the NIC hotplug event
- **THEN** cloud-init does NOT re-render `/etc/netplan/50-cloud-init.yaml` for it, because the base AMI's `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg` limits `updates.network.when` to `boot-new-instance` and `boot` (a hotplug rendering would list every pod IP as a static address on the primary and DHCP the new ENI with its own routing tables)
- **AND** the base AMI's `/etc/systemd/network/06-cilium-eni-unmanaged.network` drop-in matches the interface by `Driver=ena` under whatever name it has (networkd configures the link once udev has renamed it; the file sorts ahead of cloud-init's `10-netplan-*`) and marks it `Unmanaged=yes`, so the OS does NOT DHCP it, assign it an address, or add a competing default route
- **AND** the secondary does not match `/run/systemd/network/05-cilium-eni-primary.network`, whose `PermanentMACAddress=` is the primary's IMDS MAC, so only the primary stays OS-managed via DHCP and the host stays single-homed, and the node's IMDS, egress, and kubelet→apiserver paths keep working
- **AND** `networkctl` shows the secondary as `unmanaged` and the node stays `Ready`
- **AND** the node's ENA interfaces keep the MTU AWS offers (9001), because Cilium's devices are pinned to `en+` and `tailscale0` (MTU 1280) is not a Cilium device

#### Scenario: Cilium devices match a primary not named ens5
- **GIVEN** a Cilium cluster on i7i, whose primary ENI is `enp39s0`
- **WHEN** Cilium is installed
- **THEN** its `devices` and `egressMasqueradeInterfaces` are `en+` and match `enp39s0`
- **AND** a pod's connection to an external address succeeds

#### Scenario: Cluster with no recorded CNI is treated as Flannel
- **GIVEN** a cluster whose saved state has no `cni` field
- **WHEN** the observability stack is deployed or the OTel collector ConfigMap is regenerated
- **THEN** the cluster is treated as Flannel and no Cilium scrape jobs are rendered

### Requirement: Tailscale subnet routing to db nodes works on a Cilium cluster
When the CNI is Cilium and Tailscale is enabled, `up` SHALL install on the control node (the tailnet subnet router) an nftables NAT chain (`table ip edl_tailscale`, chain `postrouting`, `type nat hook postrouting priority 90`) that masquerades packets carrying Tailscale's forward mark (`meta mark & 0x00ff0000 == 0x00040000`) leaving an `en*` interface, so the rule matches the primary ENI under any name the instance family gives it. The chain SHALL run ahead of iptables' POSTROUTING, because Cilium's `CILIUM_POST_nat` ACCEPTs packets to cluster nodes before `ts-postrouting` can masquerade them, and Cilium re-inserts its feeder rule first on every restart. The install SHALL be idempotent (re-running replaces the table) and boot-persistent (the `edl-tailscale-masquerade.service` oneshot unit loads `/etc/easy-db-lab/tailscale-masquerade.nft` at boot). It SHALL run over `RemoteOperationsService`, after the Cilium install, and a failure SHALL abort `up`.

#### Scenario: db nodes reachable over the tailnet
- **GIVEN** a `--cni=cilium` cluster with Tailscale enabled and the VPC subnet route approved
- **WHEN** the operator's machine connects to a db node's private IP (SSH, a kit endpoint, the node's metrics port)
- **THEN** the connection succeeds, because the forwarded packet left the control node's primary ENI with the control node's source address and the reply routes back

#### Scenario: db nodes reachable over the tailnet on i7i
- **GIVEN** a `--cni=cilium` cluster on i7i, whose primary ENI is `enp39s0`, with Tailscale enabled and the VPC subnet route approved
- **WHEN** the operator's machine connects to a db node's private IP
- **THEN** the connection succeeds, because `oifname "en*"` matches `enp39s0` and the packet is masqueraded

#### Scenario: chain survives a control node reboot and a re-run of up
- **GIVEN** the chain is installed
- **WHEN** the control node reboots, or `up` runs again
- **THEN** `nft list table ip edl_tailscale` shows exactly one `postrouting` chain with the masquerade rule

#### Scenario: not installed without Tailscale
- **GIVEN** a `--cni=cilium` cluster with Tailscale disabled
- **WHEN** `up` completes
- **THEN** no `edl_tailscale` table exists on the control node

## ADDED Requirements

### Requirement: Base image manages the primary ENI by its IMDS MAC
The base AMI SHALL identify the primary ENI by the MAC that IMDS reports as the primary (`meta-data/mac`), never by interface name or PCI path. A oneshot unit, `edl-primary-eni-network.service` (`Type=oneshot`, `DefaultDependencies=no`, `Wants=` and `After=cloud-init-local.service`, `Before=network-pre.target systemd-networkd.service`, output to `journal+console`, `WantedBy=sysinit.target`), SHALL run `/usr/local/sbin/edl-primary-eni-network` on every boot. The script SHALL read the MAC from `.ds.meta_data.mac` in `/run/cloud-init/instance-data.json` with `jq`, with no fallback path, validate it as six colon-separated hex pairs, lowercase it, and write `/run/systemd/network/05-cilium-eni-primary.network` with `[Match] PermanentMACAddress=<mac>` and `[Network] DHCP=ipv4`, replacing any existing file atomically. The 05 file SHALL live only under `/run`; no `/etc/systemd/network/05-cilium-eni-primary.network` SHALL exist in the image. On any failure the script SHALL print one line naming the cause to the console, write no 05 file, and exit 1; it SHALL never pick a link by any other rule. The `06-cilium-eni-unmanaged.network` (`Driver=ena`, `Unmanaged=yes`) and `90-easydblab-no-network-hotplug.cfg` drop-ins SHALL stay unchanged. No list of NIC names or PCI slots SHALL exist anywhere in the networkd setup. The setup applies to every node on every CNI.

#### Scenario: Primary ENI managed on every instance family
- **GIVEN** a cluster created from the new base AMI (or the cassandra AMI built on it), on Cilium
- **WHEN** nodes boot on c8gd or m8gd (primary `ens52`), i7i (primary `enp39s0`), and c7gd or c6id (primary `ens5`)
- **THEN** every node's primary ENI matches `/run/systemd/network/05-cilium-eni-primary.network` by its permanent MAC and gets DHCP
- **AND** every node has one default route, reaches IMDS, and `up` completes

#### Scenario: Flannel nodes manage only the primary
- **GIVEN** a `--cni=flannel` cluster on an 8th-gen type and on i7i
- **WHEN** the nodes boot
- **THEN** the primary ENI is managed by the 05 file
- **AND** no ENA link that is the primary is left unmanaged

#### Scenario: Primary MAC unavailable fails loudly, never guesses
- **WHEN** `/run/cloud-init/instance-data.json` is missing or unreadable, or its `.ds.meta_data.mac` is null, empty, or not a MAC
- **THEN** the script prints one line naming the cause to the console and exits 1
- **AND** it writes no 05 file and does not pick any link (for example, "the only ENA link")

#### Scenario: Uppercase MAC is lowercased
- **WHEN** `.ds.meta_data.mac` is `0A:1B:2C:3D:4E:5F`
- **THEN** the 05 file contains `PermanentMACAddress=0a:1b:2c:3d:4e:5f`

#### Scenario: An /etc 05 file would shadow the /run one
- **GIVEN** `/etc/systemd/network/05-cilium-eni-primary.network` exists
- **WHEN** the script runs
- **THEN** it prints one line naming that file and exits 1, because a file of the same name in `/etc` overrides the one in `/run`

#### Scenario: A stale 05 file is replaced
- **GIVEN** `/run/systemd/network/05-cilium-eni-primary.network` already holds another MAC
- **WHEN** the script runs with a valid MAC in cloud-init's data
- **THEN** the file holds only the new MAC

#### Scenario: Reboot with a secondary attached
- **GIVEN** a Cilium node with a runtime-attached secondary ENI
- **WHEN** the node reboots
- **THEN** cloud-init-local finishes before the unit runs, and the unit finishes before systemd-networkd starts
- **AND** the primary is managed again by the 05 file and the secondary stays `unmanaged`

#### Scenario: Derived AMI carries no bake MAC
- **GIVEN** the cassandra AMI is baked on an instance launched from the new base AMI
- **WHEN** a node is launched from the cassandra AMI
- **THEN** the image holds no 05 file with the bake instance's MAC, because the file exists only on the `/run` tmpfs
- **AND** the node writes a 05 file with its own primary MAC and gets network

#### Scenario: The bake proves the match on a real instance
- **WHEN** `configure_cilium_eni_networkd.sh` runs on the bake instance
- **THEN** it installs the script and the unit, enables the unit, and removes any `/etc` 05 file
- **AND** it runs the script, runs `networkctl reload`, and fails the bake unless networkd reports `/run/systemd/network/05-cilium-eni-primary.network` as the primary link's network file

#### Scenario: Packer base test checks the unit and the unmanaged rule
- **WHEN** `./gradlew testPackerBase` runs
- **THEN** its self-checks fail if the unit is missing or lacks its `After=cloud-init-local.service` or `Before=network-pre.target` ordering, or if the 06 file lacks `Driver=ena` or `Unmanaged=yes`

### Requirement: Cilium nodes must come from an AMI carrying the node fixes
On a Cilium cluster, `up` SHALL check every node for the files the base AMI bakes for Cilium (`Constants.Cilium.NODE_FIX_FILES`) before starting K3s, and SHALL fail with the nodes and the missing paths if any node lacks one. The list SHALL be `/etc/systemd/system/edl-primary-eni-network.service`, `/usr/local/sbin/edl-primary-eni-network`, `/etc/systemd/network/06-cilium-eni-unmanaged.network` and `/etc/cloud/cloud.cfg.d/90-easydblab-no-network-hotplug.cfg`. A node that cannot be reached SHALL fail the check.

#### Scenario: Node from the new AMI passes
- **GIVEN** a Cilium cluster whose nodes come from the new base AMI
- **WHEN** `up` runs the Cilium node image check
- **THEN** no node is reported and `up` continues

#### Scenario: Node without the primary-ENI unit fails
- **GIVEN** a Cilium cluster with a node from an AMI that lacks `edl-primary-eni-network.service`
- **WHEN** `up` runs the Cilium node image check
- **THEN** `up` fails and names that node and `/etc/systemd/system/edl-primary-eni-network.service`
