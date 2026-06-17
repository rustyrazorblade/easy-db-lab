#!/usr/bin/env bash
#
# Installs the shared S3 cache library and restores the apt archive cache. Runs early in the build,
# right after the AWS CLI is available. Persists the cache config so every later install script can
# source /usr/local/lib/edl-cache.sh without needing the env vars passed again.
#
# Env vars (set by the Packer provisioner):
#   EDL_S3_BUCKET  account bucket name (empty disables all caching)
#   EDL_ARCH       dpkg architecture (amd64 / arm64)
set -euo pipefail

echo "=== Running: setup_s3_cache.sh ==="

EDL_S3_BUCKET="${EDL_S3_BUCKET:-}"
EDL_ARCH="${EDL_ARCH:-amd64}"

# Persist config for later scripts that source the library
sudo tee /etc/edl-cache.env >/dev/null <<EOF
EDL_S3_BUCKET=${EDL_S3_BUCKET}
EDL_ARCH=${EDL_ARCH}
EOF

# Install the cache library (uploaded to /tmp by a file provisioner)
sudo mkdir -p /usr/local/lib
sudo mv /tmp/edl-cache-lib.sh /usr/local/lib/edl-cache.sh

# shellcheck disable=SC1091
. /usr/local/lib/edl-cache.sh
apt_cache_restore

if [ -n "$EDL_S3_BUCKET" ]; then
    echo "✓ S3 cache enabled (bucket: ${EDL_S3_BUCKET}, arch: ${EDL_ARCH})"
else
    echo "✓ No S3 bucket configured; caching disabled (downloads go straight to origin)"
fi
