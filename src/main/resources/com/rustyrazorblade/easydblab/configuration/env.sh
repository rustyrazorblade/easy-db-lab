#@IgnoreInspection BashAddShebang

YELLOW='\033[0;33m'
YELLOW_BOLD='\033[1;33m'
NC_BOLD='\033[1m'
NC='\033[0m' # No Color

# Determine the cluster directory (where this script is located)
# Works in both bash and zsh, and when sourced from a different directory. CDPATH is cleared in
# the subshell only: with it set, cd could resolve a relative path to another directory.
if [ -n "${ZSH_VERSION:-}" ]; then
    CLUSTER_DIR="$(unset CDPATH; cd "$(dirname "${(%):-%x}")" && pwd)"
else
    CLUSTER_DIR="$(unset CDPATH; cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

echo -e "${YELLOW_BOLD}[WARNING]${YELLOW} We are creating aliases which override these commands:${NC}"
echo -e "${NC_BOLD}  ssh\n  sftp\n  scp\n  rsync\n${NC}"
echo "The aliases point the commands they override to your new cluster."
echo "kubectl, helm, cilium, curl, skopeo and k9s now run this workspace's wrappers in $CLUSTER_DIR/bin,"
echo "which reach the cluster through the SOCKS5 tunnel (or directly on a Tailscale cluster)."
echo -e "To undo these changes exit this terminal.\n"

# The tool wrappers in bin/ replace the shell functions an older env.sh defined. Drop any such
# function left in this shell, so the names resolve to the wrappers. The check keeps zsh from
# printing an error for a name that is not defined, and keeps `set -e` shells alive.
for _edl_tool in kubectl helm cilium curl skopeo k9s; do
  if typeset -f "$_edl_tool" >/dev/null 2>&1; then
    unset -f "$_edl_tool"
  fi
done
unset _edl_tool

# Put the wrappers first on PATH, once, however often this file is sourced.
case "$PATH" in
  "$CLUSTER_DIR/bin:"*) ;;
  *) export PATH="$CLUSTER_DIR/bin:$PATH" ;;
esac

mkdir -p "$CLUSTER_DIR/artifacts"

SSH_CONFIG="$CLUSTER_DIR/sshConfig"
ssh() { command ssh -F "$SSH_CONFIG" "$@"; }
sftp() { command sftp -F "$SSH_CONFIG" "$@"; }
scp() { command scp -F "$SSH_CONFIG" "$@"; }
rsync() { command rsync -ave "ssh -F $SSH_CONFIG" "$@"; }

# Configure kubectl, helm and k9s to use the K3s cluster kubeconfig (if it exists)
if [ -f "$CLUSTER_DIR/kubeconfig" ]; then
  export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
fi

# general purpose function for executing commands on all cassandra nodes
c-all () {
    for i in "${SERVERS[@]}"
    do
        echo "Executing on $i"
        ssh "$i" "$@"
    done
}

c-dl () {
    for i in "${SERVERS[@]}"
    do
        ssh "$i" "sudo chown -R ubuntu /mnt/db1/cassandra/artifacts/"
        rsync "$i:/mnt/db1/cassandra/artifacts/" "$CLUSTER_DIR/artifacts/$i"
    done
}

alias c-restart="c-all /usr/local/bin/restart-cassandra-and-wait"
alias c-status="c0 nodetool status"
alias c-tpstats="c-all nodetool tpstats"

alias c-start="c-all sudo systemctl start cassandra.service"
alias c-df="c-all df -h | grep -E 'cassandra|Filesystem'"


c-flame() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ ^db[0-9]+ ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh "$HOST" -C /usr/local/bin/flamegraph "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9]+."
  fi
}

c-flame-wall() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ ^db[0-9]+ ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh "$HOST" -C /usr/local/bin/flamegraph -e wall -X '*Unsafe.park*'  -X '*Native.epollWait'  "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9]+."
  fi
}

c-flame-compaction() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ ^db[0-9]+ ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh "$HOST" -C /usr/local/bin/flamegraph -e wall -X '*Unsafe.park*' -X '*Native.epollWait' -I '*compaction*' "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9]+."
  fi
}

c-flame-offcpu() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ ^db[0-9]+ ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh "$HOST" -C /usr/local/bin/flamegraph -e kprobe:schedule -i 2 --cstack dwarf -X '*Unsafe.park*' "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9]+."
  fi
}

c-flame-sepworker() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ ^db[0-9]+ ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh "$HOST" -C /usr/local/bin/flamegraph -I '*SEPWorker*'  "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9]+."
  fi
}

# SOCKS5 proxy helpers. The easy-db-lab CLI is the only thing that starts or stops the tunnel
# (`easy-db-lab start-socks`, `easy-db-lab stop-socks`); it records the state shell-side tools need
# in .socks5-proxy.env, which these helpers source. Nothing here reads JSON.

# The port of the tunnel the CLI recorded, or nothing when none is recorded.
_socks5_port() {
  (
    unset EDL_SOCKS_PORT
    if [ -f "$CLUSTER_DIR/.socks5-proxy.env" ]; then
      . "$CLUSTER_DIR/.socks5-proxy.env"
    fi
    printf '%s\n' "${EDL_SOCKS_PORT:-}"
  )
}

# "true" when the cluster is reached over Tailscale, as the CLI recorded it.
_edl_tailscale_active() {
  (
    unset EDL_TAILSCALE_ACTIVE
    if [ -f "$CLUSTER_DIR/.socks5-proxy.env" ]; then
      . "$CLUSTER_DIR/.socks5-proxy.env"
    fi
    printf '%s\n' "${EDL_TAILSCALE_ACTIVE:-}"
  )
}

# Runs any command with its traffic routed through the SOCKS5 tunnel, for tools that have no wrapper.
# On a Tailscale cluster the command runs directly.
# Usage: with-proxy curl http://10.0.1.50:8080/api
with-proxy() {
  if [ "$(_edl_tailscale_active)" = true ]; then
    "$@"
    return
  fi
  local port
  port=$(_socks5_port)
  if [ -z "$port" ]; then
    echo "easy-db-lab: no SOCKS tunnel is recorded for the workspace $CLUSTER_DIR." >&2
    echo "easy-db-lab: run 'easy-db-lab start-socks' in $CLUSTER_DIR, then try again." >&2
    return 1
  fi
  ALL_PROXY="socks5h://localhost:$port" \
  HTTP_PROXY="socks5h://localhost:$port" \
  HTTPS_PROXY="socks5h://localhost:$port" \
  NO_PROXY="localhost,127.0.0.1" \
  "$@"
}

# Shows the tunnel the CLI recorded, and whether anything listens on its port.
socks5-status() {
  if [ "$(_edl_tailscale_active)" = true ]; then
    echo "This cluster uses Tailscale: no SOCKS5 tunnel is needed."
    return 0
  fi
  local port
  port=$(_socks5_port)
  if [ -z "$port" ]; then
    echo "No SOCKS5 tunnel is recorded. Run 'easy-db-lab start-socks' to start one."
  elif lsof -Pi :"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "SOCKS5 tunnel active on localhost:$port"
  else
    echo "SOCKS5 tunnel recorded on localhost:$port, but nothing listens there. Run 'easy-db-lab start-socks' to restart it."
  fi
}

# ClickHouse client helper (interactive). Extra arguments go to clickhouse-client.
# The Altinity operator names server pods chi-clickhouse-clickhouse-<shard>-<replica>-0, so the
# first Running pod of the CHI is found by label. The kit's default user has no password.
clickhouse-client() {
  local pod
  pod=$(ssh control0 'kubectl get pods -n default -l clickhouse.altinity.com/chi=clickhouse --field-selector=status.phase=Running -o jsonpath="{.items[0].metadata.name}"')
  if [ -z "$pod" ]; then
    echo "No running ClickHouse server pod found (label clickhouse.altinity.com/chi=clickhouse)." >&2
    return 1
  fi
  local args=""
  [ $# -gt 0 ] && args=$(printf '%q ' "$@")
  ssh -t control0 "kubectl exec -it -n default $pod -c clickhouse -- clickhouse-client $args"
}

# ClickHouse query helper (non-interactive, sends query via HTTP POST)
# Usage: clickhouse-query "SELECT 1" or clickhouse-query <<< "SELECT 1"
# The kit exposes HTTP on NodePort 30123 of every db node (clickhouse-nodeport); container
# port 8123 is not bound on the host. The kit's default user has no password.
clickhouse-query() {
  local query="${1:-$(cat)}"
  local db_ip=$(easy-db-lab ip db0 --private)
  curl -s "http://${db_ip}:30123/" -d "$query"
}

# The easy-db-lab CLI starts the SOCKS5 tunnel for the commands that need it, and 'easy-db-lab
# start-socks' starts it for everything else (the wrappers, with-proxy, a browser).
# Use 'with-proxy <command>' for a tool that has no wrapper, or 'socks5-status' to check the tunnel.
