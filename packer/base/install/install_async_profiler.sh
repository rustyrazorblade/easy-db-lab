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

RELEASE_VERSION="4.3"
RELEASE="async-profiler-${RELEASE_VERSION}-linux-${ARCH}"
ARCHIVE="${RELEASE}.tar.gz"

sudo sysctl kernel.perf_event_paranoid=1
sudo sysctl kernel.kptr_restrict=0

# Use the shared S3 download cache when present; otherwise download directly (local script
# tests, or no cache configured).
if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi
cached_fetch \
    "https://github.com/async-profiler/async-profiler/releases/download/v${RELEASE_VERSION}/${ARCHIVE}" \
    "async-profiler/v${RELEASE_VERSION}/${ARCHIVE}" \
    "${ARCHIVE}"
tar zxf "$ARCHIVE"
sudo mv "$RELEASE" /usr/local/async-profiler

echo "✓ install_async_profiler.sh completed successfully"
