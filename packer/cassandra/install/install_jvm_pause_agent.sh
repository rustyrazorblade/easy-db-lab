#!/bin/bash
set -e

echo "=== Running: install_jvm_pause_agent.sh ==="

INSTALL_DIR="/usr/local/jvm-pause-agent"
JAR_NAME="jvm-pause-agent.jar"

# RELEASE_VERSION is passed in from packer as an environment variable.
# If not set (e.g., during packer tests), skip installation gracefully.
if [ -z "${RELEASE_VERSION}" ]; then
    echo "INFO: RELEASE_VERSION not set, skipping jvm-pause-agent installation"
    echo "INFO: The agent JAR will not be present on this image."
    echo "INFO: To install manually, place the JAR at ${INSTALL_DIR}/${JAR_NAME}"
    exit 0
fi

DOWNLOAD_URL="https://github.com/rustyrazorblade/easy-db-lab/releases/download/v${RELEASE_VERSION}/jvm-pause-agent-${RELEASE_VERSION}.jar"

sudo mkdir -p "${INSTALL_DIR}"

echo "Downloading jvm-pause-agent from ${DOWNLOAD_URL}"
if wget -q "${DOWNLOAD_URL}" -O "/tmp/${JAR_NAME}"; then
    sudo mv "/tmp/${JAR_NAME}" "${INSTALL_DIR}/${JAR_NAME}"
    sudo chmod 644 "${INSTALL_DIR}/${JAR_NAME}"
    echo "jvm-pause-agent installed to ${INSTALL_DIR}/${JAR_NAME}"
else
    echo "WARNING: Failed to download jvm-pause-agent from ${DOWNLOAD_URL}"
    echo "WARNING: The agent will not be active on this image."
fi

echo "✓ install_jvm_pause_agent.sh completed successfully"
