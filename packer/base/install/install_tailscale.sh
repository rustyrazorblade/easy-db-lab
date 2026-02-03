#!/bin/bash
# Install Tailscale VPN client
# Tailscale is installed but NOT auto-started - the daemon is started on-demand via easy-db-lab commands
set -e

echo "=== Installing Tailscale ==="

# Add Tailscale's official GPG key and repository
curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/noble.noarmor.gpg | sudo tee /usr/share/keyrings/tailscale-archive-keyring.gpg >/dev/null
curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/noble.tailscale-keyring.list | sudo tee /etc/apt/sources.list.d/tailscale.list

# Install tailscale
sudo apt-get update
sudo apt-get install -y tailscale

# Disable auto-start - we start on-demand via easy-db-lab tailscale start
sudo systemctl disable tailscaled
sudo systemctl stop tailscaled

echo "✓ Tailscale installed successfully (daemon disabled)"
