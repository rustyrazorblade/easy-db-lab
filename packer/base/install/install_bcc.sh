#!/usr/bin/env bash
set -euo pipefail

echo "=== Running: install_bcc.sh ==="

# Uploaded to /tmp/s3_cache.sh by the packer file provisioner in base.pkr.hcl
# shellcheck source=../../lib/s3_cache.sh
source "/tmp/s3_cache.sh"

BCC_VERSION=0.35.0
WORK_DIR=""

# Cleanup function for trap
cleanup() {
    if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
        echo "Cleaning up temporary directory..."
        sudo rm -rf "$WORK_DIR"
    fi
}

trap cleanup EXIT

# Check if BCC is already installed
if command -v bcc-lua &> /dev/null; then
    INSTALLED_VERSION=$(dpkg -l | grep bpfcc-tools | awk '{print $3}' || echo "unknown")
    echo "BCC already installed (version: $INSTALLED_VERSION), skipping installation"
    exit 0
fi

echo "Installing BCC version ${BCC_VERSION}..."

CACHE_KEY="packer-build-cache/bcc/bcc-v${BCC_VERSION}-$(uname -m).tar.gz"
CACHE_ARCHIVE=$(mktemp --suffix=".tar.gz")

# Try to restore from S3 cache before compiling.
# Use nested if for tar extraction so a corrupted archive falls back to compilation
# rather than aborting the build under set -euo pipefail.
if s3_cache_get "${PACKER_CACHE_BUCKET:-}" "${CACHE_KEY}" "${CACHE_ARCHIVE}"; then
    echo "Extracting BCC from cache..."
    if sudo tar -xzf "${CACHE_ARCHIVE}" -C /; then
        rm -f "${CACHE_ARCHIVE}"
        echo "Verifying BCC installation from cache..."
        if ! python3 -c "import bcc" 2>/dev/null; then
            echo "ERROR: BCC Python module not found after cache restore"
            exit 1
        fi
        echo "BCC ${BCC_VERSION} restored from cache successfully"
        echo "✓ install_bcc.sh completed successfully"
        exit 0
    else
        echo "WARNING: Cache archive extraction failed, falling back to compilation"
        rm -f "${CACHE_ARCHIVE}"
    fi
else
    rm -f "${CACHE_ARCHIVE}"
fi

# Remove any existing BCC installations
echo "Removing existing BCC packages..."
sudo apt update
sudo apt purge -y bpfcc-tools libbpfcc python3-bpfcc || true

# Install build dependencies
echo "Installing build dependencies..."
sudo apt install -y zip bison build-essential cmake flex git libedit-dev \
  libllvm14 llvm-14-dev libclang-14-dev libpolly-14-dev python3 zlib1g-dev libelf-dev libfl-dev python3-setuptools \
  liblzma-dev libdebuginfod-dev arping netperf iperf

# Create temp directory for build
WORK_DIR=$(mktemp -d)
cd "$WORK_DIR"

# Download BCC source
echo "Downloading BCC ${BCC_VERSION}..."
wget -q --show-progress "https://github.com/iovisor/bcc/releases/download/v${BCC_VERSION}/bcc-src-with-submodule.tar.gz"

# Verify download succeeded and has content
if [ ! -s bcc-src-with-submodule.tar.gz ]; then
    echo "ERROR: Download failed or file is empty"
    exit 1
fi

echo "Extracting source..."
tar xf bcc-src-with-submodule.tar.gz

# Create python symlink if needed (idempotent)
if [ ! -e /usr/bin/python ]; then
    echo "Creating python symlink..."
    sudo ln -s /usr/bin/python3 /usr/bin/python
fi

# Build and install BCC
# Reference: https://github.com/iovisor/bcc/blob/master/INSTALL.md
echo "Building BCC (this may take several minutes)..."
cd bcc/
mkdir build
cd build/

echo "Running cmake..."
cmake -DREVISION=${BCC_VERSION} -DENABLE_EXAMPLES=OFF -DENABLE_TESTS=OFF .. > /dev/null

echo "Compiling..."
make -j$(nproc)

echo "Installing BCC..."
sudo make install > /dev/null

echo "Building Python3 bindings..."
cmake -DREVISION=${BCC_VERSION} -DPYTHON_CMD=/usr/bin/python3 .. > /dev/null
pushd src/python/ > /dev/null
make -j$(nproc)
sudo make install > /dev/null
popd > /dev/null

# Verify installation
echo "Verifying BCC installation..."
if ! python3 -c "import bcc" 2>/dev/null; then
    echo "ERROR: BCC Python module not found after installation"
    exit 1
fi

# Upload compiled artifacts to S3 cache (best-effort)
echo "Creating cache archive of installed BCC artifacts..."
CACHE_ARCHIVE=$(mktemp --suffix=".tar.gz")
# Collect installed paths; libbpf may not always be present
BCC_CACHE_PATHS=(/usr/lib/libbcc* /usr/share/bcc /usr/lib/python3/dist-packages/bcc)
if compgen -G "/usr/lib/libbpf*" > /dev/null 2>&1; then
    BCC_CACHE_PATHS+=(/usr/lib/libbpf*)
fi
sudo tar -czf "${CACHE_ARCHIVE}" "${BCC_CACHE_PATHS[@]}" 2>/dev/null || true
# Make archive readable by the non-root user running aws s3 cp
sudo chmod a+r "${CACHE_ARCHIVE}"
s3_cache_put "${PACKER_CACHE_BUCKET:-}" "${CACHE_KEY}" "${CACHE_ARCHIVE}"
rm -f "${CACHE_ARCHIVE}"

echo "BCC ${BCC_VERSION} installed successfully"
echo "✓ install_bcc.sh completed successfully"
