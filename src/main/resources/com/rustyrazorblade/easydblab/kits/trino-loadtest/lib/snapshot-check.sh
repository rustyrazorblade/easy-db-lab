# shellcheck shell=bash
#
# The snapshot-leak check (D12 hygiene, pmcfadin/cqlite#2399): after a run, no `cqlite-` snapshot
# may remain on any db node. A snapshot-mode read takes a per-query Cassandra snapshot and must
# remove it; one left behind is a leak.
#
# The check runs on the operator's machine, not in the driver pod, because that is where the
# cluster is reachable: kit processes have <workspace>/bin first on PATH, so `ssh db0 nodetool
# listsnapshots` goes through the workspace ssh wrapper. The command must print the raw
# `nodetool listsnapshots` listing of every node; this file does the `cqlite-` matching. It runs
# under bash 3.2 and needs the shared kit library.

TL_SNAPSHOT_PREFIX="cqlite-"
TL_SNAPSHOT_DEFAULT_TIMEOUT_SECONDS=60

# Fails unless the check command $1 is not blank and the timeout $2 is a whole number of seconds
# above 0.
tl_validate_snapshot_check() {
  [ -n "${1//[[:space:]]/}" ] || edl_fail "--snapshot-check-cmd must not be blank"
  if ! [[ "$2" =~ ^[0-9]+$ ]] || [ "$2" -eq 0 ]; then
    edl_fail "--snapshot-check-timeout-s must be a whole number of seconds above 0 (got '$2')"
  fi
}

# Prints, one per line, the snapshot names in the listing on stdin that start with cqlite-.
tl_leaked_snapshots() {
  awk -v prefix="${TL_SNAPSHOT_PREFIX}" 'index($1, prefix) == 1 { print $1 }'
}

# Runs the check command $1 with `bash -c`, allowing it $2 seconds, and prints its stdout. Fails
# when the command exits non-zero or runs out of time. The command runs in its own process group,
# and a timeout kills the whole group, so no `ssh` or `nodetool` it started is left running.
tl_snapshot_listing() {
  local cmd="$1" timeout_s="$2" dir pid watchdog rc=0 detail
  dir="$(mktemp -d)"
  set -m
  bash -c "${cmd}" > "${dir}/out" 2> "${dir}/err" < /dev/null &
  pid=$!
  # /bin/sleep, not sleep: the wait is real time even where sleep is replaced.
  (/bin/sleep "${timeout_s}" && touch "${dir}/timed-out" && kill -KILL -- "-${pid}") > /dev/null 2>&1 &
  watchdog=$!
  set +m
  wait "${pid}" || rc=$?
  kill -KILL -- "-${watchdog}" > /dev/null 2>&1 || true
  if [ -e "${dir}/timed-out" ]; then
    rm -rf "${dir}"
    edl_fail "snapshot-leak check: FAIL - the check command did not finish within ${timeout_s}s"
  fi
  if [ "${rc}" -ne 0 ]; then
    detail="$(cat "${dir}/err")"
    rm -rf "${dir}"
    edl_fail "snapshot-leak check: FAIL - the check command exited ${rc}: ${detail:-(no stderr)}"
  fi
  cat "${dir}/out"
  rm -rf "${dir}"
}

# Runs the check command $1 with timeout $2, prints PASS when no cqlite- snapshot remains, and
# fails naming every leaked snapshot otherwise. A listing with no visible character fails too:
# `nodetool listsnapshots` always prints a header or "There are no snapshots", so an empty one
# means the command listed nothing (a loop over no hosts, a typo), not a clean cluster.
tl_run_snapshot_check() {
  local listing leaked count
  listing="$(tl_snapshot_listing "$1" "$2")" || exit 1
  [ -n "${listing//[[:space:]]/}" ] ||
    edl_fail "snapshot-leak check: FAIL - the check command printed nothing; it must print the nodetool listsnapshots output of every db node"
  leaked="$(printf '%s\n' "${listing}" | tl_leaked_snapshots)"
  if [ -n "${leaked}" ]; then
    count="$(printf '%s\n' "${leaked}" | wc -l | tr -d ' ')"
    edl_fail "snapshot-leak check: FAIL - ${count} leaked snapshot(s): $(printf '%s\n' "${leaked}" | paste -sd ',' - | sed 's/,/, /g')"
  fi
  echo "snapshot-leak check: PASS (0 ${TL_SNAPSHOT_PREFIX} snapshots remain)"
}
