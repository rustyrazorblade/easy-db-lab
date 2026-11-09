#!/bin/sh
#
# The account compactor's gate: runs one compactor binary only while this pod holds the store lease.
#
# Usage: busybox sh compactor-gate.sh -- <binary> [args...]
#
# The lease container writes the time until which the lease is valid, in epoch seconds, to
# GATE_VALID_UNTIL, and removes the file when it loses the lease. This gate:
#   1. waits until that time is later than now;
#   2. starts the binary as its child;
#   3. reads the file every LEASE_GATE_POLL_SECONDS. When the time is not later than now, or the file
#      is gone, it sends SIGTERM, waits LEASE_GATE_GRACE_SECONDS, sends SIGKILL, and goes back to 1;
#   4. starts the binary again when it exits while the lease is valid;
#   5. on SIGTERM (the pod stopping) passes it to the binary, waits for it, and exits 0. The gate is
#      PID 1 in its container, and the kernel ignores SIGTERM to PID 1 without a handler.
#
# Because the time only moves while the agent renews, a dead or hung agent stops the binary too.
#
# The compactor images are distroless: no applet is on PATH. Only shell builtins run bare; date and
# sleep run as explicit busybox applets ($BB, copied into the pod by the init container).

set -u

BB="${GATE_BUSYBOX:-/gate/bin/busybox}"
VALID_UNTIL="${GATE_VALID_UNTIL:-/lease/valid-until}"

if [ -z "${LEASE_GATE_POLL_SECONDS:-}" ] || [ -z "${LEASE_GATE_GRACE_SECONDS:-}" ]; then
  echo "compactor-gate: LEASE_GATE_POLL_SECONDS and LEASE_GATE_GRACE_SECONDS must be set" >&2
  exit 2
fi
POLL="${LEASE_GATE_POLL_SECONDS}"
GRACE="${LEASE_GATE_GRACE_SECONDS}"

if [ "${1:-}" = "--" ]; then
  shift
fi
if [ "$#" = 0 ]; then
  echo "compactor-gate: no binary to run" >&2
  exit 2
fi
NAME="$1"

child=""
stopping=0
# The last file content that did not parse, so it is logged once, not on every poll.
unparsed=""

log() {
  echo "compactor-gate [${NAME}]: $*"
}

now() {
  "${BB}" date +%s
}

# True when the file holds a whole number of seconds later than now. Read with the builtin `read`,
# so a half-written or garbage file is simply not valid.
lease_valid() {
  [ -f "${VALID_UNTIL}" ] || return 1
  until_s=""
  read -r until_s < "${VALID_UNTIL}" 2>/dev/null || [ -n "${until_s}" ] || return 1
  case "${until_s}" in
    '' | *[!0-9]*)
      if [ "${until_s}" != "${unparsed}" ]; then
        log "${VALID_UNTIL} does not hold a whole number of seconds; the lease is not valid"
        unparsed="${until_s}"
      fi
      return 1
      ;;
  esac
  unparsed=""
  [ "${until_s}" -gt "$(now)" ]
}

# True while the child runs. `kill -0` alone also answers for a zombie, so ask the shell's job
# table first: `jobs` reaps a child that has exited.
child_running() {
  [ -n "${child}" ] || return 1
  jobs > /dev/null 2>&1
  kill -0 "${child}" 2>/dev/null
}

# Sleeps $1 seconds in the background and waits for it, so a SIGTERM to the gate interrupts it.
pause() {
  "${BB}" sleep "$1" &
  sleeper=$!
  wait "${sleeper}" 2>/dev/null
  kill "${sleeper}" 2>/dev/null
  sleeper=""
}

# Stops the child: SIGTERM, up to the grace for it to exit, then SIGKILL. The grace is measured on
# the clock, not counted in sleeps: a starved gate's `sleep 1` can take longer, and the lease's
# margin assumes SIGKILL comes the grace after SIGTERM.
stop_child() {
  [ -n "${child}" ] || return 0
  kill -TERM "${child}" 2>/dev/null
  term_at="$(now)"
  while child_running && [ $(($(now) - term_at)) -lt "${GRACE}" ]; do
    pause 1
  done
  if child_running; then
    log "still running ${GRACE}s after SIGTERM; sending SIGKILL"
    kill -KILL "${child}" 2>/dev/null
  fi
  wait "${child}" 2>/dev/null
  child=""
}

on_term() {
  stopping=1
  log "SIGTERM: stopping the compactor"
  stop_child
  exit 0
}
trap on_term TERM INT

sleeper=""
waiting=0
while [ "${stopping}" = 0 ]; do
  if ! lease_valid; then
    if [ "${waiting}" = 0 ]; then
      log "waiting for the lease: ${VALID_UNTIL} is missing or not later than now"
      waiting=1
    fi
    pause "${POLL}"
    continue
  fi
  waiting=0
  "$@" &
  child=$!
  log "lease valid until ${until_s}; started pid ${child}"
  while child_running; do
    if ! lease_valid; then
      log "lease not valid; stopping the compactor"
      stop_child
      break
    fi
    pause "${POLL}"
  done
  if [ -n "${child}" ]; then
    # Some shells (dash, ash) already reaped the child in `jobs`, and then `wait` answers 127.
    wait "${child}" 2>/dev/null
    status=$?
    case "${status}" in
      127) log "the compactor exited; starting it again while the lease is valid" ;;
      *) log "the compactor exited with status ${status}; starting it again while the lease is valid" ;;
    esac
    child=""
    pause "${POLL}"
  fi
done
