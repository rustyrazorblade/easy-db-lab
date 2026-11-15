#!/bin/bash
set -euo pipefail

echo "=== Running: install_otel_agent.sh ==="

OTEL_VERSION="2.31.1"
OTEL_JAR="opentelemetry-javaagent.jar"

sudo mkdir -p /usr/local/otel

# SHA-256 for each version: gh api repos/open-telemetry/opentelemetry-java-instrumentation/releases/tags/v${OTEL_VERSION} | jq -r '.assets[] | select(.name=="opentelemetry-javaagent.jar") | .digest'
OTEL_SHA256="bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba"

# Download the OpenTelemetry Java agent (via S3 cache)
# cached_fetch: S3 download cache when EDL_S3_BUCKET is set, direct download otherwise.
# EDL_CACHE_LIB points tests at the repo copy; the AMI has it at /usr/local/lib/edl-cache.sh.
# shellcheck disable=SC1090
source "${EDL_CACHE_LIB:-/usr/local/lib/edl-cache.sh}"
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
