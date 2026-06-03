#!/bin/bash

set -euo pipefail

echo "=== Running: install_cilium_cli.sh ==="

CILIUM_CLI_VERSION="v0.19.4"

cpu_arch=$(uname -m)
if [[ "$cpu_arch" == "x86_64" ]]; then
    ARCH="amd64"
elif [[ "$cpu_arch" == "aarch64" ]]; then
    ARCH="arm64"
else
    echo "Unsupported architecture: $cpu_arch"
    exit 1
fi

TARBALL="cilium-linux-${ARCH}.tar.gz"

echo "Downloading cilium CLI ${CILIUM_CLI_VERSION} for ${ARCH}..."
curl -fsSL --retry 3 \
    "https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/${TARBALL}" \
    -o "${TARBALL}"

curl -fsSL --retry 3 \
    "https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/${TARBALL}.sha256sum" \
    -o "${TARBALL}.sha256sum"

sha256sum --check "${TARBALL}.sha256sum"

tar -xzvf "${TARBALL}" cilium
sudo mv cilium /usr/local/bin/
rm -f "${TARBALL}" "${TARBALL}.sha256sum"

cilium version --client || { echo "ERROR: cilium CLI installation verification failed" >&2; exit 1; }

echo "✓ install_cilium_cli.sh completed successfully"
