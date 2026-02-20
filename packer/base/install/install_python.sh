#!/bin/bash
set -e

echo "=== Running: install_python.sh ==="

# Ensure non-interactive mode for apt
export DEBIAN_FRONTEND=noninteractive

# Add deadsnakes PPA for additional Python versions
sudo DEBIAN_FRONTEND=noninteractive add-apt-repository ppa:deadsnakes/ppa -y
sudo DEBIAN_FRONTEND=noninteractive apt update -y

# Install Python 3.10 with development packages
sudo DEBIAN_FRONTEND=noninteractive apt install -y python3.10 python3.10-dev python3.10-venv python3-pip

# Setup update-alternatives for python version management
# Make Python 3.10 the default python command
sudo update-alternatives --install /usr/bin/python python /usr/bin/python3.10 1
sudo update-alternatives --set python /usr/bin/python3.10

# Install uv (fast Python package installer) via official installer
echo "Installing uv to /usr/local/bin..."
curl -LsSf https://astral.sh/uv/install.sh | sudo env INSTALLER_NO_MODIFY_PATH=1 UV_INSTALL_DIR=/usr/local/bin sh

# Verify installation
uv --version

# Install iostat-tool using uv
uv tool install iostat-tool

# Create symlink for iostat-cli
# Find the installed location and create symlink
sudo ln -sf $(which iostat-cli) /usr/local/bin/iostat-cli

echo "✓ install_python.sh completed successfully"
