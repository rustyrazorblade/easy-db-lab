#!/usr/bin/env bash
set -euo pipefail

echo "=== Running: install_bcc.sh ==="

# Ubuntu 26.04 ships bpfcc-tools 0.35.0 as a pre-built package; no source build needed.
sudo DEBIAN_FRONTEND=noninteractive apt install -y bpfcc-tools libbpfcc python3-bpfcc

echo "✓ install_bcc.sh completed successfully"
