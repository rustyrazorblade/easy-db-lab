#!/bin/bash

echo "=== Running: install_pyroscope_agent.sh ==="

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

PYROSCOPE_VERSION="2.3.0"
PYROSCOPE_JAR="pyroscope.jar"

sudo mkdir -p /usr/local/pyroscope

# Download the Pyroscope Java agent (via S3 cache)
# cached_fetch: S3 download cache when EDL_S3_BUCKET is set, direct download otherwise.
# EDL_CACHE_LIB points tests at the repo copy; the AMI has it at /usr/local/lib/edl-cache.sh.
# shellcheck disable=SC1090
source "${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}"
cached_fetch \
    "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_VERSION}/${PYROSCOPE_JAR}" \
    "pyroscope/v${PYROSCOPE_VERSION}/${PYROSCOPE_JAR}" \
    "/tmp/${PYROSCOPE_JAR}"

sudo mv "/tmp/${PYROSCOPE_JAR}" "/usr/local/pyroscope/${PYROSCOPE_JAR}"
sudo chmod 644 "/usr/local/pyroscope/${PYROSCOPE_JAR}"

echo "Pyroscope Java agent installed to /usr/local/pyroscope/${PYROSCOPE_JAR}"

echo "✓ install_pyroscope_agent.sh completed successfully"
