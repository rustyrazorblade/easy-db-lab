#!/bin/bash

set -euo pipefail

echo "=== Running: install_kubectl.sh ==="

KUBECTL_MINOR="v1.35"

curl -fsSL "https://pkgs.k8s.io/core:/stable:/${KUBECTL_MINOR}/deb/Release.key" \
    | gpg --dearmor \
    | sudo tee /etc/apt/keyrings/kubernetes-apt-keyring.gpg > /dev/null

echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/${KUBECTL_MINOR}/deb/ /" \
    | sudo tee /etc/apt/sources.list.d/kubernetes.list

sudo apt-get update
sudo apt-get install -y --no-install-recommends kubectl

kubectl version --client || { echo "ERROR: kubectl installation verification failed" >&2; exit 1; }

echo "✓ install_kubectl.sh completed successfully"
