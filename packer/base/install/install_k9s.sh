#!/bin/bash

set -ex

echo "=== Running: install_k9s.sh ==="

# Get the architecture using uname
cpu_arch=$(uname -m)

# Set ARCH based on the CPU architecture
if [[ "$cpu_arch" == "x86_64" ]]; then
    ARCH="amd64"
elif [[ "$cpu_arch" == "aarch64" ]]; then
    ARCH="arm64"
else
    echo "Unsupported architecture: $cpu_arch"
    exit 1
fi

echo "ARCH is set to: $ARCH"

K9S_VERSION="v0.50.18"
RELEASE="k9s_Linux_${ARCH}.tar.gz"

echo "Downloading k9s ${K9S_VERSION} for ${ARCH}..."
wget "https://github.com/derailed/k9s/releases/download/${K9S_VERSION}/${RELEASE}" || { echo "ERROR: k9s download failed" >&2; exit 1; }

tar -xzvf "${RELEASE}"
sudo mv k9s /usr/local/bin/
rm -f "${RELEASE}" LICENSE README.md

# Verify installation
k9s version || { echo "ERROR: k9s installation verification failed" >&2; exit 1; }

echo "✓ install_k9s.sh completed successfully"
