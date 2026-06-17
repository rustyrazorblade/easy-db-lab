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
# Use the shared S3 download cache when present; otherwise download directly (local script
# tests, or no cache configured).
if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi
cached_fetch \
    "https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/${TARBALL}" \
    "cilium-cli/${CILIUM_CLI_VERSION}/${TARBALL}" \
    "${TARBALL}"

# Always fetch the checksum from origin and verify (validates cache integrity too)
curl -fsSL --retry 3 \
    "https://github.com/cilium/cilium-cli/releases/download/${CILIUM_CLI_VERSION}/${TARBALL}.sha256sum" \
    -o "${TARBALL}.sha256sum"

sha256sum --check "${TARBALL}.sha256sum"

tar -xzvf "${TARBALL}" cilium
sudo mv cilium /usr/local/bin/
rm -f "${TARBALL}" "${TARBALL}.sha256sum"

cilium version --client || { echo "ERROR: cilium CLI installation verification failed" >&2; exit 1; }

echo "✓ install_cilium_cli.sh completed successfully"
