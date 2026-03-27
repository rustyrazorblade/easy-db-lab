#!/usr/bin/env bash
set -euo pipefail

echo "=== Running: install_bcache.sh ==="

# Ensure non-interactive mode for apt
export DEBIAN_FRONTEND=noninteractive

# Check if bcache-tools is already installed
if command -v make-bcache &> /dev/null; then
    echo "bcache-tools already installed, skipping installation"
    exit 0
fi

echo "Installing bcache-tools, linux-modules-extra-aws, and nvme-cli..."

sudo DEBIAN_FRONTEND=noninteractive apt update
sudo DEBIAN_FRONTEND=noninteractive apt install -y bcache-tools linux-modules-extra-aws nvme-cli

# Verify installation
echo "Verifying bcache-tools installation..."
if ! command -v make-bcache &> /dev/null; then
    echo "ERROR: make-bcache command not found after installation"
    exit 1
fi

echo "bcache-tools installed successfully"
echo "✓ install_bcache.sh completed successfully"
