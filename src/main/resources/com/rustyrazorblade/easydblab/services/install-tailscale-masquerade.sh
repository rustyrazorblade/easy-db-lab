#!/bin/bash
# Masquerade Tailscale-forwarded traffic ahead of Cilium's NAT chain, on the control node.
#
# WHY: the control node is the Tailscale subnet router for the VPC CIDR. Cilium's
# CILIUM_POST_nat chain carries "-o ens+ -m set --match-set cilium_node_set_v4 dst -j ACCEPT"
# (exclude traffic to cluster nodes from masquerade), and iptables POSTROUTING jumps to it
# BEFORE ts-postrouting. A packet forwarded from tailscale0 to a db node therefore hits ACCEPT
# and is never MASQUERADEd: it leaves ens5 with the operator's 100.x source, and the reply has
# no route back. From the operator's machine control0 works but every db node times out.
# Cilium re-inserts its feeder rule at position 1 on every restart, so ordering inside iptables
# cannot fix it.
#
# HOW: an nftables NAT chain at priority 90, ahead of iptables' POSTROUTING (srcnat = 100),
# masquerades packets carrying Tailscale's forward mark (0x40000 in the 0xff0000 field, set by
# ts-forward on packets entering from tailscale0). nf_nat performs NAT in the first chain that
# sets up a mapping, so Cilium's later ACCEPT no longer matters.
#
# IDEMPOTENT: the .nft file declares, deletes, and recreates the table, so re-running replaces
# the rules in place. BOOT-PERSISTENT: a oneshot unit loads the file at every boot; the AMI's
# nftables.service does not load /etc/nftables.d/, so the unit is the loader.
set -euo pipefail

echo "=== Installing Tailscale masquerade ahead of Cilium NAT ==="

NFT_BIN="$(command -v nft)" || { echo "ERROR: nft not found; nftables is required" >&2; exit 1; }
NFT_FILE=/etc/easy-db-lab/tailscale-masquerade.nft
UNIT=/etc/systemd/system/edl-tailscale-masquerade.service

mkdir -p /etc/easy-db-lab

cat > "$NFT_FILE" <<'EOF'
# easy-db-lab: masquerade tailnet-forwarded packets before Cilium's CILIUM_POST_nat ACCEPT.
# Loaded by edl-tailscale-masquerade.service. Declare + delete + recreate keeps it idempotent.
table ip edl_tailscale
delete table ip edl_tailscale
table ip edl_tailscale {
    chain postrouting {
        type nat hook postrouting priority 90; policy accept;
        meta mark & 0x00ff0000 == 0x00040000 oifname "ens*" masquerade
    }
}
EOF

cat > "$UNIT" <<EOF
[Unit]
Description=Masquerade Tailscale-forwarded traffic ahead of Cilium NAT (easy-db-lab)
After=network-pre.target
Wants=network-pre.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=${NFT_BIN} -f ${NFT_FILE}
ExecStop=${NFT_BIN} delete table ip edl_tailscale

[Install]
WantedBy=multi-user.target
EOF

chmod 0644 "$NFT_FILE" "$UNIT"

systemctl daemon-reload
systemctl enable edl-tailscale-masquerade.service
# restart, not `enable --now`: a oneshot that is already active would not re-read the file.
systemctl restart edl-tailscale-masquerade.service

# Self-verify: the chain must be live with its masquerade rule. Read nft's output in full first:
# piping it into `grep -q` fails under pipefail, because grep exits on the first match and nft then
# dies of SIGPIPE (exit 141) even though the table is live.
TABLE="$("$NFT_BIN" list table ip edl_tailscale)"
grep -q 'masquerade' <<<"$TABLE" || { echo "ERROR: edl_tailscale table has no masquerade rule" >&2; exit 1; }

echo "✓ edl_tailscale nft table live and edl-tailscale-masquerade.service enabled"
