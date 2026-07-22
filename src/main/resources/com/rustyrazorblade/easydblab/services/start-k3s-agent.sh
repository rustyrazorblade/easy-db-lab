#!/bin/bash

set -euo pipefail

echo "=== Starting K3s Agent ==="

# Check arguments
if [ $# -lt 2 ]; then
    echo "Usage: $0 <server_url> <node_token>"
    echo "Example: $0 https://10.0.1.5:6443 K10abc..."
    exit 1
fi

SERVER_URL="$1"
NODE_TOKEN="$2"

echo "Server URL: $SERVER_URL"

# Relocate K3s data directory to NVMe storage if available
# This prevents the root EBS volume from filling up with container images
if mountpoint -q /mnt/db1 && [ ! -L /var/lib/rancher/k3s ]; then
    echo "Relocating K3s data to NVMe at /mnt/db1/k3s..."
    mkdir -p /mnt/db1/k3s
    if [ -d /var/lib/rancher/k3s ]; then
        cp -a /var/lib/rancher/k3s/. /mnt/db1/k3s/
        rm -rf /var/lib/rancher/k3s
    fi
    ln -s /mnt/db1/k3s /var/lib/rancher/k3s
    echo "K3s data relocated to NVMe"
fi

# Note: Registry TLS configuration is handled by configure_registry_tls.sh
# which runs before K3s startup to configure /etc/rancher/k3s/registries.yaml with HTTPS

# Check if k3s-agent.service already exists
if systemctl list-unit-files k3s-agent.service --no-pager --no-legend 2>/dev/null | grep -q k3s-agent.service; then
    echo "k3s-agent.service already installed, skipping installation"
else
    echo "Installing k3s in agent mode..."

    # Run airgap installation in agent mode
    INSTALL_K3S_SKIP_DOWNLOAD=true \
    K3S_URL="$SERVER_URL" \
    K3S_TOKEN="$NODE_TOKEN" \
    /usr/local/bin/install-k3s.sh

    echo "✓ K3s agent installed successfully"
fi

# Ensure the NVMe data volume is mounted before k3s-agent starts on every boot.
# The k3s data dir (/var/lib/rancher/k3s) is a symlink onto /mnt/db1. On a
# reboot systemd could otherwise start k3s-agent before /mnt/db1 is remounted,
# leaving it unable to find its data dir. RequiresMountsFor pulls in and orders
# k3s-agent after the /mnt/db1 mount unit.
if mountpoint -q /mnt/db1; then
    mkdir -p /etc/systemd/system/k3s-agent.service.d
    cat > /etc/systemd/system/k3s-agent.service.d/10-nvme-mount.conf <<'EOF'
[Unit]
RequiresMountsFor=/mnt/db1
EOF
    systemctl daemon-reload
fi

# Start the k3s-agent service
echo "Starting k3s-agent.service..."
systemctl start k3s-agent

echo "✓ K3s agent started successfully"
