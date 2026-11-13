#!/bin/bash

set -euo pipefail

echo "=== Running: install_ecr_credential_provider.sh ==="

# Install the kubelet ECR image credential provider so every node pulls
# *.dkr.ecr.*.amazonaws.com images using the node's instance role, with no per-pod
# imagePullSecrets. kubelet runs this binary on demand and caches the token it returns.
# The binary and its config path are referenced by --kubelet-arg in the k3s start
# scripts (services/start-k3s-server.sh, services/start-k3s-agent.sh); keep the paths
# below in sync with those scripts.

# Pinned to the cloud-provider-aws release whose binaries are published on the public
# Kubernetes artifacts CDN. The checksums are fetched from origin and verified below.
ECR_CP_VERSION="v1.37.0"
ECR_CP_BIN_DIR="/var/lib/rancher/credentialprovider/bin"
ECR_CP_CONFIG="/var/lib/rancher/credentialprovider/config.yaml"

# Detect architecture from the running system if Packer did not provide ARCH.
# On a Graviton build the builder instance is aarch64, so uname resolves it
# correctly even when ARCH is unset.
if [ -z "${ARCH:-}" ]; then
  case "$(uname -m)" in
    x86_64)  ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
    *) echo "Unsupported architecture: $(uname -m)"; exit 1 ;;
  esac
fi

ASSET="ecr-credential-provider-linux-${ARCH}"
BASE_URL="https://artifacts.k8s.io/binaries/cloud-provider-aws/${ECR_CP_VERSION}/linux/${ARCH}/${ASSET}"

echo "Downloading ecr-credential-provider ${ECR_CP_VERSION} for ${ARCH}..."
# Use the shared S3 download cache when present; otherwise download directly (local script
# tests, or no cache configured).
if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi
cached_fetch \
    "${BASE_URL}" \
    "cloud-provider-aws/${ECR_CP_VERSION}/${ASSET}" \
    "${ASSET}"

# Always fetch the checksum from origin and verify (validates cache integrity too). The
# CDN publishes the bare hex digest with no filename, so build the check line here.
EXPECTED_SHA="$(curl -fsSL --retry 3 "${BASE_URL}.sha256")"
echo "${EXPECTED_SHA}  ${ASSET}" | sha256sum --check -

sudo mkdir -p "${ECR_CP_BIN_DIR}"
sudo install -m 0755 "${ASSET}" "${ECR_CP_BIN_DIR}/ecr-credential-provider"
rm -f "${ASSET}"

# Write the CredentialProviderConfig kubelet reads. matchImages covers the standard,
# China and FIPS ECR hostnames; the provider resolves the region and credentials from
# IMDSv2 using the node's instance role. defaultCacheDuration bounds how long kubelet
# reuses a token (an ECR token is valid for 12h).
sudo tee "${ECR_CP_CONFIG}" > /dev/null <<'EOF'
apiVersion: kubelet.config.k8s.io/v1
kind: CredentialProviderConfig
providers:
  - name: ecr-credential-provider
    matchImages:
      - "*.dkr.ecr.*.amazonaws.com"
      - "*.dkr.ecr.*.amazonaws.com.cn"
      - "*.dkr.ecr-fips.*.amazonaws.com"
    defaultCacheDuration: "12h"
    apiVersion: credentialprovider.kubelet.k8s.io/v1
EOF
sudo chmod 0644 "${ECR_CP_CONFIG}"

# Verify the binary is in place and executable, and the config is present. The binary
# itself is a kubelet plugin that reads a request on stdin, so it is not run here.
test -x "${ECR_CP_BIN_DIR}/ecr-credential-provider" \
    || { echo "ERROR: ecr-credential-provider is not installed or not executable" >&2; exit 1; }
test -s "${ECR_CP_CONFIG}" || { echo "ERROR: credential provider config missing" >&2; exit 1; }

echo "✓ install_ecr_credential_provider.sh completed successfully"
