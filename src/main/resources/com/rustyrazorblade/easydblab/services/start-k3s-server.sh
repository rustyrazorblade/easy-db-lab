#!/bin/bash

set -euo pipefail

# Moves the directory $2 onto the data mount $1 as $3 and leaves a symlink at $2, so its contents
# live on the NVMe data disk instead of the root volume. Runs only when $1 is a mount point and $2
# is not already a symlink, so a second run leaves the link alone. Existing contents are copied.
relocate_to_data_mount() {
    local mount="$1" dir="$2" target="$3"
    if mountpoint -q "$mount" && [ ! -L "$dir" ]; then
        echo "Relocating $dir to $target..."
        mkdir -p "$target"
        if [ -d "$dir" ]; then
            cp -a "$dir/." "$target/"
            rm -rf "$dir"
        fi
        mkdir -p "$(dirname "$dir")"
        ln -s "$target" "$dir"
        echo "$dir relocated to $target"
    fi
}

echo "=== Starting K3s Server ==="

# Parse flags
NO_FLANNEL=false
for arg in "$@"; do
    [ "$arg" = "--flannel-backend=none" ] && NO_FLANNEL=true
done

# The K3s data directory holds container images and every local-path PVC, so database data lands on
# NVMe and the root EBS volume does not fill up. Pod logs (/var/log/pods) move too: database logs
# never live on the root volume. The OTel Collector mounts /mnt/db1/container-logs to follow the link.
relocate_to_data_mount /mnt/db1 /var/lib/rancher/k3s /mnt/db1/k3s
relocate_to_data_mount /mnt/db1 /var/log/pods /mnt/db1/container-logs

# Cilium chains the portmap CNI plugin for hostPort. With --flannel-backend=none, K3s leaves
# containerd on its default CNI bin dir, /opt/cni/bin, where Cilium installs only cilium-cni.
# Link K3s's bundled portmap (its stable data/cni dir, populated when K3s starts) there so every
# pod sandbox finds it. Inert under Flannel, whose containerd uses K3s's own bin dir.
mkdir -p /opt/cni/bin
ln -sfn /var/lib/rancher/k3s/data/cni/portmap /opt/cni/bin/portmap

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
        K3S_EXEC_ARGS='server --write-kubeconfig-mode=644 --flannel-backend=none --disable-network-policy --disable=traefik,servicelb'
    else
        K3S_EXEC_ARGS='server --write-kubeconfig-mode=644'
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
