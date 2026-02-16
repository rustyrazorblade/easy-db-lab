#!/bin/bash

set -ex

echo "=== Running: install_k3s.sh ==="

# Pre-download k3s components for airgap installation on Ubuntu 24.04
# k3s will NOT be installed during image build - installation happens at runtime
# This allows node type (server vs agent) to be determined when the node starts

# Determine k3s version and architecture
K3S_VERSION="v1.35.1+k3s1"
ARCH="amd64"

echo "Downloading k3s ${K3S_VERSION} for airgap installation..."

# Download k3s binary
echo "Downloading k3s binary..."
sudo curl -sfL -o /usr/local/bin/k3s \
  "https://github.com/k3s-io/k3s/releases/download/${K3S_VERSION}/k3s"
sudo chmod +x /usr/local/bin/k3s

# Download airgap images
echo "Downloading k3s airgap images..."
sudo mkdir -p /var/lib/rancher/k3s/agent/images
sudo curl -sfL -o /var/lib/rancher/k3s/agent/images/k3s-airgap-images-${ARCH}.tar.zst \
  "https://github.com/k3s-io/k3s/releases/download/${K3S_VERSION}/k3s-airgap-images-${ARCH}.tar.zst"

# Download install script
echo "Downloading k3s install script..."
sudo curl -sfL -o /usr/local/bin/install-k3s.sh https://get.k3s.io
sudo chmod +x /usr/local/bin/install-k3s.sh

# Create kubectl symlink for convenience
sudo ln -sf /usr/local/bin/k3s /usr/local/bin/kubectl

# Create kubeconfig directory structure
sudo mkdir -p /etc/rancher/k3s
sudo chmod 755 /etc/rancher/k3s

# Verify binary is executable
k3s --version
kubectl version --client

echo "✓ k3s ${K3S_VERSION} downloaded successfully (not installed yet)"
echo "Installation will occur at runtime in server or agent mode as needed"
echo "✓ install_k3s.sh completed successfully"
