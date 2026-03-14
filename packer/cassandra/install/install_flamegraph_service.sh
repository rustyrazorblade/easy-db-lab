#!/bin/bash

echo "=== Running: install_flamegraph_service.sh ==="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Install the flamegraph-to-pyroscope script
sudo install -m 0755 "${SCRIPT_DIR}/../bin/flamegraph-to-pyroscope" /usr/local/bin/flamegraph-to-pyroscope

# Install the SystemD one-shot service unit
sudo install -m 0644 "${SCRIPT_DIR}/../services/flamegraph-cassandra.service" \
    /etc/systemd/system/flamegraph-cassandra.service

sudo systemctl daemon-reload

echo "Installed flamegraph-to-pyroscope to /usr/local/bin/"
echo "Installed flamegraph-cassandra.service to /etc/systemd/system/"
echo "Trigger with: sudo systemctl start flamegraph-cassandra"
echo "✓ install_flamegraph_service.sh completed successfully"
