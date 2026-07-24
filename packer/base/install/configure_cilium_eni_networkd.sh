#!/bin/bash
# Leave Cilium's secondary ENIs (ens6+) unmanaged by the OS network stack.
#
# WHY: In Cilium ENI IPAM native-routing mode the cilium-operator attaches a SECOND ENI
# (ens6) to a node at runtime once it runs more than ~7 pods. On our Ubuntu Nitro image,
# systemd-networkd (fed by cloud-init/netplan) then DHCPs ens6 and installs a COMPETING
# default route. That multi-homes the host and breaks IMDS, egress, and the node's
# kubelet->apiserver channel -> the node goes NotReady, observability pods never become
# Ready, and `up` fails at GrafanaUpdateConfig. Cilium documents this exact requirement:
# "AWS ENI" -> "Node Configuration" says the OS must NOT manage newly attached ENI devices
# (https://docs.cilium.io -> Installation -> AWS ENI). We satisfy it by having networkd
# fully own the PRIMARY interface (ens5) and mark every SECONDARY ENI (ens6+) Unmanaged so
# Cilium owns them.
#
# PRECEDENCE (why the 05-/06- prefixes matter): cloud-init/netplan renders
# /run/systemd/network/10-netplan-ens6.network which DHCPs ens6. systemd-networkd selects
# the LEXICALLY-FIRST matching .network file per link across all config dirs, so our files
# MUST sort before 10-netplan-*. The 05-/06- prefixes guarantee that; a 99- file would lose
# and never take effect.
#
# ROBUSTNESS (why drop-ins in /etc, and why NOT disable cloud-init networking): the drop-ins
# live in /etc/systemd/network so they persist in the baked image and survive reboots. Because
# they sort ahead of 10-netplan-*, they win the first-match selection even if cloud-init
# REGENERATES the 10-netplan-* files on a later boot -- a regenerated 10-netplan-ens6.network
# can never override our 06- file. Crucially our own 05- file fully configures ens5 via DHCP,
# so PRIMARY networking never depends on cloud-init's output. That is why we deliberately do
# NOT disable cloud-init's network management (e.g. a 99-disable-network-config.cfg): doing so
# risks breaking ens5 DHCP/SSH on a fresh instance if the baked netplan does not match the new
# instance's interface, and buys us nothing the lexical precedence above does not already give.
#
# TIMING: ens6 is attached at RUNTIME (post-boot), so these drop-ins must PRE-EXIST in the
# image. This therefore belongs in the BASE AMI provisioning, not a post-attach hook.
#
# INERT ON FLANNEL: the 06- match only hits ens6+, which only exist on Cilium ENI-IPAM nodes.
# A Flannel cluster never attaches a secondary ENI, so 06- matches nothing there and is a
# no-op. The 05- file just gives ens5 normal DHCP, which is what a Flannel node wants anyway.
# Hence this is safe to bake unconditionally into the base AMI -- no CNI conditional needed.
set -euo pipefail

echo "=== Running: configure_cilium_eni_networkd.sh ==="

NET_DIR="/etc/systemd/network"
PRIMARY="${NET_DIR}/05-cilium-eni-primary.network"
SECONDARY="${NET_DIR}/06-cilium-eni-unmanaged.network"

sudo mkdir -p "${NET_DIR}"

# Primary ENI (ens5): OS-managed via DHCP.
sudo tee "${PRIMARY}" >/dev/null <<'EOF2'
[Match]
Name=ens5

[Network]
DHCP=ipv4
EOF2

# Secondary ENIs (ens6+): left Unmanaged so Cilium owns them.
sudo tee "${SECONDARY}" >/dev/null <<'EOF2'
[Match]
Name=ens[6-9] ens[1-9][0-9]

[Link]
Unmanaged=yes
EOF2

sudo chmod 0644 "${PRIMARY}" "${SECONDARY}"

# Self-verify: fail the build if either drop-in is missing or does not carry the load-bearing
# directive. Keeps the packer script test (./gradlew testPackerBase) meaningful, since the
# harness only checks the exit code.
grep -q '^Name=ens5$' "${PRIMARY}"
grep -q '^DHCP=ipv4$' "${PRIMARY}"
grep -q '^Name=ens\[6-9\] ens\[1-9\]\[0-9\]$' "${SECONDARY}"
grep -q '^Unmanaged=yes$' "${SECONDARY}"

echo "✓ wrote ${PRIMARY} (ens5 OS-managed) and ${SECONDARY} (ens6+ Unmanaged)"
echo "✓ configure_cilium_eni_networkd.sh completed successfully"
