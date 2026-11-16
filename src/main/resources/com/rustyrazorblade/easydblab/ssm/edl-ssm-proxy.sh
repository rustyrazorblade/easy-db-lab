#!/bin/sh
# easy-db-lab SSM ProxyCommand wrapper. Usage: edl-ssm-proxy <command> [args...]
#
# Runs <command> (the `aws ssm start-session` ProxyCommand) with ssh's stdin and stdout, and ends
# its whole process tree when ssh is done with it. The AWS CLI does not pass a termination on to
# the session-manager-plugin it starts, and a plugin whose WebSocket is stuck does not notice that
# its stdin closed, so without this both outlive ssh, re-parented to PID 1. The tree is ended when:
#   - ssh closes the connection (our stdin reaches EOF), after a short grace for a clean exit;
#   - ssh exits or is killed (we are re-parented, or ssh's SIGHUP reaches us);
#   - we are sent SIGTERM or SIGINT.
# POSIX sh only: it runs under dash on Linux and bash's sh mode on macOS.

grace_seconds=${EDL_SSM_PROXY_GRACE_SECONDS:-2}
ssh_pid=$PPID

work=$(mktemp -d "${TMPDIR:-/tmp}/edl-ssm-proxy.XXXXXX") || exit 1
fifo="$work/stdin"
mkfifo "$fifo" || { rmdir "$work"; exit 1; }

# A background job's stdin is /dev/null unless redirected from a saved copy, so keep one on fd 3.
exec 3<&0

"$@" <"$fifo" 3<&- &
child=$!
cat <&3 >"$fifo" 3<&- &
feeder=$!

# Only the child and the feeder hold ssh's pipes from here on, so ssh sees EOF as soon as they end.
exec 0</dev/null 1>/dev/null 3<&-

descendants() {
    for kid in $(pgrep -P "$1" 2>/dev/null); do
        descendants "$kid"
        echo "$kid"
    done
}

end_tree() {
    pids="$(descendants "$child") $child"
    kill -TERM $pids 2>/dev/null
    waited=0
    while [ "$waited" -lt "$grace_seconds" ]; do
        alive=""
        for pid in $pids; do kill -0 "$pid" 2>/dev/null && alive="$alive $pid"; done
        [ -z "$alive" ] && break
        sleep 1
        waited=$((waited + 1))
    done
    kill -KILL $pids 2>/dev/null
    kill -TERM "$feeder" 2>/dev/null
    rm -rf "$work"
}

trap 'end_tree; exit 129' HUP
trap 'end_tree; exit 143' TERM
trap 'end_tree; exit 130' INT

eof_at=""
while kill -0 "$child" 2>/dev/null; do
    if ! kill -0 "$ssh_pid" 2>/dev/null || [ "$(ps -o ppid= -p $$ | tr -d ' ')" != "$ssh_pid" ]; then
        end_tree
        exit 1
    fi
    if [ -z "$eof_at" ] && ! kill -0 "$feeder" 2>/dev/null; then
        eof_at=0
    fi
    if [ -n "$eof_at" ]; then
        if [ "$eof_at" -ge "$grace_seconds" ]; then
            end_tree
            exit 0
        fi
        eof_at=$((eof_at + 1))
    fi
    sleep 1
done

wait "$child"
status=$?
kill -TERM "$feeder" 2>/dev/null
rm -rf "$work"
exit "$status"
