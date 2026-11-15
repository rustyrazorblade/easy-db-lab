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
# cached_fetch: S3 download cache when EDL_S3_BUCKET is set, direct download otherwise.
# EDL_CACHE_LIB points tests at the repo copy; the AMI has it at /usr/local/lib/edl-cache.sh.
# shellcheck disable=SC1090
source "${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}"
cached_fetch \
    "https://github.com/derailed/k9s/releases/download/${K9S_VERSION}/${RELEASE}" \
    "k9s/${K9S_VERSION}/${RELEASE}" \
    "${RELEASE}"

tar -xzf "${RELEASE}"
sudo mv k9s /usr/local/bin/
rm -f "${RELEASE}" LICENSE README.md

# Verify installation
k9s version || { echo "ERROR: k9s installation verification failed" >&2; exit 1; }

echo "✓ install_k9s.sh completed successfully"
