#!/bin/bash

echo "=== Running: install_async_profiler.sh ==="

# Get the architecture using uname
cpu_arch=$(uname -m)

# Set ARCH based on the CPU architecture
if [[ "$cpu_arch" == "x86_64" ]]; then
    ARCH="x64"
elif [[ "$cpu_arch" == "aarch64" ]]; then
    ARCH="arm64"
else
    echo "Unsupported architecture: $cpu_arch"
    exit 1
fi

echo "ARCH is set to: $ARCH"

RELEASE_VERSION="4.5"
RELEASE="async-profiler-${RELEASE_VERSION}-linux-${ARCH}"
ARCHIVE="${RELEASE}.tar.gz"

sudo sysctl kernel.perf_event_paranoid=1
sudo sysctl kernel.kptr_restrict=0

# cached_fetch: S3 download cache when EDL_S3_BUCKET is set, direct download otherwise.
# EDL_CACHE_LIB points tests at the repo copy; the AMI has it at /usr/local/lib/edl-cache.sh.
# shellcheck disable=SC1090
source "${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}"
cached_fetch \
    "https://github.com/async-profiler/async-profiler/releases/download/v${RELEASE_VERSION}/${ARCHIVE}" \
    "async-profiler/v${RELEASE_VERSION}/${ARCHIVE}" \
    "${ARCHIVE}"
tar zxf "$ARCHIVE"
sudo mv "$RELEASE" /usr/local/async-profiler

echo "✓ install_async_profiler.sh completed successfully"
