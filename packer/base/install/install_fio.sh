#!/usr/bin/env bash
set -euo pipefail

echo "=== Running: install_fio.sh ==="

# Ensure non-interactive mode for apt
export DEBIAN_FRONTEND=noninteractive

# Check if fio is already installed
if command -v fio &> /dev/null; then
    INSTALLED_VERSION=$(fio --version 2>&1 | head -n1 | awk '{print $2}' || echo "unknown")
    echo "fio already installed (version: $INSTALLED_VERSION), skipping installation"
    exit 0
fi

echo "Installing fio from apt..."

# Install fio from Ubuntu repository
sudo DEBIAN_FRONTEND=noninteractive apt update
sudo DEBIAN_FRONTEND=noninteractive apt install -y fio fatrace

# Verify installation
echo "Verifying fio installation..."
if ! command -v fio &> /dev/null; then
    echo "ERROR: fio command not found after installation"
    exit 1
fi

INSTALLED_VERSION=$(fio --version 2>&1 | head -n1)
echo "fio installed successfully: $INSTALLED_VERSION"
echo "✓ install_fio.sh completed successfully"
