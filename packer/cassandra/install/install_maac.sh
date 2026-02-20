#!/bin/bash
# Install Management API for Apache Cassandra (MAAC) metrics agent
# Downloads version-specific JARs that expose Cassandra metrics as Prometheus endpoint on port 9000
set -euo pipefail

MAAC_VERSION="0.1.113"
MAAC_URL="https://github.com/k8ssandra/management-api-for-apache-cassandra/releases/download/v${MAAC_VERSION}/jars.zip"
MAAC_BASE="/opt/management-api"
TEMP_DIR=$(mktemp -d)

echo "Downloading MAAC agent v${MAAC_VERSION}..."
curl -fsSL -o "${TEMP_DIR}/jars.zip" "${MAAC_URL}"

echo "Extracting MAAC JARs..."
cd "${TEMP_DIR}"
unzip -q jars.zip

# Install version-specific agent JARs
sudo mkdir -p "${MAAC_BASE}/4.0" "${MAAC_BASE}/4.1" "${MAAC_BASE}/5.0" "${MAAC_BASE}/configs"

sudo cp "management-api-agent-4.x/target/datastax-mgmtapi-agent-4.x-${MAAC_VERSION}.jar" \
    "${MAAC_BASE}/4.0/datastax-mgmtapi-agent.jar"

sudo cp "management-api-agent-4.1.x/target/datastax-mgmtapi-agent-4.1.x-${MAAC_VERSION}.jar" \
    "${MAAC_BASE}/4.1/datastax-mgmtapi-agent.jar"

sudo cp "management-api-agent-5.0.x/target/datastax-mgmtapi-agent-5.0.x-${MAAC_VERSION}.jar" \
    "${MAAC_BASE}/5.0/datastax-mgmtapi-agent.jar"

# Create metrics collector config
sudo tee "${MAAC_BASE}/configs/metrics-collector.yaml" > /dev/null <<'EOF'
endpoint:
  address: "127.0.0.1"
  port: 9000
EOF

# Set ownership
sudo chown -R cassandra: "${MAAC_BASE}"

# Clean up
rm -rf "${TEMP_DIR}"

echo "MAAC agent v${MAAC_VERSION} installed successfully"
echo "  4.0 JAR: ${MAAC_BASE}/4.0/datastax-mgmtapi-agent.jar"
echo "  4.1 JAR: ${MAAC_BASE}/4.1/datastax-mgmtapi-agent.jar"
echo "  5.0 JAR: ${MAAC_BASE}/5.0/datastax-mgmtapi-agent.jar"
echo "  Config:  ${MAAC_BASE}/configs/metrics-collector.yaml"
