#!/usr/bin/env bash
#
# Installs yq via the S3 download cache (falls back to a direct download when the cache is absent).
#
# Env vars (set by the Packer provisioner):
#   ARCH  dpkg architecture (amd64 / arm64)
set -euo pipefail

echo "=== Running: install_yq.sh ==="

ARCH="${ARCH:?ARCH must be set}"
YQ_VERSION="v4.41.1"

# shellcheck disable=SC1090
source "${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}"

cached_fetch \
    "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_${ARCH}" \
    "yq/${YQ_VERSION}/yq_linux_${ARCH}" \
    /tmp/yq
sudo install -m 0755 /tmp/yq /usr/local/bin/yq
rm -f /tmp/yq

echo "✓ install_yq.sh completed successfully"
