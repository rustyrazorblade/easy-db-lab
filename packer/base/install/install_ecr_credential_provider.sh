#!/usr/bin/env bash
#
# Installs the kubelet ECR credential provider (kubernetes/cloud-provider-aws) and its
# CredentialProviderConfig, so every K3s kubelet pulls images from the account's ECR with the
# node instance role and no image pull secret. The kubelet asks the provider for a registry token
# when it pulls, so no stored credential can expire on a long-lived cluster.
#
# Where K3s reads them: K3s v1.35.1+k3s1 (install_k3s.sh) passes
# --image-credential-provider-bin-dir and --image-credential-provider-config to the kubelet by
# itself when both default paths exist, on the server and on agents:
#   - pkg/cli/cmds/agent.go: ImageCredProvBinDirFlag defaults to
#     /var/lib/rancher/credentialprovider/bin, ImageCredProvConfigFlag to
#     /var/lib/rancher/credentialprovider/config.yaml (server.go lists both flags too).
#   - pkg/daemons/agent/agent.go ImageCredProvAvailable: the bin dir must be a directory and the
#     config a file.
#   - pkg/daemons/agent/agent_linux.go: when available, sets image-credential-provider-bin-dir and
#     image-credential-provider-config in the kubelet args.
# So start_k3s_server.sh and start_k3s_agent.sh need no --kubelet-arg. The K3s data directory is
# relocated to /mnt/db1 at start, but /var/lib/rancher/credentialprovider is outside it.
#
# Env vars:
#   ARCH                      dpkg architecture (amd64 / arm64); detected when unset
#   CREDENTIAL_PROVIDER_ROOT  install root (default /var/lib/rancher/credentialprovider); tests
#                             point it at a sandbox
set -euo pipefail

echo "=== Running: install_ecr_credential_provider.sh ==="

# Pinned release of kubernetes/cloud-provider-aws. The binaries are published to
# artifacts.k8s.io, with a .sha256 next to each; the checksums below were read from those files and
# match the downloaded binaries. The plugin speaks credentialprovider.kubelet.k8s.io/v1, the stable
# kubelet plugin API, so it does not need to match the K3s minor version.
PROVIDER_VERSION="v1.37.0"
SHA256_AMD64="842e0fd8159f5ed8df2f38e6521e1e4f0a1ee80cf1e1ac119bbf20bf9c20681c"
SHA256_ARM64="dd4748d167b0167cd4a66779dbbe4964e17a29a076aaa3d2b63cfc36ae84b52c"
ECR_HOST_PATTERN="*.dkr.ecr.*.amazonaws.com"

if [ -z "${ARCH:-}" ]; then
  case "$(uname -m)" in
    x86_64)  ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
    *) echo "ERROR: unsupported architecture: $(uname -m)" >&2; exit 1 ;;
  esac
fi

case "$ARCH" in
  amd64) EXPECTED_SHA256="$SHA256_AMD64" ;;
  arm64) EXPECTED_SHA256="$SHA256_ARM64" ;;
  *) echo "ERROR: unsupported ARCH '${ARCH}' (expected amd64 or arm64)" >&2; exit 1 ;;
esac

ROOT="${CREDENTIAL_PROVIDER_ROOT:-/var/lib/rancher/credentialprovider}"
BIN_DIR="${ROOT}/bin"
CONFIG="${ROOT}/config.yaml"

if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi

DOWNLOAD="$(mktemp)"
trap 'rm -f "$DOWNLOAD"' EXIT

cached_fetch \
  "https://artifacts.k8s.io/binaries/cloud-provider-aws/${PROVIDER_VERSION}/linux/${ARCH}/ecr-credential-provider-linux-${ARCH}" \
  "ecr-credential-provider/${PROVIDER_VERSION}/ecr-credential-provider-linux-${ARCH}" \
  "$DOWNLOAD"

ACTUAL_SHA256="$(sha256sum "$DOWNLOAD" | cut -d' ' -f1)"
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "ERROR: ecr-credential-provider ${PROVIDER_VERSION} ${ARCH} checksum mismatch: expected ${EXPECTED_SHA256}, got ${ACTUAL_SHA256}" >&2
  exit 1
fi

sudo mkdir -p "$BIN_DIR"
# The kubelet runs the binary whose file name is the provider name in the config.
sudo install -m 0755 "$DOWNLOAD" "${BIN_DIR}/ecr-credential-provider"

sudo tee "$CONFIG" >/dev/null <<EOF
apiVersion: kubelet.config.k8s.io/v1
kind: CredentialProviderConfig
providers:
  - name: ecr-credential-provider
    matchImages:
      - "${ECR_HOST_PATTERN}"
    defaultCacheDuration: "12h"
    apiVersion: credentialprovider.kubelet.k8s.io/v1
EOF

# Verify what K3s checks before it wires the provider in: a bin directory with the binary in it
# and a config file that matches the ECR hosts.
[ -x "${BIN_DIR}/ecr-credential-provider" ] || { echo "ERROR: ${BIN_DIR}/ecr-credential-provider is not executable" >&2; exit 1; }
grep -qF -- "- \"${ECR_HOST_PATTERN}\"" "$CONFIG" || { echo "ERROR: ${CONFIG} does not match ${ECR_HOST_PATTERN}" >&2; exit 1; }

echo "✓ ecr-credential-provider ${PROVIDER_VERSION} (${ARCH}) installed in ${BIN_DIR}, config ${CONFIG}"
echo "✓ install_ecr_credential_provider.sh completed successfully"
