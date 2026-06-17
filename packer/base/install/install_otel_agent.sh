#!/bin/bash
set -euo pipefail

echo "=== Running: install_otel_agent.sh ==="

OTEL_VERSION="2.25.0"
OTEL_JAR="opentelemetry-javaagent.jar"

sudo mkdir -p /usr/local/otel

# SHA-256 for each version: gh api repos/open-telemetry/opentelemetry-java-instrumentation/releases/tags/v${OTEL_VERSION} | jq -r '.assets[] | select(.name=="opentelemetry-javaagent.jar") | .digest'
OTEL_SHA256="d6e809824176cf88792db359a9d928281ba2102fa8755453c1940f6c0289e396"

# Download the OpenTelemetry Java agent (via S3 cache)
# Use the shared S3 download cache when present; otherwise download directly (local script
# tests, or no cache configured).
if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi
cached_fetch \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/${OTEL_JAR}" \
    "otel-agent/v${OTEL_VERSION}/${OTEL_JAR}" \
    "/tmp/${OTEL_JAR}"

# Verify the checksum (validates cache integrity too)
echo "${OTEL_SHA256}  /tmp/${OTEL_JAR}" | sha256sum -c -

sudo mv "/tmp/${OTEL_JAR}" "/usr/local/otel/${OTEL_JAR}"
sudo chmod 644 "/usr/local/otel/${OTEL_JAR}"

echo "OpenTelemetry Java agent installed to /usr/local/otel/${OTEL_JAR}"

echo "✓ install_otel_agent.sh completed successfully"
