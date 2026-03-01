#!/bin/bash
set -euo pipefail

# Bootstrap action: Install OTel Java agent, OTel Collector, and Pyroscope Java agent on EMR nodes
# This script is uploaded to S3 and executed during EMR cluster bootstrap.
# The control node IP is resolved at upload time by TemplateService in the collector config.

OTEL_DIR="/opt/otel"
PYROSCOPE_DIR="/opt/pyroscope"

# OTel Java Agent
AGENT_URL="__OTEL_AGENT_DOWNLOAD_URL__"
AGENT_JAR="$OTEL_DIR/opentelemetry-javaagent.jar"

# OTel Collector
COLLECTOR_URL="__OTEL_COLLECTOR_DOWNLOAD_URL__"
COLLECTOR_BIN="$OTEL_DIR/otelcol-contrib"
COLLECTOR_CONFIG="$OTEL_DIR/config.yaml"

# Pyroscope Java Agent
PYROSCOPE_URL="__PYROSCOPE_AGENT_DOWNLOAD_URL__"
PYROSCOPE_JAR="$PYROSCOPE_DIR/pyroscope.jar"

echo "=== Installing OTel and Pyroscope agents ==="

# Create directories
sudo mkdir -p "$OTEL_DIR" "$PYROSCOPE_DIR"

# --- OTel Java Agent ---
echo "Installing OTel Java agent to $AGENT_JAR"
sudo curl -fsSL -o "$AGENT_JAR" "$AGENT_URL"
sudo chmod 644 "$AGENT_JAR"
echo "OTel Java agent installed successfully"

# --- OTel Collector ---
echo "Installing OTel Collector to $COLLECTOR_BIN"
TMPDIR=$(mktemp -d)
sudo curl -fsSL -o "$TMPDIR/otelcol.tar.gz" "$COLLECTOR_URL"
sudo tar -xzf "$TMPDIR/otelcol.tar.gz" -C "$TMPDIR"
sudo mv "$TMPDIR/otelcol-contrib" "$COLLECTOR_BIN"
sudo chmod 755 "$COLLECTOR_BIN"
rm -rf "$TMPDIR"
echo "OTel Collector installed successfully"

# --- OTel Collector Config ---
echo "Writing OTel Collector config to $COLLECTOR_CONFIG"
sudo tee "$COLLECTOR_CONFIG" > /dev/null << 'CONFIGEOF'
__OTEL_COLLECTOR_CONFIG__
CONFIGEOF
# Note: __CONTROL_NODE_IP__ is resolved at upload time by TemplateService
echo "OTel Collector config written"

# --- OTel Collector systemd service ---
echo "Creating otel-collector systemd service"
sudo tee /etc/systemd/system/otel-collector.service > /dev/null << 'SERVICEEOF'
[Unit]
Description=OpenTelemetry Collector
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/otel/otelcol-contrib --config /opt/otel/config.yaml
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
SERVICEEOF

sudo systemctl daemon-reload
sudo systemctl enable otel-collector
sudo systemctl start otel-collector
echo "OTel Collector service started"

# --- Pyroscope Java Agent ---
echo "Installing Pyroscope Java agent to $PYROSCOPE_JAR"
sudo curl -fsSL -o "$PYROSCOPE_JAR" "$PYROSCOPE_URL"
sudo chmod 644 "$PYROSCOPE_JAR"
echo "Pyroscope Java agent installed successfully"

echo "=== All agents installed successfully ==="
