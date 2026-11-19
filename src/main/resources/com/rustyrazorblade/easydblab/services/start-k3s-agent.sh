#!/bin/bash

set -euo pipefail

echo "=== Starting K3s Agent ==="

# "start" on the first join attempt; "restart" when up retries a join that did not register.
MODE="${1:-start}"
case "$MODE" in
    start|restart) ;;
    *)
        echo "ERROR: unknown mode '$MODE', expected start or restart"
        echo "Usage: $0 [start|restart]"
        exit 1
        ;;
esac

# The server URL and the node token come from the agent config that up writes first (mode 0600).
# They are never passed on the command line: a remote command line reaches failure messages and
# events, and the token must not. The token is never printed.
AGENT_CONFIG=/etc/rancher/k3s/config.yaml

# Prints the value of a top-level "key: value" line, without the quotes the YAML writer adds.
# Prints nothing when the file or the key is missing.
config_value() {
    [ -r "$AGENT_CONFIG" ] || return 0
    sed -n "s/^$1:[[:space:]]*//p" "$AGENT_CONFIG" | head -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

SERVER_URL="$(config_value server)"
NODE_TOKEN="$(config_value token)"
if [ -z "$SERVER_URL" ] || [ -z "$NODE_TOKEN" ]; then
    echo "ERROR: $AGENT_CONFIG has no server or no token; the agent must be configured before it starts"
    exit 1
fi

echo "Server URL: $SERVER_URL"

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

# K3s runs the kubelet in its own process, and the kubelet sets that process's open-file limit to
# its max-open-files, 1000000 by default, below the 1048576 node tuning gives the unit. Set it to
# the unit's value, so the running k3s-agent process has the limit edl-node-tuning verify checks.
# kubelet-arg+ adds to the kubelet args of the eviction file instead of replacing them.
mkdir -p /etc/rancher/k3s/config.yaml.d
cat > /etc/rancher/k3s/config.yaml.d/15-kubelet-limits.yaml <<'EOF'
kubelet-arg+:
  - "max-open-files=1048576"
EOF

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

# Check if k3s-agent.service already exists
if systemctl list-unit-files k3s-agent.service --no-pager --no-legend 2>/dev/null | grep -q k3s-agent.service; then
    echo "k3s-agent.service already installed, skipping installation"
else
    echo "Installing k3s in agent mode..."

    # Run airgap installation in agent mode. INSTALL_K3S_EXEC carries the kubelet ECR
    # credential provider args; "agent" keeps the subcommand that K3S_URL already implies.
    INSTALL_K3S_SKIP_DOWNLOAD=true \
    K3S_URL="$SERVER_URL" \
    K3S_TOKEN="$NODE_TOKEN" \
    INSTALL_K3S_EXEC="agent $KUBELET_ECR_ARGS" \
    /usr/local/bin/install-k3s.sh

    echo "✓ K3s agent installed successfully"
fi

# Ensure the NVMe data volume is mounted before k3s-agent starts on every boot.
# The k3s data dir (/var/lib/rancher/k3s) is a symlink onto /mnt/db1. On a
# reboot systemd could otherwise start k3s-agent before /mnt/db1 is remounted,
# leaving it unable to find its data dir. RequiresMountsFor pulls in and orders
# k3s-agent after the /mnt/db1 mount unit.
if mountpoint -q /mnt/db1; then
    mkdir -p /etc/systemd/system/k3s-agent.service.d
    cat > /etc/systemd/system/k3s-agent.service.d/10-nvme-mount.conf <<'EOF'
[Unit]
RequiresMountsFor=/mnt/db1
EOF
    systemctl daemon-reload
fi

# Start (or, on a retry, restart) the k3s-agent service without waiting for it. The agent can sit
# for minutes waiting on a busy server, and a blocking start held this SSH session for all of it
# (#1134). `up` reads the node's registration from the K8s API instead.
echo "Running systemctl $MODE --no-block k3s-agent..."
systemctl "$MODE" --no-block k3s-agent

echo "✓ K3s agent $MODE queued"
