#!/bin/bash

set -euo pipefail

echo "=== Running: install_ecr_credential_provider.sh ==="

# Install the kubelet ECR image credential provider so every node pulls
# *.dkr.ecr.*.amazonaws.com images using the node's instance role, with no per-pod
# imagePullSecrets. kubelet runs this binary on demand and caches the token it returns.
# The binary and its config path are referenced by --kubelet-arg in the k3s start
# scripts (services/start-k3s-server.sh, services/start-k3s-agent.sh); keep the paths
# below in sync with those scripts.

# Pinned to a cloud-provider-aws release whose binaries are published on the public
# Kubernetes artifacts CDN. The SHA-256 digests are pinned here (copied from the CDN's
# .sha256 files for this release), so a replaced artifact fails the build. Upgrading the
# version means changing the version and both digests together.
ECR_CP_VERSION="v1.37.0"
ECR_CP_SHA256_AMD64="842e0fd8159f5ed8df2f38e6521e1e4f0a1ee80cf1e1ac119bbf20bf9c20681c"
ECR_CP_SHA256_ARM64="dd4748d167b0167cd4a66779dbbe4964e17a29a076aaa3d2b63cfc36ae84b52c"
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

case "${ARCH}" in
  amd64) EXPECTED_SHA="${ECR_CP_SHA256_AMD64}" ;;
  arm64) EXPECTED_SHA="${ECR_CP_SHA256_ARM64}" ;;
  *) echo "ERROR: unsupported ARCH for ecr-credential-provider: ${ARCH}" >&2; exit 1 ;;
esac

ASSET="ecr-credential-provider-linux-${ARCH}"
BASE_URL="https://artifacts.k8s.io/binaries/cloud-provider-aws/${ECR_CP_VERSION}/linux/${ARCH}/${ASSET}"

# Download into a private temp directory, removed on exit.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT
cd "${WORK_DIR}"

echo "Downloading ecr-credential-provider ${ECR_CP_VERSION} for ${ARCH}..."
echo "Installing binary to ${ECR_CP_BIN_DIR}, config to ${ECR_CP_CONFIG}"
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

# Verify against the pinned digest (this also validates a cached copy).
echo "${EXPECTED_SHA}  ${ASSET}" | sha256sum --check -

sudo mkdir -p "${ECR_CP_BIN_DIR}"
sudo install -m 0755 "${ASSET}" "${ECR_CP_BIN_DIR}/ecr-credential-provider"

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
