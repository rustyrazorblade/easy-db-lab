#!/bin/bash

set -e

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

# Start the k3s-agent service
echo "Starting k3s-agent.service..."
systemctl start k3s-agent

echo "✓ K3s agent started successfully"
