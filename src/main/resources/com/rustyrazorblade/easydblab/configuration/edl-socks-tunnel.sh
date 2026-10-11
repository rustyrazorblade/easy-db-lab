#!/bin/sh
# easy-db-lab SOCKS tunnel.
#
# easy-db-lab writes this script into <workspace>/bin/edl-socks-tunnel with the tool wrappers, and
# rewrites it when it changes, so do not edit the copy. The CLI starts it, detached, as
#
#   edl-socks-tunnel <port> <sshConfig> <backoff-seconds> <startup-grace-seconds> <host>
#
# and records its PID. It runs `ssh -N -D <port>` to <host>, waits for ssh to exit, and starts it
# again on the same port after <backoff-seconds>, for as long as it runs. The port never changes, so
# the port recorded in the proxy env file stays right across a reconnect. The keepalives in the
# generated sshConfig make ssh exit when its connection dies, which is what lets it reconnect.
#
# If the first ssh exits within <startup-grace-seconds>, the tunnel never came up (the port was
# taken, the key was refused), so the script exits with ssh's status and the CLI reports the failure.
#
# USR1 asks for a restart: the script ends its ssh and starts the next one at once, with no backoff
# and no start-up grace exit. The CLI sends it when it rewrites sshConfig, so the new file is read now.
#
# TERM or INT ends the running ssh and the loop; nothing is started afterwards. HUP is left alone:
# the CLI starts the script under nohup, so the tunnel survives the terminal that started it.
#
# It must stay POSIX sh: it runs under dash on Linux and under /bin/sh on macOS.

if [ "$#" -ne 5 ]; then
    echo "usage: ${0##*/} <port> <sshConfig> <backoff-seconds> <startup-grace-seconds> <host>" >&2
    exit 2
fi
port=$1
ssh_config=$2
backoff=$3
grace=$4
host=$5

child=
stopping=
restarting=
status=0

end_child() {
    if [ -n "$child" ]; then
        kill "$child" 2>/dev/null
    fi
}
stop() {
    stopping=1
    end_child
}
restart() {
    restarting=1
    end_child
}
trap stop TERM INT
trap restart USR1

# Runs "$@" in the background and waits until it has exited, also when a trapped signal interrupts
# the wait; leaves its exit status in $status.
run_child() {
    "$@" &
    child=$!
    # A signal that came before $child was set found no child to end.
    if [ -n "$stopping" ] || [ -n "$restarting" ]; then
        kill "$child" 2>/dev/null
    fi
    while :; do
        wait "$child"
        status=$?
        kill -0 "$child" 2>/dev/null || break
    done
    child=
}

first=1
while [ -z "$stopping" ]; do
    # A restart asked for during the backoff ended the sleep; this ssh is the one it asked for.
    restarting=
    started=$(date +%s)
    run_child ssh -v -o ExitOnForwardFailure=yes -N -D "$port" -F "$ssh_config" "$host"
    [ -n "$stopping" ] && break
    if [ -n "$restarting" ]; then
        echo "edl-socks-tunnel: restarting ssh on port $port, as asked." >&2
        continue
    fi
    if [ -n "$first" ] && [ $(($(date +%s) - started)) -lt "$grace" ]; then
        echo "edl-socks-tunnel: ssh exited with status $status before the tunnel on port $port was up; not restarting." >&2
        exit "$status"
    fi
    first=
    echo "edl-socks-tunnel: ssh exited with status $status; starting it again on port $port in ${backoff}s." >&2
    run_child sleep "$backoff"
done
exit 0
