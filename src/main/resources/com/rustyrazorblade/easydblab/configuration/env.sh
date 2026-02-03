#@IgnoreInspection BashAddShebang

YELLOW='\033[0;33m'
YELLOW_BOLD='\033[1;33m'
NC_BOLD='\033[1m'
NC='\033[0m' # No Color

# Determine the cluster directory (where this script is located)
# Works in both bash and zsh
if [ -n "${ZSH_VERSION:-}" ]; then
    CLUSTER_DIR="$(dirname "$(realpath "$0")")"
else
    CLUSTER_DIR="$(dirname "$(realpath "${BASH_SOURCE[0]}")")"
fi

echo -e "${YELLOW_BOLD}[WARNING]${YELLOW} We are creating aliases which override these commands:${NC}"
echo -e "${NC_BOLD}  ssh\n  sftp\n  scp\n  rsync\n${NC}"
echo "The aliases point the commands they override to your new cluster."
echo -e "To undo these changes exit this terminal.\n"

mkdir -p "$CLUSTER_DIR/artifacts"

SSH_CONFIG="$CLUSTER_DIR/sshConfig"
ssh() { command ssh -F "$SSH_CONFIG" "$@"; }
sftp() { command sftp -F "$SSH_CONFIG" "$@"; }
scp() { command scp -F "$SSH_CONFIG" "$@"; }
rsync() { command rsync -ave "ssh -F $SSH_CONFIG" "$@"; }

# Configure kubectl to use the K3s cluster kubeconfig (if it exists)
if [ -f "$CLUSTER_DIR/kubeconfig" ]; then
  export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
  # k9s function - uses proxy only when Tailscale is not connected
  k9s() {
    if is-tailscale-connected; then
      command k9s --kubeconfig "$KUBECONFIG" "$@"
    else
      HTTPS_PROXY="socks5://localhost:$SOCKS5_PROXY_PORT" command k9s --kubeconfig "$KUBECONFIG" "$@"
    fi
  }
fi

# general purpose function for executing commands on all cassandra nodes
c-all () {
    for i in "${SERVERS[@]}"
    do
        echo "Executing on $i"
        ssh $i $@
    done
}

c-dl () {
    for i in "${SERVERS[@]}"
    do
        ssh $i "sudo chown -R ubuntu /mnt/db1/cassandra/artifacts/"
        rsync $i:/mnt/db1/cassandra/artifacts/ "$CLUSTER_DIR/artifacts/$i"
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

  if [[ $HOST =~ db[0-9*] ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh $HOST -C /usr/local/bin/flamegraph "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9*]."
  fi
}

c-flame-wall() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ db[0-9*] ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh $HOST -C /usr/local/bin/flamegraph -e wall -X '*Unsafe.park*'  -X '*Native.epollWait'  "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9*]."
  fi
}

c-flame-compaction() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ db[0-9*] ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh $HOST -C /usr/local/bin/flamegraph -e wall -X '*Unsafe.park*' -X '*Native.epollWait' -I '*compaction*' "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9*]."
  fi
}

c-flame-offcpu() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ db[0-9*] ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh $HOST -C /usr/local/bin/flamegraph -e kprobe:schedule -i 2 --cstack dwarf -X '*Unsafe.park*' "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9*]."
  fi
}

c-flame-sepworker() {
  HOST=$1
  [ -z "$HOST" ] &&  echo "Host is required"

  if [[ $HOST =~ db[0-9*] ]]; then
    mkdir -p "$CLUSTER_DIR/artifacts/$1"
    ssh $HOST -C /usr/local/bin/flamegraph -I '*SEPWorker*'  "${@:2}"
    c-dl
  else
    echo "Host must be in the format db[0-9*]."
  fi
}

# SOCKS5 proxy functions
# Global variable to store SOCKS5 proxy PID
SOCKS5_PROXY_PID=""
SOCKS5_PROXY_PORT=1080

# Check if Tailscale is connected on control node
# Returns 0 (true) if connected, 1 (false) otherwise
is-tailscale-connected() {
  # Check if tailscale command exists locally
  if ! command -v tailscale &>/dev/null; then
    return 1
  fi

  # Check if Tailscale is connected locally
  local ts_status
  ts_status=$(tailscale status --json 2>/dev/null)

  # Check if BackendState is "Running"
  [ -n "$ts_status" ] && echo "$ts_status" | grep -q '"BackendState":.*"Running"'
}

# Proxy wrapper for commands that need to access internal network (10.x.x.x)
# Usage: with-proxy curl http://10.0.1.50:8080/api
with-proxy() {
  ALL_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" \
  HTTP_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" \
  HTTPS_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" \
  NO_PROXY="localhost,127.0.0.1" \
  "$@"
}

# kubectl wrapper - uses proxy only when Tailscale is not connected
kubectl() {
  if is-tailscale-connected; then
    command kubectl "$@"
  else
    HTTPS_PROXY="socks5://localhost:$SOCKS5_PROXY_PORT" command kubectl "$@"
  fi
}

# curl wrapper - uses proxy only when Tailscale is not connected
curl() {
  if is-tailscale-connected; then
    command curl "$@"
  else
    ALL_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" command curl "$@"
  fi
}

# skopeo wrapper - uses proxy only when Tailscale is not connected
skopeo() {
  if is-tailscale-connected; then
    command skopeo "$@"
  else
    ALL_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" \
    HTTP_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" \
    HTTPS_PROXY="socks5h://localhost:$SOCKS5_PROXY_PORT" \
    NO_PROXY="localhost,127.0.0.1" \
    command skopeo "$@"
  fi
}

# Start SOCKS5 proxy via SSH dynamic port forwarding
start-socks5() {
  local port=${1:-$SOCKS5_PROXY_PORT}
  local proxy_state_file="$CLUSTER_DIR/.socks5-proxy-state"

  echo "Starting SOCKS5 proxy..."

  # Check if control0 exists in SSH config
  if ! grep -q "^Host control0" "$SSH_CONFIG" 2>/dev/null; then
    echo -e "${YELLOW}Warning: control0 not found in SSH config. Cannot start SOCKS5 proxy.${NC}"
    return 1
  fi

  # Get control host IP from sshConfig
  local control_ip=$(grep -A 1 "^Host control0" "$SSH_CONFIG" | grep "Hostname" | awk '{print $2}')

  # Check if proxy state file exists and validate
  if [ -f "$proxy_state_file" ]; then
    echo "Found existing proxy state, validating..."

    # Read existing proxy state (simple JSON parsing with grep/awk)
    local existing_pid=$(grep '"pid"' "$proxy_state_file" | awk -F: '{print $2}' | tr -d ' ,')
    local existing_ip=$(grep '"controlIP"' "$proxy_state_file" | awk -F'"' '{print $4}')
    local existing_ssh_config=$(grep '"sshConfig"' "$proxy_state_file" | awk -F'"' '{print $4}')

    local proxy_valid=true

    # Check if PID is still running
    if ! kill -0 "$existing_pid" 2>/dev/null; then
      echo "  - Previous proxy process (PID: $existing_pid) is no longer running"
      proxy_valid=false
    fi

    # Check if SSH config matches
    if [ "$existing_ssh_config" != "$SSH_CONFIG" ]; then
      echo "  - SSH config has changed (was: $existing_ssh_config, now: $SSH_CONFIG)"
      proxy_valid=false
    fi

    # Check if control host IP matches
    if [ "$existing_ip" != "$control_ip" ]; then
      echo "  - Control host IP has changed (was: $existing_ip, now: $control_ip)"
      proxy_valid=false
    fi

    # If proxy is invalid, clean it up
    if [ "$proxy_valid" = false ]; then
      echo "  - Stopping stale SOCKS5 proxy..."
      if kill "$existing_pid" 2>/dev/null; then
        echo "  - Stopped process $existing_pid"
      fi
      rm -f "$proxy_state_file"
    else
      echo -e "${YELLOW}Valid SOCKS5 proxy already running on localhost:$port [PID: $existing_pid]${NC}"

      echo "  - Use 'with-proxy <command>' to route commands through the proxy"
      echo "  - kubectl is automatically configured to use the proxy"
      echo "  - Use 'socks5-status' to check the status"
      return 0
    fi
  fi

  # Check if port is already in use (e.g., by MCP mode Kotlin proxy)
  if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${YELLOW}SOCKS5 proxy already running on localhost:$port (managed externally)${NC}"
    echo "  - This may be managed by easy-db-lab in MCP mode"

    echo "  - Use 'with-proxy <command>' to route commands through the proxy"
    echo "  - kubectl is automatically configured to use the proxy"
    echo "  - Use 'socks5-status' to check the status"
    return 0
  fi

  # Start SSH dynamic port forwarding (SOCKS5 proxy)
  ssh -F "$SSH_CONFIG" -N -D "$port" control0 &
  SOCKS5_PROXY_PID=$!

  # Wait a moment and verify the process is still running
  sleep 1
  if kill -0 $SOCKS5_PROXY_PID 2>/dev/null; then
    echo "  - SOCKS5 proxy started on localhost:$port [PID: $SOCKS5_PROXY_PID]"

    # Write proxy state to file
    local cluster_name=$(basename "$CLUSTER_DIR")
    local start_time=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    cat > "$proxy_state_file" <<EOF
{
  "pid": $SOCKS5_PROXY_PID,
  "port": $port,
  "controlHost": "control0",
  "controlIP": "$control_ip",
  "clusterName": "$cluster_name",
  "startTime": "$start_time",
  "sshConfig": "$SSH_CONFIG"
}
EOF

    echo "  - Proxy state saved to $proxy_state_file"

    echo ""
    echo -e "${NC_BOLD}SOCKS5 proxy is now active:${NC}"
    echo "  - SOCKS Host: localhost"
    echo "  - SOCKS Port: $port"
    echo "  - Use 'with-proxy <command>' to route commands through the proxy"
    echo "  - kubectl is automatically configured to use the proxy"
    echo ""
    echo -e "${NC_BOLD}To configure your browser:${NC}"
    echo "  - SOCKS Host: localhost"
    echo "  - SOCKS Port: $port"
    echo "  - SOCKS Version: 5"
    echo ""
    echo "Use 'stop-socks5' to stop the SOCKS5 proxy."
    return 0
  else
    echo -e "${YELLOW}Error: Failed to start SOCKS5 proxy${NC}"
    SOCKS5_PROXY_PID=""
    return 1
  fi
}

# Stop SOCKS5 proxy
stop-socks5() {
  local proxy_state_file="$CLUSTER_DIR/.socks5-proxy-state"

  echo "Stopping SOCKS5 proxy..."

  # Try to read PID from state file first
  if [ -f "$proxy_state_file" ]; then
    local proxy_pid=$(grep '"pid"' "$proxy_state_file" | awk -F: '{print $2}' | tr -d ' ,')
    if [ -n "$proxy_pid" ]; then
      if kill "$proxy_pid" 2>/dev/null; then
        echo "  - Stopped SOCKS5 proxy process [PID: $proxy_pid]"
      else
        echo "  - Process $proxy_pid not running"
      fi
    fi
    rm -f "$proxy_state_file"
    echo "  - Removed proxy state file"
  elif [ -n "$SOCKS5_PROXY_PID" ]; then
    # Fall back to global variable if no state file
    if kill $SOCKS5_PROXY_PID 2>/dev/null; then
      echo "  - Stopped SOCKS5 proxy process [PID: $SOCKS5_PROXY_PID]"
    fi
    SOCKS5_PROXY_PID=""
  else
    # Last resort: find existing SSH SOCKS5 proxy processes
    local PIDS=$(ps aux | grep -E "ssh.*-D.*control0" | grep -v grep | awk '{print $2}')
    if [ -n "$PIDS" ]; then
      echo "$PIDS" | while read pid; do
        if kill $pid 2>/dev/null; then
          echo "  - Stopped SOCKS5 proxy process [PID: $pid]"
        fi
      done
    else
      echo "  - No active SOCKS5 proxy processes found."
    fi
  fi

  echo "SOCKS5 proxy stopped."
}

# Check SOCKS5 proxy status
socks5-status() {
  echo "Checking SOCKS5 proxy status..."

  # Check for SSH CLI-based SOCKS5 proxy
  local SSH_PIDS=$(ps aux | grep -E "ssh.*-D.*control0" | grep -v grep)

  if [ -n "$SSH_PIDS" ]; then
    echo -e "${NC_BOLD}Active SSH SOCKS5 proxy:${NC}"
    echo "$SSH_PIDS" | while read line; do
      local pid=$(echo "$line" | awk '{print $2}')
      local port=$(echo "$line" | grep -oE '\-D [0-9]+' | awk '{print $2}')
      echo "  - SOCKS5 proxy active on localhost:${port:-$SOCKS5_PROXY_PORT} [SSH PID: $pid]"
    done
  elif lsof -Pi :$SOCKS5_PROXY_PORT -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo -e "${NC_BOLD}Active SOCKS5 proxy (non-SSH):${NC}"
    local PID=$(lsof -Pi :$SOCKS5_PROXY_PORT -sTCP:LISTEN -t 2>/dev/null | head -1)
    local COMMAND=$(ps -p "$PID" -o comm= 2>/dev/null)
    echo "  - SOCKS5 proxy active on localhost:$SOCKS5_PROXY_PORT [${COMMAND:-Unknown} PID: $PID]"
    echo "  - This may be managed by easy-db-lab in MCP mode"
  else
    echo "  - No active SOCKS5 proxy found."
  fi
}

# Aliases for convenience
alias socks5-start="start-socks5"
alias socks5-stop="stop-socks5"

# ClickHouse client helper (interactive)
clickhouse-client() {
  ssh -t control0 kubectl exec -it clickhouse-0 -c clickhouse -- clickhouse-client --user default --password default
}

# ClickHouse query helper (non-interactive, sends query via HTTP POST)
# Usage: clickhouse-query "SELECT 1" or clickhouse-query <<< "SELECT 1"
clickhouse-query() {
  local query="${1:-$(cat)}"
  local control_ip=$(easy-db-lab ip db0 --private)
  with-proxy curl -s -u "default:default" "http://${control_ip}:8123/" -d "$query"
}

# Conditionally start SOCKS5 proxy based on Tailscale status
if is-tailscale-connected; then
  echo -e "${NC_BOLD}[INFO]${NC} Tailscale VPN is connected - skipping SOCKS5 proxy."
  echo "  Direct network access to VPC resources is available."
else
  start-socks5
fi
