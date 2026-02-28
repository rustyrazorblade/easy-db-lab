#!/bin/bash
set -e

# Ensure non-interactive mode for apt
export DEBIAN_FRONTEND=noninteractive

# needed early on before we do anything with /mnt
if mountpoint -q /mnt; then
  echo "/mnt is a mount point."
  sudo umount -l -f /mnt
else
  echo "/mnt is not a mount point."
fi

sudo DEBIAN_FRONTEND=noninteractive apt update
sudo DEBIAN_FRONTEND=noninteractive apt upgrade -y
sudo DEBIAN_FRONTEND=noninteractive apt update

sudo DEBIAN_FRONTEND=noninteractive apt install -y wget sysstat unzip ripgrep ant ant-optional tree zfsutils-linux nicstat inotify-tools

# Create directory for exec tool logs (shipped to VictoriaLogs by OTel filelog/tools receiver)
sudo mkdir -p /var/log/easydblab/tools

cpu_arch=$(uname -m)
# Set ARCH based on the CPU architecture
if [[ "$cpu_arch" == "x86_64" ]]; then
    sudo DEBIAN_FRONTEND=noninteractive apt install -y cpuid
elif [[ "$cpu_arch" == "aarch64" ]]; then
  echo "No additional packages needed for ARM64"
else
    echo "Unsupported architecture: $cpu_arch"
    exit 1
fi


