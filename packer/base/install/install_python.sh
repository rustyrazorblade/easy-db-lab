#!/bin/bash
set -e

echo "=== Running: install_python.sh ==="

# Ensure non-interactive mode for apt
export DEBIAN_FRONTEND=noninteractive

# Add deadsnakes PPA for Python 3.11 (supports Ubuntu 26.04+)
# cqlsh (bundled with Cassandra) only supports Python 3.8-3.11; Python 3.12+ is rejected.
# The cqlsh wrapper script tries 'python3' (3.12, rejected) then falls through to 'python',
# which we point at Python 3.11.
# software-properties-common provides add-apt-repository; gpg-agent is required by the PPA signing machinery
sudo DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends software-properties-common gpg-agent
sudo DEBIAN_FRONTEND=noninteractive add-apt-repository ppa:deadsnakes/ppa -y

# On Ubuntu 26.04, x86-64-v3 hardware (e.g. c6i) reports amd64v3 as the native apt architecture.
# deadsnakes provides python3.11 binaries in binary-amd64 and binary-arm64, but NOT binary-amd64v3.
# Without pinning the base arch, apt fetches only the amd64v3 list which has no python3.11 binary.
# Strip any microarch suffix (amd64v3 → amd64) to get the base architecture for the PPA pin.
BASE_ARCH=$(dpkg --print-architecture | sed 's/v[0-9]*$//')
DEADSNAKES_SOURCES=$(find /etc/apt/sources.list.d/ -name "*deadsnakes*" 2>/dev/null | head -1)
if [ -n "$DEADSNAKES_SOURCES" ]; then
    if grep -q "^Types:" "$DEADSNAKES_SOURCES"; then
        # deb822 format (.sources file)
        if ! grep -q "^Architectures:" "$DEADSNAKES_SOURCES"; then
            sudo sed -i "/^Types:/a Architectures: ${BASE_ARCH}" "$DEADSNAKES_SOURCES"
            echo "Pinned deadsnakes to arch=${BASE_ARCH} in $DEADSNAKES_SOURCES"
        fi
    else
        # one-line format (.list file)
        sudo sed -i "s|^deb https|deb [arch=${BASE_ARCH}] https|" "$DEADSNAKES_SOURCES"
        echo "Pinned deadsnakes to arch=${BASE_ARCH} in $DEADSNAKES_SOURCES"
    fi
fi

sudo DEBIAN_FRONTEND=noninteractive apt update -y
sudo DEBIAN_FRONTEND=noninteractive apt install -y python3.11 python3.11-dev python3.11-venv python3-pip

sudo update-alternatives --install /usr/bin/python python /usr/bin/python3.11 1
sudo update-alternatives --set python /usr/bin/python3.11

# Install uv (fast Python package installer) via official installer
echo "Installing uv to /usr/local/bin..."
curl -LsSf https://astral.sh/uv/install.sh | sudo env INSTALLER_NO_MODIFY_PATH=1 UV_INSTALL_DIR=/usr/local/bin sh >/tmp/uv-install.log 2>&1

# Verify installation
uv --version

# Install iostat-tool using uv
uv tool install iostat-tool

# Create symlink for iostat-cli
# Find the installed location and create symlink
sudo ln -sf $(which iostat-cli) /usr/local/bin/iostat-cli

echo "✓ install_python.sh completed successfully"
