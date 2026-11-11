#!/bin/sh
# easy-db-lab SOCKS tunnel.
#
# easy-db-lab writes this script into <workspace>/bin/edl-socks-tunnel with the tool wrappers, and
# rewrites it when it changes, so do not edit the copy. The CLI starts it, detached, as
#
#   /bin/sh edl-socks-tunnel <port> <sshConfig> <backoff-seconds> <log> <host>
#
# through /bin/sh rather than as an executable: macOS scans the first exec of a rewritten executable,
# which can outlast the CLI's wait for the tunnel. It stays executable, so it can be run by hand.
#
# and records its PID. It runs `ssh -N -D 127.0.0.1:<port>` to <host>, waits for ssh to exit, and starts
# it again on the same port after <backoff-seconds>, for as long as it runs. The listener is on IPv4
# loopback only, and every client uses 127.0.0.1: a tunnel that also listened on ::1 could keep running
# with only that address bound while another process holds 127.0.0.1 on the port. The port never changes, so
# the port recorded in the proxy env file stays right across a reconnect. The keepalives in the
# generated sshConfig make ssh exit when its connection dies, which is what lets it reconnect.
#
# It starts ssh again only when <workspace>/.socks5-proxy.env records its own port as EDL_SOCKS_PORT.
# The CLI writes that port only after it has verified the tunnel end to end, so when the port is not
# recorded the tunnel never came up (the port was taken, the key was refused): the script exits with
# ssh's status, and the CLI reports the failure or retries on a new port. No timer decides this.
#
# An ssh that could not bind its port (another process took it during the backoff) ends the script
# too, after that one attempt, so it never loops on a port it cannot have. ssh reports the failure only
# in text, so the script appends all its output to <log>, notes the log's size before each ssh run, and
# looks for "cannot listen to port" in what that run wrote. ssh prints that only when no address of the
# forward could be bound; its "Address already in use" line names one address and does not mean the
# forward failed. The CLI matches the same message.
#
# TERM ends the running ssh and the loop, and logs that it did; nothing is started afterwards. INT is ignored, and so is HUP
# (the CLI starts the script under nohup): a Ctrl-C in the terminal of a later easy-db-lab command, or
# closing that terminal, must not end the tunnel every command of the workspace shares.
#
# Each line the script writes starts with a UTC timestamp. It must stay POSIX sh: it runs under dash
# on Linux and under /bin/sh on macOS.

if [ "$#" -ne 5 ]; then
    echo "usage: ${0##*/} <port> <sshConfig> <backoff-seconds> <log> <host>" >&2
    exit 2
fi
port=$1
ssh_config=$2
backoff=$3
log_file=$4
host=$5

exec 2>>"$log_file"

# CDPATH is cleared in the subshell only: with it set, cd could resolve a relative bin/ to another directory.
bin_dir=$(unset CDPATH; cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
env_file=$(dirname "$bin_dir")/.socks5-proxy.env

log() {
    printf '%s edl-socks-tunnel: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

child=
stopping=
interrupted=
status=0

stop() {
    stopping=1
    interrupted=1
    if [ -n "$child" ]; then
        kill "$child" 2>/dev/null
    fi
}
trap stop TERM
trap '' INT

# Runs "$@" in the background and waits until it has exited, also when TERM interrupts the wait;
# leaves its exit status in $status.
run_child() {
    "$@" &
    child=$!
    # A TERM that came before $child was set found no child to end.
    if [ -n "$stopping" ]; then
        kill "$child" 2>/dev/null
    fi
    # Only the trap sets $interrupted, so a wait that returned because the child exited ends the loop.
    # A status cannot tell the two apart, and kill -0 could match a new process that reused the PID.
    # A later pass runs only after a trap interrupted the wait, and there 127 means the child was
    # already reaped; on the first pass 127 is the child's own status. wait's own stderr is dropped:
    # dash reports a child ended by a signal there ("Terminated: 15"), a log line with no timestamp.
    first_wait=1
    while :; do
        interrupted=
        wait "$child" 2>/dev/null
        code=$?
        [ "$code" -eq 127 ] && [ -z "$first_wait" ] && break
        first_wait=
        status=$code
        [ -n "$interrupted" ] || break
    done
    child=
}

# Whether the env file records this tunnel's port, which the CLI writes only for a verified tunnel.
port_is_recorded() {
    recorded=$(
        unset EDL_SOCKS_PORT
        if [ -f "$env_file" ]; then
            . "$env_file"
        fi
        printf '%s' "${EDL_SOCKS_PORT:-}"
    )
    [ "$recorded" = "$port" ]
}

log_size() {
    wc -c <"$log_file" | tr -d ' '
}

# Whether the log, after its first $1 bytes, holds the errors of an ssh that could not bind its port.
bind_failed_after() {
    tail -c +"$(($1 + 1))" "$log_file" | grep -q -e 'cannot listen to port'
}

while [ -z "$stopping" ]; do
    before=$(log_size)
    log "starting ssh on port $port"
    run_child ssh -v -o ExitOnForwardFailure=yes -N -D "127.0.0.1:$port" -F "$ssh_config" "$host"
    [ -n "$stopping" ] && break
    if ! port_is_recorded; then
        log "ssh exited with status $status before the tunnel on port $port was verified; not restarting."
        exit "$status"
    fi
    if bind_failed_after "$before"; then
        log "ssh exited with status $status because port $port is taken by another process; not restarting."
        exit "$status"
    fi
    log "ssh exited with status $status; starting it again on port $port in ${backoff}s."
    run_child sleep "$backoff"
done
log "stopped by TERM; ssh on port $port has ended."
exit 0
