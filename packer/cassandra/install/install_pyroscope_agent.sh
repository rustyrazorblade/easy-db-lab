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

PYROSCOPE_VERSION="0.14.0"
PYROSCOPE_JAR="pyroscope.jar"

sudo mkdir -p /usr/local/pyroscope

# Download the Pyroscope Java agent
wget -q "https://github.com/grafana/pyroscope-java/releases/download/v${PYROSCOPE_VERSION}/${PYROSCOPE_JAR}" \
    -O "/tmp/${PYROSCOPE_JAR}"

sudo mv "/tmp/${PYROSCOPE_JAR}" "/usr/local/pyroscope/${PYROSCOPE_JAR}"
sudo chmod 644 "/usr/local/pyroscope/${PYROSCOPE_JAR}"

echo "Pyroscope Java agent installed to /usr/local/pyroscope/${PYROSCOPE_JAR}"

echo "✓ install_pyroscope_agent.sh completed successfully"
