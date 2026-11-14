#!/bin/bash

set -euo pipefail

echo "=== Starting K3s Server ==="

# Parse flags. The pod and service ranges and the node CIDR size come from the CLI
# (Constants.K3s): K3s's default 10.42.0.0/16 with a /24 per node runs out at 256 nodes.
NO_FLANNEL=false
CIDR_ARGS=""
for arg in "$@"; do
    case "$arg" in
        --flannel-backend=none) NO_FLANNEL=true ;;
        --cluster-cidr=*|--service-cidr=*|--kube-controller-manager-arg=node-cidr-mask-size=*)
            CIDR_ARGS="$CIDR_ARGS $arg" ;;
    esac
done

# Relocate K3s data directory to NVMe storage if available
# This prevents the root EBS volume from filling up with container images
if mountpoint -q /mnt/db1 && [ ! -L /var/lib/rancher/k3s ]; then
    echo "Relocating K3s data to NVMe at /mnt/db1/k3s..."
    mkdir -p /mnt/db1/k3s
    if [ -d /var/lib/rancher/k3s ]; then
        cp -a /var/lib/rancher/k3s/. /mnt/db1/k3s/
        rm -rf /var/lib/rancher/k3s
    fi
    ln -s /mnt/db1/k3s /var/lib/rancher/k3s
    echo "K3s data relocated to NVMe"
fi

# Relocate container logs to NVMe storage to keep the root volume free. The kubelet writes pod
# logs under /var/log/pods, and may have created that directory already; `ln -s` into an existing
# directory would put the link inside it, so what is there moves first.
if mountpoint -q /mnt/db1 && [ ! -L /var/log/pods ]; then
    echo "Relocating container logs to NVMe at /mnt/db1/container-logs..."
    mkdir -p /mnt/db1/container-logs
    if [ -d /var/log/pods ]; then
        cp -a /var/log/pods/. /mnt/db1/container-logs/
        rm -rf /var/log/pods
    fi
    ln -s /mnt/db1/container-logs /var/log/pods
    echo "Container logs relocated to NVMe"
fi

# On a host whose data disk holds the database, that disk is also the kubelet's nodefs and
# imagefs (k3s keeps its kubelet and containerd data under /var/lib/rancher/k3s, which is on
# /mnt/db1). The kubelet's default hard eviction (nodefs.available<10%) then evicts pods when the
# database fills its own disk, including the database's pods, which cannot start again (their
# local PV is bound to the node, and DiskPressure keeps the scheduler off it), and the node's
# observability pods. Eviction frees nothing there, so the disk signals are set to 0%. Memory
# eviction and the inode guard keep their kubelet defaults; an explicit eviction-hard replaces
# every default, so they are restated. This is a k3s config file, not an installer argument, so it
# is rewritten on every run and `%` needs no escaping for systemd.
if mountpoint -q /mnt/db1; then
    mkdir -p /etc/rancher/k3s/config.yaml.d
    cat > /etc/rancher/k3s/config.yaml.d/10-kubelet-eviction.yaml <<'EOF'
kubelet-arg:
  - "eviction-hard=memory.available<100Mi,nodefs.inodesFree<5%,nodefs.available<0%,imagefs.available<0%"
EOF
    echo "Kubelet disk eviction signals set to 0% in /etc/rancher/k3s/config.yaml.d/10-kubelet-eviction.yaml"
fi

# Cilium chains the portmap CNI plugin for hostPort. With --flannel-backend=none, K3s leaves
# containerd on its default CNI bin dir, /opt/cni/bin, where Cilium installs only cilium-cni.
# Link K3s's bundled portmap (its stable data/cni dir, populated when K3s starts) there so every
# pod sandbox finds it. Inert under Flannel, whose containerd uses K3s's own bin dir.
mkdir -p /opt/cni/bin
ln -sfn /var/lib/rancher/k3s/data/cni/portmap /opt/cni/bin/portmap

# Kubelet ECR image credential provider: every node pulls *.dkr.ecr.*.amazonaws.com images
# using its instance role, with no per-pod imagePullSecrets. The binary and config are baked
# into the AMI by install/install_ecr_credential_provider.sh; keep these paths in sync.
KUBELET_ECR_ARGS="--kubelet-arg=image-credential-provider-config=/var/lib/rancher/credentialprovider/config.yaml --kubelet-arg=image-credential-provider-bin-dir=/var/lib/rancher/credentialprovider/bin"

# Note: Registry TLS configuration is handled by configure_registry_tls.sh
# which runs before K3s startup to configure /etc/rancher/k3s/registries.yaml with HTTPS

# Check if k3s.service already exists
if systemctl list-unit-files k3s.service --no-pager --no-legend 2>/dev/null | grep -q k3s.service; then
    echo "k3s.service already installed, skipping installation"
else
    echo "Installing k3s in server mode..."

    if [ "$NO_FLANNEL" = "true" ]; then
        echo "Custom CNI mode: disabling built-in Flannel, Traefik, and ServiceLB"
        # Traefik and ServiceLB are disabled with Cilium. ServiceLB (klipper) publishes every node
        # InternalIP as the traefik LoadBalancer ingress IP, and Cilium installs a port-0 wildcard
        # per LoadBalancer IP that rejects every pod packet to a node IP on a non-service port, so
        # kubelet probes never get their SYN-ACK and no pod in the pod network becomes Ready.
        K3S_EXEC_ARGS="server --write-kubeconfig-mode=644 --flannel-backend=none --disable-network-policy --disable=traefik,servicelb$CIDR_ARGS $KUBELET_ECR_ARGS"
    else
        K3S_EXEC_ARGS="server --write-kubeconfig-mode=644$CIDR_ARGS $KUBELET_ECR_ARGS"
    fi

    INSTALL_K3S_SKIP_DOWNLOAD=true \
    INSTALL_K3S_EXEC="$K3S_EXEC_ARGS" \
    /usr/local/bin/install-k3s.sh

    echo "✓ K3s server installed successfully"
fi

# Ensure the NVMe data volume is mounted before k3s starts on every boot.
# The k3s data dir (/var/lib/rancher/k3s) is a symlink onto /mnt/db1. On a
# reboot systemd could otherwise start k3s before /mnt/db1 is remounted, and
# k3s crash-loops with "extracting data: no such file or directory".
# RequiresMountsFor pulls in and orders k3s after the /mnt/db1 mount unit.
if mountpoint -q /mnt/db1; then
    mkdir -p /etc/systemd/system/k3s.service.d
    cat > /etc/systemd/system/k3s.service.d/10-nvme-mount.conf <<'EOF'
[Unit]
RequiresMountsFor=/mnt/db1
EOF
    systemctl daemon-reload
fi

# Start the k3s service
echo "Starting k3s.service..."
systemctl start k3s

echo "✓ K3s server started successfully"
