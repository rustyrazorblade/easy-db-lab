#!/bin/bash
set -euo pipefail

# Bootstrap action: Install OTel Java agent on EMR nodes
# This script is uploaded to S3 and executed during EMR cluster bootstrap.

OTEL_DIR="/opt/otel"
AGENT_URL="__OTEL_AGENT_DOWNLOAD_URL__"
AGENT_JAR="$OTEL_DIR/opentelemetry-javaagent.jar"

echo "Installing OTel Java agent to $AGENT_JAR"
sudo mkdir -p "$OTEL_DIR"
sudo curl -fsSL -o "$AGENT_JAR" "$AGENT_URL"
sudo chmod 644 "$AGENT_JAR"
echo "OTel Java agent installed successfully"
