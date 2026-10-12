#!/bin/sh
# easy-db-lab tool wrapper.
#
# easy-db-lab writes this script into <workspace>/bin/ once for each of kubectl, helm, cilium, curl,
# skopeo and k9s, and rewrites it when it changes, so do not edit the copies. Kit shell steps, hooks
# and shells that sourced env.sh have <workspace>/bin first on PATH, so every call of those tools,
# indirect ones included (env, xargs, sh -c, nested scripts), runs through here.
#
# Each call reads <workspace>/.socks5-proxy.env, which only the easy-db-lab CLI writes:
#   - on a Tailscale cluster it runs the real tool with the environment unchanged;
#   - on a SOCKS cluster it routes this one call through the recorded tunnel port;
#   - with no tunnel recorded, or when the recorded tunnel script (EDL_SOCKS_PID) has exited, it fails
#     and says to run `easy-db-lab socks start`.
# kubectl, helm, cilium and k9s always use <workspace>/kubeconfig, in place of any inherited KUBECONFIG,
# because a wrapper reaches only its own workspace's cluster. curl and skopeo leave KUBECONFIG alone.
# The proxy variables are set for this call only, so tools that are not wrapped (aws included)
# never get them.
#
# It must stay POSIX sh: it runs under dash on Linux and under /bin/sh on macOS.

tool=${0##*/}
# CDPATH is cleared in the subshell only: with it set, cd could resolve a relative bin/ to another
# directory and print that directory too.
bin_dir=$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
workspace=$(dirname "$bin_dir")
marker=.easy-db-lab-tool-wrappers

# The real tool is the first executable of this name on PATH that is neither in a directory of
# wrappers (one holding the marker, such as another workspace's bin/) nor this file itself.
real=
saved_ifs=$IFS
IFS=:
set -f
for dir in $PATH; do
    [ -n "$dir" ] || dir=.
    [ -f "$dir/$marker" ] && continue
    candidate=$dir/$tool
    if [ -f "$candidate" ] && [ -x "$candidate" ] && ! [ "$candidate" -ef "$0" ]; then
        real=$candidate
        break
    fi
done
set +f
IFS=$saved_ifs

if [ -z "$real" ]; then
    echo "easy-db-lab: $tool is not installed: no $tool found on PATH outside easy-db-lab's tool wrappers." >&2
    exit 127
fi

case $tool in
    kubectl | helm | cilium | k9s)
        kubeconfig=$workspace/kubeconfig
        if ! [ -f "$kubeconfig" ]; then
            echo "easy-db-lab: $kubeconfig does not exist, so $tool cannot reach the cluster of the workspace $workspace." >&2
            exit 1
        fi
        export KUBECONFIG="$kubeconfig"
        ;;
esac

# Only the env file decides; values inherited from the caller are ignored.
unset EDL_TAILSCALE_ACTIVE EDL_SOCKS_PORT EDL_SOCKS_PID
env_file=$workspace/.socks5-proxy.env
if [ -f "$env_file" ]; then
    . "$env_file"
fi

if [ "${EDL_TAILSCALE_ACTIVE:-}" = true ]; then
    exec "$real" "$@"
fi

# Fails with the pointer to socks start; $1 says why there is no tunnel to use, and $2, when set, where
# to read more.
no_tunnel() {
    echo "easy-db-lab: $1 for the workspace $workspace, so $tool cannot reach the cluster." >&2
    if [ -n "${2:-}" ]; then
        echo "easy-db-lab: $2" >&2
    fi
    echo "easy-db-lab: run 'easy-db-lab socks start' in $workspace, then try again." >&2
    exit 1
}

case ${EDL_SOCKS_PORT:-}:${EDL_SOCKS_PID:-} in
    :* | *: | *[!0-9:]*) no_tunnel "no SOCKS tunnel is recorded" ;;
esac
# The port is used only while the recorded tunnel script runs: once it has exited, nothing of ours
# listens there, and another process may have taken the port. kill -0 shows only that some process
# holds the PID, not that it is the tunnel: POSIX sh cannot read another process's arguments portably.
# That is enough here, because the CLI's check is the one that decides. Every CLI command that uses
# the tunnel first proves the recorded PID is the tunnel script, by its arguments, and replaces the
# tunnel when another program holds the PID.
kill -0 "$EDL_SOCKS_PID" 2>/dev/null ||
    no_tunnel "the recorded SOCKS tunnel (PID $EDL_SOCKS_PID) is not running" \
        "$workspace/logs/socks5-proxy.log says why it exited."

# The tunnel listens on 127.0.0.1 only; localhost could resolve to ::1, where another process may listen.
socks=socks5://127.0.0.1:$EDL_SOCKS_PORT
socks_h=socks5h://127.0.0.1:$EDL_SOCKS_PORT
local_hosts=localhost,127.0.0.1

# Replace, never add to, the caller's proxy settings: an inherited NO_PROXY would bypass the tunnel.
unset HTTP_PROXY http_proxy HTTPS_PROXY https_proxy ALL_PROXY all_proxy NO_PROXY no_proxy
case $tool in
    kubectl | helm | cilium | k9s)
        export HTTPS_PROXY="$socks" https_proxy="$socks"
        ;;
    curl)
        export ALL_PROXY="$socks_h" all_proxy="$socks_h"
        export NO_PROXY="$local_hosts" no_proxy="$local_hosts"
        ;;
    skopeo)
        export ALL_PROXY="$socks_h" all_proxy="$socks_h"
        export HTTP_PROXY="$socks_h" http_proxy="$socks_h"
        export HTTPS_PROXY="$socks_h" https_proxy="$socks_h"
        export NO_PROXY="$local_hosts" no_proxy="$local_hosts"
        ;;
    *)
        echo "easy-db-lab: $0 is not one of easy-db-lab's tool wrappers (kubectl, helm, cilium, curl, skopeo, k9s)." >&2
        exit 1
        ;;
esac

exec "$real" "$@"
