#!/bin/bash
set -e

echo "Installing cassandra-easy-stress from pre-built artifact..."

# Download the latest pre-built release
TARBALL_URL="https://github.com/apache/cassandra-easy-stress/releases/download/latest/cassandra-easy-stress-latest.tar.gz"
TEMP_DIR=$(mktemp -d)

echo "Downloading cassandra-easy-stress from ${TARBALL_URL}..."
cd "${TEMP_DIR}"
curl -fsSL -o cassandra-easy-stress-latest.tar.gz "${TARBALL_URL}"

echo "Extracting tarball..."
tar -xzf cassandra-easy-stress-latest.tar.gz

# Create installation directory
echo "Installing cassandra-easy-stress to /usr/local/cassandra-easy-stress..."
sudo rm -rf /usr/local/cassandra-easy-stress
sudo mkdir -p /usr/local/cassandra-easy-stress

# Find the extracted directory (should be cassandra-easy-stress or similar)
EXTRACTED_DIR=$(find . -maxdepth 1 -type d -name "cassandra-easy-stress*" | head -n 1)

if [ -z "${EXTRACTED_DIR}" ]; then
    echo "Error: Could not find extracted cassandra-easy-stress directory"
    ls -la
    exit 1
fi

echo "Found extracted directory: ${EXTRACTED_DIR}"

# Copy the distribution contents
sudo cp -r "${EXTRACTED_DIR}"/* /usr/local/cassandra-easy-stress/

# Ensure scripts are executable
sudo chmod +x /usr/local/cassandra-easy-stress/bin/*

# Clean up temporary files
echo "Cleaning up temporary files..."
cd /
rm -rf "${TEMP_DIR}"

echo "cassandra-easy-stress installation complete!"
echo "Binary available at: /usr/local/cassandra-easy-stress/bin/cassandra-easy-stress"
