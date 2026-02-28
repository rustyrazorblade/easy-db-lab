#!/bin/bash

echo "=== Running: install_otel_agent.sh ==="

OTEL_VERSION="2.25.0"
OTEL_JAR="opentelemetry-javaagent.jar"

sudo mkdir -p /usr/local/otel

# Download the OpenTelemetry Java agent
wget -q "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/${OTEL_JAR}" \
    -O "/tmp/${OTEL_JAR}"

sudo mv "/tmp/${OTEL_JAR}" "/usr/local/otel/${OTEL_JAR}"
sudo chmod 644 "/usr/local/otel/${OTEL_JAR}"

echo "OpenTelemetry Java agent installed to /usr/local/otel/${OTEL_JAR}"

echo "✓ install_otel_agent.sh completed successfully"
