#!/bin/bash
# Configure containerd and K3s to trust the registry's TLS certificate
# Arguments: REGISTRY_HOST REGISTRY_PORT S3_BUCKET S3_PATH

set -e

REGISTRY_HOST=$1
REGISTRY_PORT=$2
S3_BUCKET=$3
S3_PATH=$4

CERT_DIR="/etc/containerd/certs.d/${REGISTRY_HOST}:${REGISTRY_PORT}"

sudo mkdir -p "$CERT_DIR"

# Download certificate from S3 (use full path since sudo doesn't preserve PATH)
sudo /usr/local/bin/aws s3 cp --no-progress "s3://$S3_BUCKET/$S3_PATH" "$CERT_DIR/ca.crt"

# Create hosts.toml configuration for containerd
sudo tee "$CERT_DIR/hosts.toml" > /dev/null <<EOF
server = "https://${REGISTRY_HOST}:${REGISTRY_PORT}"

[host."https://${REGISTRY_HOST}:${REGISTRY_PORT}"]
  ca = "$CERT_DIR/ca.crt"
EOF

# Configure K3s registry mirror with HTTPS
# This must be done before K3s starts so it picks up the configuration
sudo mkdir -p /etc/rancher/k3s
sudo tee /etc/rancher/k3s/registries.yaml > /dev/null <<EOF
mirrors:
  "${REGISTRY_HOST}:${REGISTRY_PORT}":
    endpoint:
      - "https://${REGISTRY_HOST}:${REGISTRY_PORT}"
configs:
  "${REGISTRY_HOST}:${REGISTRY_PORT}":
    tls:
      ca_file: "$CERT_DIR/ca.crt"
EOF
echo "✓ K3s registry configuration written to /etc/rancher/k3s/registries.yaml"

# Restart containerd to apply changes (if running)
if systemctl is-active --quiet containerd; then
    sudo systemctl restart containerd
    echo "Containerd restarted"
else
    echo "Containerd not running yet, skipping restart"
fi
