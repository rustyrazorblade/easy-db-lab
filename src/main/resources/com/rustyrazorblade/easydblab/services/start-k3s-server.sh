#!/bin/bash

set -euo pipefail

echo "=== Starting K3s Server ==="

# Parse flags
NO_FLANNEL=false
for arg in "$@"; do
    [ "$arg" = "--flannel-backend=none" ] && NO_FLANNEL=true
done

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

# Check if k3s.service already exists
if systemctl list-unit-files k3s.service --no-pager --no-legend 2>/dev/null | grep -q k3s.service; then
    echo "k3s.service already installed, skipping installation"
else
    echo "Installing k3s in server mode..."

    if [ "$NO_FLANNEL" = "true" ]; then
        echo "Custom CNI mode: disabling built-in Flannel"
        K3S_EXEC_ARGS='server --write-kubeconfig-mode=644 --flannel-backend=none --disable-network-policy'
    else
        K3S_EXEC_ARGS='server --write-kubeconfig-mode=644'
    fi

    INSTALL_K3S_SKIP_DOWNLOAD=true \
    INSTALL_K3S_EXEC="$K3S_EXEC_ARGS" \
    /usr/local/bin/install-k3s.sh

    echo "✓ K3s server installed successfully"
fi

# Start the k3s service
echo "Starting k3s.service..."
systemctl start k3s

echo "✓ K3s server started successfully"
