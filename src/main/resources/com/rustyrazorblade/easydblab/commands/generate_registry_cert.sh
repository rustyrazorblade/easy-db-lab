#!/bin/bash
# Generate self-signed TLS certificate for container registry
# Arguments: REGISTRY_IP CERT_DIR S3_BUCKET S3_PATH

set -ex

REGISTRY_IP=$1
CERT_DIR=$2
S3_BUCKET=$3
S3_PATH=$4

echo "Generating certificate for IP: $REGISTRY_IP"
echo "Certificate directory: $CERT_DIR"
echo "S3 bucket: $S3_BUCKET"
echo "S3 path: $S3_PATH"

sudo mkdir -p "$CERT_DIR"

# Generate self-signed certificate with IP SAN
sudo openssl req -newkey rsa:4096 -nodes -sha256 \
    -keyout "$CERT_DIR/registry.key" \
    -x509 -days 365 \
    -out "$CERT_DIR/registry.crt" \
    -subj "/CN=$REGISTRY_IP" \
    -addext "subjectAltName=IP:$REGISTRY_IP" \
    2>&1

sudo chmod 600 "$CERT_DIR/registry.key"
sudo chmod 644 "$CERT_DIR/registry.crt"

echo "Certificate generated successfully"
ls -la "$CERT_DIR/"

# Upload certificate to S3 (use full path since sudo doesn't preserve PATH)
sudo /usr/local/bin/aws s3 cp --no-progress "$CERT_DIR/registry.crt" "s3://$S3_BUCKET/$S3_PATH"
echo "Certificate uploaded to S3"
