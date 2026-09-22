#!/bin/bash
# Leave Cilium's runtime-attached secondary ENIs unmanaged by the OS network stack.
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
# MATCH BY DRIVER, NOT NAME (why 06- says Driver=ena): a hotplugged ENI first appears as eth0
# and is renamed to ens6 moments later. networkd configures the link under its FIRST name, so a
# Name=ens[6-9] match arrives too late: by the rename the address, a second default route, and a
# duplicate subnet route are already installed and Unmanaged=yes does not remove them. Driver=ena
# matches the ENI under whatever name it has. The 05- file claims ens5 by name and sorts first, so
# the primary stays managed; every OTHER ENA interface falls through to 06- and is unmanaged.
#
# CLOUD-INIT HOTPLUG (why the cloud.cfg.d drop-in): Ubuntu's cloud-init enables install_hotplug,
# so on every ENI attach it re-renders /etc/netplan/50-cloud-init.yaml from IMDS. That rendering
# lists every Cilium pod IP as a static address on ens5 and gives the new ENI DHCP plus its own
# routing-policy tables. Our 05-/06- files win networkd's first-match selection over the
# 10-netplan-* output, but the safe thing is for the rendering never to happen: the drop-in
# removes `hotplug` from updates.network.when, leaving first boot and reboot rendering intact so
# ens5 DHCP/SSH on a fresh instance still comes from cloud-init. install_hotplug stays in
# cloud.cfg; with hotplug absent from the `when` list the module removes its udev rule.
#
# ROBUSTNESS (why drop-ins in /etc): the drop-ins live in /etc/systemd/network so they persist in
# the baked image and survive reboots. Because they sort ahead of 10-netplan-*, they win the
# first-match selection even if cloud-init regenerates the 10-netplan-* files on a later boot.
#
# TIMING: ens6 is attached at RUNTIME (post-boot), so these drop-ins must PRE-EXIST in the
# image. This therefore belongs in the BASE AMI provisioning, not a post-attach hook.
#
# INERT ON FLANNEL: on a Flannel node the only ENA interface is ens5, which 05- claims, so 06-
# matches nothing and the hotplug drop-in has no attach event to act on. The 05- file just gives
# ens5 normal DHCP, which is what a Flannel node wants anyway. Hence this is safe to bake
# unconditionally into the base AMI -- no CNI conditional needed.
set -euo pipefail

echo "=== Running: configure_cilium_eni_networkd.sh ==="

NET_DIR="/etc/systemd/network"
PRIMARY="${NET_DIR}/05-cilium-eni-primary.network"
SECONDARY="${NET_DIR}/06-cilium-eni-unmanaged.network"
CLOUD_CFG_DIR="/etc/cloud/cloud.cfg.d"
NO_HOTPLUG="${CLOUD_CFG_DIR}/90-easydblab-no-network-hotplug.cfg"

sudo mkdir -p "${NET_DIR}" "${CLOUD_CFG_DIR}"

# Primary ENI (ens5): OS-managed via DHCP.
sudo tee "${PRIMARY}" >/dev/null <<'EOF2'
[Match]
Name=ens5

[Network]
DHCP=ipv4
EOF2

# Every other ENA interface (a hotplugged ENI, under its pre- or post-rename name): left
# Unmanaged so Cilium owns it. ens5 never reaches this file because 05- matches it first.
sudo tee "${SECONDARY}" >/dev/null <<'EOF2'
[Match]
Driver=ena

[Link]
Unmanaged=yes
EOF2

# cloud-init: render the network config on first boot and on reboot, never on a NIC hotplug.
sudo tee "${NO_HOTPLUG}" >/dev/null <<'EOF2'
# easy-db-lab: Cilium attaches ENIs at runtime; cloud-init must not re-render netplan for them.
updates:
  network:
    when: ['boot-new-instance', 'boot']
EOF2

sudo chmod 0644 "${PRIMARY}" "${SECONDARY}" "${NO_HOTPLUG}"

# Self-verify: fail the build if a drop-in is missing or does not carry the load-bearing
# directive. Keeps the packer script test (./gradlew testPackerBase) meaningful, since the
# harness only checks the exit code.
grep -q '^Name=ens5$' "${PRIMARY}"
grep -q '^DHCP=ipv4$' "${PRIMARY}"
grep -q '^Driver=ena$' "${SECONDARY}"
grep -q '^Unmanaged=yes$' "${SECONDARY}"
grep -q "^    when: \['boot-new-instance', 'boot'\]$" "${NO_HOTPLUG}"
if grep -q 'hotplug' "${NO_HOTPLUG}"; then
    echo "ERROR: 'hotplug' must not appear in ${NO_HOTPLUG}"
    exit 1
fi

echo "✓ wrote ${PRIMARY} (ens5 OS-managed), ${SECONDARY} (other ENA links Unmanaged), ${NO_HOTPLUG} (no cloud-init hotplug)"
echo "✓ configure_cilium_eni_networkd.sh completed successfully"
