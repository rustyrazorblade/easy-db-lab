#!/bin/bash

echo "=== Running: install_flamegraph_service.sh ==="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$(dirname "$SCRIPT_DIR")/bin"
SERVICES_DIR="$(dirname "$SCRIPT_DIR")/services"

# Install the flamegraph-to-pyroscope script
sudo cp "$BIN_DIR/flamegraph-to-pyroscope" /usr/local/bin/flamegraph-to-pyroscope
sudo chmod +x /usr/local/bin/flamegraph-to-pyroscope

echo "Installed flamegraph-to-pyroscope to /usr/local/bin/"

# Install the SystemD service
sudo cp "$SERVICES_DIR/flamegraph-cassandra.service" /etc/systemd/system/flamegraph-cassandra.service

echo "Installed flamegraph-cassandra.service to /etc/systemd/system/"

sudo systemctl daemon-reload

echo "install_flamegraph_service.sh completed successfully"
