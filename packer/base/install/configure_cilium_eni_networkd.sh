#!/bin/bash
# Keep the primary ENI OS-managed and leave every other ENA link (Cilium's runtime-attached
# secondary ENIs) unmanaged by the OS network stack.
#
# WHY: In Cilium ENI IPAM native-routing mode the cilium-operator attaches a SECOND ENI to a node
# at runtime once it runs more than ~7 pods. On our Ubuntu Nitro image, systemd-networkd (fed by
# cloud-init/netplan) then DHCPs that ENI and installs a COMPETING default route. That multi-homes
# the host and breaks IMDS, egress, and the node's kubelet->apiserver channel -> the node goes
# NotReady, observability pods never become Ready, and `up` fails at GrafanaUpdateConfig. Cilium
# documents this exact requirement: "AWS ENI" -> "Node Configuration" says the OS must NOT manage
# newly attached ENI devices (https://docs.cilium.io -> Installation -> AWS ENI). We satisfy it by
# having networkd fully own the PRIMARY ENI and mark every OTHER ENA link Unmanaged so Cilium owns
# them.
#
# PRIMARY BY MAC, NOT NAME: the primary's name comes from its PCI slot and differs per instance
# family, so no name or slot list can work. The base AMI instead installs
# /usr/local/sbin/edl-primary-eni-network and the edl-primary-eni-network.service oneshot. On every
# boot, after cloud-init-local and before systemd-networkd, the script reads the IMDS primary MAC
# (.ds."meta-data".mac in /run/cloud-init/instance-data.json) and writes
# /run/systemd/network/05-cilium-eni-primary.network with PermanentMACAddress=<mac> and DHCP=ipv4.
# If it cannot read a valid MAC it prints one line to the console and exits 1; it never guesses.
#
# WHY /run FOR 05-: /run is a tmpfs, so the 05- file is written fresh on every boot and is never
# captured into an AMI. The cassandra AMI is baked on a real instance launched from this base AMI;
# a 05- file in /etc would carry the bake instance's MAC into it and every node would lose its
# network. A file of the same name in /etc would also override the /run one, so this script removes
# any /etc 05- file and the boot script refuses to run while one exists.
#
# PRECEDENCE (why the 05-/06- prefixes matter): cloud-init/netplan renders
# /run/systemd/network/10-netplan-<link>.network, which DHCPs every ENI it knows of.
# systemd-networkd selects the LEXICALLY-FIRST matching .network file per link across /etc, /run
# and /usr/lib, so our files MUST sort before 10-netplan-*. The 05-/06- prefixes guarantee that; a
# 99- file would lose and never take effect.
#
# MATCH BY DRIVER (why 06- says Driver=ena): a hotplugged ENI first appears under a kernel name and
# udev renames it to its slot-derived name. networkd waits for udev to finish with the link,
# rename included, before it configures it, so a name match is not too early; it is wrong because
# the slot-derived name differs per family. Driver=ena matches every ENA link under any name. The
# 05- file claims the primary by its permanent MAC and sorts first, so the primary stays managed;
# every OTHER ENA link falls through to 06- and is unmanaged.
#
# CLOUD-INIT HOTPLUG (why the cloud.cfg.d drop-in): Ubuntu's cloud-init enables install_hotplug,
# so on every ENI attach it re-renders /etc/netplan/50-cloud-init.yaml from IMDS. That rendering
# lists every Cilium pod IP as a static address on the primary and gives the new ENI DHCP plus its
# own routing-policy tables. Our 05-/06- files win networkd's first-match selection over the
# 10-netplan-* output, but the safe thing is for the rendering never to happen: the drop-in
# removes `hotplug` from updates.network.when, leaving first boot and reboot rendering intact.
# install_hotplug stays in cloud.cfg; with hotplug absent from the `when` list the module removes
# its udev rule.
#
# CLOUD-INIT PER-NIC DHCP (why the 91- drop-in): cloud-init 26.x's Ec2 datasource treats every
# boot as a new instance and rebuilds the network config. With the default
# apply_full_imds_network_config: true it runs `dhcpcd --persistent` on each non-primary NIC to learn
# its gateway for policy routing, then SIGKILLs dhcpcd. The lease address and a `proto dhcp`
# default route stay on each Cilium secondary and sort ahead of the primary's route, so a reboot
# kills host egress and SSM. networkd still shows the secondaries unmanaged; the DHCP runs inside
# cloud-init while it generates the config, so neither updates.network.when nor
# `network: {config: disabled}` stops it. The drop-in sets the option to false: cloud-init then
# renders plain DHCP for one fallback NIC and never runs the per-NIC DHCP. The primary is still
# matched by MAC through the /run 05- file, which sorts ahead of 10-netplan-*, so the fallback NIC
# choice never decides which link is the primary. Cilium keeps its own per-ENI routing tables.
#
# TIMING: secondary ENIs are attached at RUNTIME (post-boot), so 06- and the unit must PRE-EXIST
# in the image. This therefore belongs in the BASE AMI provisioning, not a post-attach hook.
#
# INERT ON FLANNEL: on a Flannel node the only ENA link is the primary, which 05- claims, so 06-
# matches nothing and the hotplug drop-in has no attach event to act on. The 05- file just gives
# the primary normal DHCP, which is what a Flannel node wants anyway. Hence this is safe to bake
# unconditionally into the base AMI -- no CNI conditional needed.
#
# THE SCRIPT SOURCE: Packer uploads install/edl-primary-eni-network to /tmp before this runs; the
# Docker test (packer/docker-compose.yml, test-base) sets EDL_PRIMARY_ENI_SCRIPT_SOURCE to the
# copy in the mounted packer tree.
set -euo pipefail

echo "=== Running: configure_cilium_eni_networkd.sh ==="

SCRIPT_SOURCE="${EDL_PRIMARY_ENI_SCRIPT_SOURCE:-/tmp/edl-primary-eni-network}"
SCRIPT_TARGET="/usr/local/sbin/edl-primary-eni-network"
UNIT_NAME="edl-primary-eni-network.service"
UNIT_FILE="/etc/systemd/system/${UNIT_NAME}"
UNIT_WANTS_LINK="/etc/systemd/system/sysinit.target.wants/${UNIT_NAME}"
NET_DIR="/etc/systemd/network"
ETC_PRIMARY="${NET_DIR}/05-cilium-eni-primary.network"
RUN_PRIMARY="/run/systemd/network/05-cilium-eni-primary.network"
SECONDARY="${NET_DIR}/06-cilium-eni-unmanaged.network"
CLOUD_CFG_DIR="/etc/cloud/cloud.cfg.d"
NO_HOTPLUG="${CLOUD_CFG_DIR}/90-easydblab-no-network-hotplug.cfg"
PRIMARY_NIC_ONLY="${CLOUD_CFG_DIR}/91-easydblab-ec2-primary-nic-only.cfg"

fail() {
    echo "ERROR: $1"
    exit 1
}

sudo mkdir -p "${NET_DIR}" "${CLOUD_CFG_DIR}" "$(dirname "${SCRIPT_TARGET}")" "$(dirname "${UNIT_WANTS_LINK}")"

# The primary's 05- file lives only under /run (see WHY /run FOR 05- above).
sudo rm -f "${ETC_PRIMARY}"

# The boot script that writes the /run 05- file.
[[ -f "${SCRIPT_SOURCE}" ]] || fail "${SCRIPT_SOURCE} not found; upload install/edl-primary-eni-network first"
sudo install -m 0755 "${SCRIPT_SOURCE}" "${SCRIPT_TARGET}"

# The unit runs the script on every boot: after cloud-init-local wrote instance-data.json, before
# networkd reads its config. Wants= keeps the order even if cloud-init-local is not otherwise in
# the boot transaction. DefaultDependencies=no lets it run this early in sysinit.
sudo tee "${UNIT_FILE}" >/dev/null <<'EOF2'
[Unit]
Description=easy-db-lab: networkd config for the primary ENI, matched by its IMDS MAC
DefaultDependencies=no
Wants=cloud-init-local.service
After=cloud-init-local.service
Before=network-pre.target systemd-networkd.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/edl-primary-eni-network
StandardOutput=journal+console
StandardError=journal+console

[Install]
WantedBy=sysinit.target
EOF2

# Enable through the symlink `systemctl enable` would make; the Docker test image has no systemd.
sudo ln -sfn "${UNIT_FILE}" "${UNIT_WANTS_LINK}"

# Every other ENA link (a hotplugged ENI, under any name): left Unmanaged so Cilium owns it. The
# primary never reaches this file because the /run 05- file matches it first.
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

# cloud-init: configure only the primary NIC. See CLOUD-INIT PER-NIC DHCP above.
sudo tee "${PRIMARY_NIC_ONLY}" >/dev/null <<'EOF2'
datasource:
  Ec2:
    apply_full_imds_network_config: false
EOF2

sudo chmod 0644 "${UNIT_FILE}" "${SECONDARY}" "${NO_HOTPLUG}" "${PRIMARY_NIC_ONLY}"

# Prove the path on a real instance: run the script against this instance's cloud-init data,
# reload networkd, and require that the primary link now uses the /run 05- file. The primary is
# the link that holds the default route. Docker has no ENA link and no cloud-init data, so skip.
ena_links() {
    local dev
    for dev in /sys/class/net/*; do
        [[ "$(basename "$(readlink -f "${dev}/device/driver" 2>/dev/null)")" == "ena" ]] && basename "${dev}"
    done
    return 0
}

if [[ -n "$(ena_links)" ]]; then
    # Read the primary link before the reload: networkd may drop the route while it reconfigures.
    primary_link="$(ip -o route show default | awk '{for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit }}')"
    [[ -n "${primary_link}" ]] || fail "no default route on the bake instance"
    sudo "${SCRIPT_TARGET}"
    sudo networkctl reload
    status=""
    for _ in $(seq 1 30); do
        status="$(networkctl status "${primary_link}" 2>/dev/null || true)"
        grep -qF "Network File: ${RUN_PRIMARY}" <<<"${status}" && break
        sleep 1
    done
    grep -qF "Network File: ${RUN_PRIMARY}" <<<"${status}" \
        || fail "networkctl status ${primary_link} does not name ${RUN_PRIMARY}: ${status}"
    echo "✓ the primary link ${primary_link} uses ${RUN_PRIMARY}"
    # The line checks below only prove what this script wrote. Ask cloud-init what it reads, so a
    # wrong key or wrong nesting in the 91- file fails the bake.
    sudo cloud-init query merged_system_cfg \
        | jq -e '.datasource.Ec2.apply_full_imds_network_config == false' >/dev/null \
        || fail "cloud-init does not read apply_full_imds_network_config as false from ${PRIMARY_NIC_ONLY}"
    echo "✓ cloud-init reads apply_full_imds_network_config: false from ${PRIMARY_NIC_ONLY}"
else
    echo "no ENA link (not an EC2 instance); skipping the networkd reload check"
fi

# Self-verify: fail the build if a file is missing or does not carry the load-bearing directive.
# Keeps the packer script test (./gradlew testPackerBase) meaningful, since the harness only checks
# the exit code.
[[ -x "${SCRIPT_TARGET}" ]] || fail "${SCRIPT_TARGET} is missing or not executable"
[[ -f "${UNIT_FILE}" ]] || fail "${UNIT_FILE} is missing"
[[ "$(readlink "${UNIT_WANTS_LINK}")" == "${UNIT_FILE}" ]] || fail "${UNIT_NAME} is not enabled (${UNIT_WANTS_LINK})"
for line in \
    'DefaultDependencies=no' \
    'Wants=cloud-init-local.service' \
    'After=cloud-init-local.service' \
    'Before=network-pre.target systemd-networkd.service' \
    'Type=oneshot' \
    "ExecStart=${SCRIPT_TARGET}" \
    'StandardOutput=journal+console' \
    'StandardError=journal+console' \
    'WantedBy=sysinit.target'; do
    grep -qxF "${line}" "${UNIT_FILE}" || fail "${UNIT_FILE} lacks '${line}'"
done
grep -qx 'Driver=ena' "${SECONDARY}" || fail "${SECONDARY} lacks 'Driver=ena'"
grep -qx 'Unmanaged=yes' "${SECONDARY}" || fail "${SECONDARY} lacks 'Unmanaged=yes'"
[[ ! -e "${ETC_PRIMARY}" ]] || fail "${ETC_PRIMARY} exists and would override ${RUN_PRIMARY}"
grep -q "^    when: \['boot-new-instance', 'boot'\]$" "${NO_HOTPLUG}" || fail "${NO_HOTPLUG} lacks the when list"
if grep -q 'hotplug' "${NO_HOTPLUG}"; then
    fail "'hotplug' must not appear in ${NO_HOTPLUG}"
fi
[[ -f "${PRIMARY_NIC_ONLY}" ]] || fail "${PRIMARY_NIC_ONLY} is missing"
for line in 'datasource:' '  Ec2:' '    apply_full_imds_network_config: false'; do
    grep -qxF "${line}" "${PRIMARY_NIC_ONLY}" || fail "${PRIMARY_NIC_ONLY} lacks '${line}'"
done

echo "✓ installed ${SCRIPT_TARGET} and enabled ${UNIT_NAME} (primary ENI by IMDS MAC, in /run), ${SECONDARY} (other ENA links Unmanaged), ${NO_HOTPLUG} (no cloud-init hotplug), ${PRIMARY_NIC_ONLY} (cloud-init configures only the primary NIC)"
echo "✓ configure_cilium_eni_networkd.sh completed successfully"
