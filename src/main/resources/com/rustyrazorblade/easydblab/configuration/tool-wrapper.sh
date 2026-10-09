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
#   - with no port recorded it fails and says to run `easy-db-lab start-socks`.
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

# Only the env file decides; values inherited from the caller are ignored.
unset EDL_TAILSCALE_ACTIVE EDL_SOCKS_PORT
env_file=$workspace/.socks5-proxy.env
if [ -f "$env_file" ]; then
    . "$env_file"
fi

if [ "${EDL_TAILSCALE_ACTIVE:-}" = true ]; then
    exec "$real" "$@"
fi

case ${EDL_SOCKS_PORT:-} in
    '' | *[!0-9]*)
        echo "easy-db-lab: no SOCKS tunnel is recorded for the workspace $workspace, so $tool cannot reach the cluster." >&2
        echo "easy-db-lab: run 'easy-db-lab start-socks' in $workspace, then try again." >&2
        exit 1
        ;;
esac

socks=socks5://localhost:$EDL_SOCKS_PORT
socks_h=socks5h://localhost:$EDL_SOCKS_PORT
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
