#!/bin/bash

set -euo pipefail

echo "=== Running: install_helm.sh ==="

HELM_VERSION="v3.17.3"

echo "Installing helm ${HELM_VERSION}..."
curl -fsSL "https://raw.githubusercontent.com/helm/helm/${HELM_VERSION}/scripts/get-helm-3" | DESIRED_VERSION="${HELM_VERSION}" bash

helm version || { echo "ERROR: helm installation verification failed" >&2; exit 1; }

echo "✓ install_helm.sh completed successfully"
