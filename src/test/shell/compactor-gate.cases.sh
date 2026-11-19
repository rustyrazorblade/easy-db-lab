#!/bin/sh
#
# The account compactor gate's cases, in POSIX sh, for configuration/compactor/compactor-gate.sh.
# compactor-gate.test.sh runs them under every local POSIX shell; compactor-gate-busybox.test.sh
# runs them under the static busybox sh the pod runs the gate with.
#
# Environment:
# - GATE_SCRIPT: the gate script.
# - GATE_SHELL: the absolute shell command the gate runs under, for example "/bin/busybox sh".
# - REAL_BUSYBOX: optional. A real busybox for the real-clock case; without it, that case runs
#   the system's date and sleep through a stub.
#
# The gate runs with PATH pointing nowhere and GATE_BUSYBOX at a stub, so any applet it calls
# without the explicit busybox path fails, as it would in the distroless compactor images.
#
# Every case but one runs the gate on a virtual clock. The stub's `date +%s` reads the clock file,
# and its `sleep N` returns only when the test moves the clock N seconds on. The test moves the
# clock only while the gate waits in such a sleep, then waits until the gate sleeps again. So each
# gate decision is made, and logged, before the next check, on any machine load, and the cases
# assert time in the gate's own seconds. A wait in real time only waits for something that must
# happen (a process start, an exit), returns as soon as it does, and fails only after GUARD
# seconds, which catches a hang, not a slow machine. The real-clock case checks the real applets
# and asserts no time at all.

set -u

GATE="${GATE_SCRIPT:?GATE_SCRIPT must name compactor-gate.sh}"
GATE_SHELL="${GATE_SHELL:?GATE_SHELL must name the shell the gate runs under}"
REAL_BUSYBOX="${REAL_BUSYBOX:-}"
GUARD="${GATE_TEST_GUARD_SECONDS:-300}"
DRIVER_PATH="${PATH}"
REAL_SLEEP="$(command -v sleep)"
REAL_DATE="$(command -v date)"

run=0
failed=0
WORK=""
GATE_PID=""

kill_all() {
  [ -n "${GATE_PID}" ] && { kill -KILL "${GATE_PID}" 2>/dev/null; wait "${GATE_PID}" 2>/dev/null; }
  GATE_PID=""
  [ -n "${WORK}" ] || return 0
  [ -f "${WORK}/pid" ] && kill -KILL "$(cat "${WORK}/pid")" 2>/dev/null
  [ -f "${WORK}/uptime-writer.pid" ] && { kill -KILL "$(cat "${WORK}/uptime-writer.pid")" 2>/dev/null; wait "$(cat "${WORK}/uptime-writer.pid")" 2>/dev/null; }
  for marker in "${WORK}"/sleepers/*; do
    [ -f "${marker}" ] && kill -KILL "${marker##*/}" 2>/dev/null
  done
  return 0
}

cleanup() {
  kill_all
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK=""
  return 0
}
trap cleanup EXIT

report() {
  echo "FAIL [${GATE_SHELL}] ${current}: $1"
  sed 's/^/    gate: /' "${WORK}/gate.log" 2>/dev/null
  sed 's/^/    compactor: /' "${WORK}/events" 2>/dev/null
  echo "    clock: $(clock 2>/dev/null)"
}

# Passes when the condition $1 holds now; otherwise reports $2.
check() {
  eval "$1" && return 0
  report "$2"
  return 1
}

# Waits in real time for the condition $1, which must come true; reports $2 after GUARD seconds.
await() {
  deadline=$(($("${REAL_DATE}" +%s) + GUARD))
  until eval "$1"; do
    if [ "$("${REAL_DATE}" +%s)" -ge "${deadline}" ]; then
      report "$2 (not true after ${GUARD}s)"
      return 1
    fi
    "${REAL_SLEEP}" 0.05
  done
}

setup() {
  cleanup
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/sleepers"
  : > "${WORK}/events"
  set_clock 1800000000
  set_uptime 5000
  # The virtual-clock busybox: the two applets the gate may call, and nothing else. `date` reads
  # the wall clock; `sleep` waits on the uptime, which a wall-clock step does not move.
  cat > "${WORK}/stubs/busybox" <<EOF
#!/bin/sh
PATH='${DRIVER_PATH}'
up() { read -r u _ < "${WORK}/uptime"; echo "\${u%%.*}"; }
case "\$1" in
  date)
    [ "\$#" = 2 ] && [ "\$2" = +%s ] || { echo "busybox stub: unexpected date \$*" >&2; exit 127; }
    exec cat "${WORK}/clock" ;;
  sleep)
    target=\$((\$(up) + \$2))
    echo "\${target}" > "${WORK}/sleepers/.\$\$" && mv "${WORK}/sleepers/.\$\$" "${WORK}/sleepers/\$\$"
    while [ "\$(up)" -lt "\${target}" ]; do "${REAL_SLEEP}" 0.05; done
    rm -f "${WORK}/sleepers/\$\$"
    exit 0 ;;
esac
echo "busybox stub: unexpected applet \$1" >&2
exit 127
EOF
  # The real-clock busybox, for when no real busybox is given.
  cat > "${WORK}/stubs/busybox-real" <<EOF
#!/bin/sh
case "\$1" in
  date) shift; exec "${REAL_DATE}" "\$@" ;;
  sleep) shift; exec "${REAL_SLEEP}" "\$@" ;;
esac
echo "busybox stub: unexpected applet \$1" >&2
exit 127
EOF
  # The fake compactor: records its pid, its start and each SIGTERM. The trap comes first, so a
  # SIGTERM after the recorded start is always recorded. FAKE_IGNORE_TERM=1 records SIGTERM and
  # keeps running; FAKE_EXIT_ON_REQUEST=1 exits 3 when the test creates the exit-now file.
  cat > "${WORK}/stubs/compactor" <<EOF
#!/bin/sh
PATH='${DRIVER_PATH}'
if [ "\${FAKE_IGNORE_TERM:-0}" = 1 ]; then
  trap 'echo term >> "${WORK}/events"' TERM
else
  trap 'echo term >> "${WORK}/events"; exit 0' TERM
fi
echo "\$\$" > "${WORK}/pid"
echo "start \$*" >> "${WORK}/events"
while :; do
  if [ "\${FAKE_EXIT_ON_REQUEST:-0}" = 1 ] && [ -f "${WORK}/exit-now" ]; then
    rm -f "${WORK}/exit-now"
    echo exit >> "${WORK}/events"
    exit 3
  fi
  "${REAL_SLEEP}" 0.1 &
  wait \$!
done
EOF
  chmod +x "${WORK}/stubs/busybox" "${WORK}/stubs/busybox-real" "${WORK}/stubs/compactor"
}

clock() { cat "${WORK}/clock"; }

set_clock() {
  echo "$1" > "${WORK}/clock.tmp" && mv "${WORK}/clock.tmp" "${WORK}/clock"
}

# The virtual /proc/uptime: seconds since boot and idle seconds, as the kernel writes them. It only
# moves forward; advance moves it with the wall clock, step_wall_back leaves it.
set_uptime() {
  echo "$1.37 4242.00" > "${WORK}/uptime.tmp" && mv "${WORK}/uptime.tmp" "${WORK}/uptime"
}

uptime_s() {
  read -r up _ < "${WORK}/uptime"
  echo "${up%%.*}"
}

# Steps the wall clock $1 seconds back in one jump, as NTP can, while the uptime stays.
step_wall_back() {
  set_clock $(($(clock) - $1))
}

# True while the gate waits in a stub sleep the uptime has not reached: it has acted on the
# current time and does nothing more until the clock moves.
asleep() {
  now_v="$(uptime_s)"
  for marker in "${WORK}"/sleepers/*; do
    [ -f "${marker}" ] || continue
    target="$(cat "${marker}" 2>/dev/null)"
    [ -n "${target}" ] || continue
    [ "${target}" -gt "${now_v}" ] && kill -0 "${marker##*/}" 2>/dev/null && return 0
  done
  return 1
}

# Moves the wall clock and the uptime $1 seconds on in one jump, once the gate sleeps, then waits
# until it sleeps again.
advance() {
  await asleep "the gate waits on its clock" || return 1
  set_clock $(($(clock) + $1))
  set_uptime $(($(uptime_s) + $1))
  await asleep "the gate acted on the new time and waits on its clock again"
}

grant() { echo "$(($(clock) + $1))" > "${WORK}/valid-until"; }

starts() { grep -c '^start' "${WORK}/events" 2>/dev/null || true; }

logged() { grep -c "$1" "${WORK}/gate.log" 2>/dev/null || true; }

compactor_pid() { cat "${WORK}/pid"; }

# True when process $1 has exited: gone, or a zombie its parent has not reaped yet.
exited() {
  if [ -r "/proc/$1/stat" ]; then
    [ "$(cut -d' ' -f3 < "/proc/$1/stat")" = Z ]
    return
  fi
  kill -0 "$1" 2>/dev/null || return 0
  case "$(ps -o stat= -p "$1" 2>/dev/null)" in
    *Z*) return 0 ;;
  esac
  return 1
}

no_applet_missing() { [ "$(logged 'not found')" = 0 ] && [ "$(logged 'unexpected')" = 0 ]; }

# Starts the gate in the background with grace $1 seconds, on the busybox $2; the rest are
# VAR=value settings for the fake compactor.
start_gate_with() {
  grace="$1"
  busybox="$2"
  uptime_file="$3"
  shift 3
  # shellcheck disable=SC2086 # GATE_SHELL may be "/bin/busybox sh".
  env PATH=/nonexistent GATE_BUSYBOX="${busybox}" GATE_VALID_UNTIL="${WORK}/valid-until" GATE_UPTIME="${uptime_file}" \
    LEASE_GATE_POLL_SECONDS=1 LEASE_GATE_GRACE_SECONDS="${grace}" "$@" \
    ${GATE_SHELL} "${GATE}" -- "${WORK}/stubs/compactor" -target=compactor > "${WORK}/gate.log" 2>&1 &
  GATE_PID=$!
}

start_gate() {
  grace="$1"
  shift
  start_gate_with "${grace}" "${WORK}/stubs/busybox" "${WORK}/uptime" "$@"
}

# Grants a lease, starts the gate, and waits until the compactor has recorded its start.
start_compacting() {
  grace="$1"
  shift
  grant 100
  start_gate "${grace}" "$@"
  await '[ "$(starts)" = 1 ]' "the compactor started under a valid lease" || return 1
  await asleep "the gate waits on its clock"
}

case_waits_while_not_valid() {
  start_gate 1
  await asleep "the gate waits on its clock" || return 1
  check '[ "$(logged "waiting for the lease")" = 1 ]' "the gate did not say once that it waits for the lease" || return 1
  echo "$(($(clock) - 10))" > "${WORK}/valid-until"
  advance 1 || return 1
  echo "not-a-time" > "${WORK}/valid-until"
  advance 1 || return 1
  advance 1 || return 1
  advance 1 || return 1
  check '[ "$(logged started)" = 0 ]' "the compactor started while the lease was not valid" || return 1
  check '[ "$(logged "waiting for the lease")" = 1 ]' "the gate said more than once that it waits for the lease" || return 1
  check '[ "$(logged "${WORK}/valid-until does not hold a whole number")" = 1 ]' \
    "the gate did not say once, with the path, that the file does not parse" || return 1
  check 'kill -0 "${GATE_PID}" 2>/dev/null' "the gate exited while waiting for the lease" || return 1
  check no_applet_missing "the gate called an applet that is not on PATH"
}

case_starts_when_valid() {
  start_gate 1
  await asleep "the gate waits on its clock" || return 1
  grant 100
  advance 1 || return 1
  check '[ "$(logged "started pid")" = 1 ]' "the gate did not start the compactor one poll after the lease became valid" || return 1
  await '[ "$(starts)" = 1 ]' "the compactor recorded its start" || return 1
  check 'grep -q "^start -target=compactor$" "${WORK}/events"' "the compactor did not get its arguments" || return 1
  check no_applet_missing "the gate called an applet that is not on PATH"
}

case_stops_when_time_passes_then_starts_again() {
  start_compacting 1 || return 1
  grant 1
  advance 1 || return 1
  check '[ "$(logged "lease not valid; stopping")" = 1 ]' "the gate did not stop the compactor on the poll the time passed" || return 1
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got SIGTERM" || return 1
  pid="$(compactor_pid)"
  await 'exited "${pid}"' "the compactor exited on SIGTERM" || return 1
  advance 1 || return 1
  check '[ "$(logged SIGKILL)" = 0 ]' "the gate sent SIGKILL to a compactor that stopped on SIGTERM" || return 1
  check '[ "$(logged "waiting for the lease")" = 1 ]' "the gate did not say it waits for the lease again" || return 1
  check 'kill -0 "${GATE_PID}" 2>/dev/null' "the gate exited instead of waiting for the lease again" || return 1
  grant 100
  advance 1 || return 1
  check '[ "$(logged "started pid")" = 2 ]' "the gate did not start the compactor again when the lease was valid again" || return 1
  await '[ "$(starts)" = 2 ]' "the compactor recorded its second start"
}

case_stops_when_file_goes() {
  start_compacting 1 || return 1
  rm -f "${WORK}/valid-until"
  advance 1 || return 1
  check '[ "$(logged "lease not valid; stopping")" = 1 ]' "the gate did not stop the compactor on the poll the file went" || return 1
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got SIGTERM after the file went"
}

case_kills_after_the_grace() {
  start_compacting 2 FAKE_IGNORE_TERM=1 || return 1
  rm -f "${WORK}/valid-until"
  advance 1 || return 1
  check '[ "$(logged "lease not valid; stopping")" = 1 ]' "the gate did not stop the compactor" || return 1
  term_at="$(clock)"
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got SIGTERM" || return 1
  pid="$(compactor_pid)"
  advance 1 || return 1
  check '[ "$(logged SIGKILL)" = 0 ]' "the gate sent SIGKILL 1s after SIGTERM, before the 2s grace" || return 1
  check '! exited "${pid}"' "the compactor was killed before the grace passed" || return 1
  advance 1 || return 1
  check '[ "$(logged SIGKILL)" = 1 ]' "the gate did not send SIGKILL when the grace passed" || return 1
  check '[ $(($(clock) - term_at)) = 2 ]' "the SIGKILL came $(($(clock) - term_at))s after SIGTERM, not 2s" || return 1
  await 'exited "${pid}"' "the compactor died of the SIGKILL"
}

# A gate that is starved of CPU sleeps longer than it asked. It must still send SIGKILL once the
# grace has passed on its clock, not after as many sleeps as the grace has seconds, and never before.
case_kills_on_the_clock_when_starved() {
  start_compacting 3 FAKE_IGNORE_TERM=1 || return 1
  rm -f "${WORK}/valid-until"
  advance 1 || return 1
  check '[ "$(logged "lease not valid; stopping")" = 1 ]' "the gate did not stop the compactor" || return 1
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got SIGTERM" || return 1
  pid="$(compactor_pid)"
  # One sleep of 1s that took 2s: 2s on the clock is short of the 3s grace.
  advance 2 || return 1
  check '[ "$(logged SIGKILL)" = 0 ]' "the gate sent SIGKILL before the grace had passed on its clock" || return 1
  check '! exited "${pid}"' "the compactor was killed before the grace passed" || return 1
  # A second slow sleep, 4s on the clock: past the grace, though the gate slept only twice.
  advance 2 || return 1
  check '[ "$(logged SIGKILL)" = 1 ]' "the gate counted its sleeps, not its clock: no SIGKILL 4s after SIGTERM with a 3s grace" || return 1
  await 'exited "${pid}"' "the compactor died of the SIGKILL"
}

# A backward wall-clock step (NTP) during the grace must not delay the SIGKILL: the grace is
# measured on the uptime, which never goes back.
case_kills_on_time_when_the_wall_clock_steps_back() {
  start_compacting 2 FAKE_IGNORE_TERM=1 || return 1
  rm -f "${WORK}/valid-until"
  advance 1 || return 1
  check '[ "$(logged "lease not valid; stopping")" = 1 ]' "the gate did not stop the compactor" || return 1
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got SIGTERM" || return 1
  pid="$(compactor_pid)"
  step_wall_back 3600
  advance 1 || return 1
  check '[ "$(logged SIGKILL)" = 0 ]' "the gate sent SIGKILL 1s after SIGTERM, before the 2s grace" || return 1
  advance 1 || return 1
  check '[ "$(logged SIGKILL)" = 1 ]' "the wall clock went back an hour and the gate did not send SIGKILL 2s after SIGTERM" || return 1
  await 'exited "${pid}"' "the compactor died of the SIGKILL"
}

case_restarts_after_exit_while_valid() {
  start_compacting 1 FAKE_EXIT_ON_REQUEST=1 || return 1
  pid="$(compactor_pid)"
  : > "${WORK}/exit-now"
  await 'exited "${pid}"' "the compactor exited on request" || return 1
  advance 1 || return 1
  check '[ "$(logged "the compactor exited")" = 1 ]' "the gate did not report the exit on the next poll" || return 1
  check '[ "$(logged "started pid")" = 1 ]' "the gate started the compactor again without waiting a poll" || return 1
  advance 1 || return 1
  check '[ "$(logged "started pid")" = 2 ]' "the gate did not start the compactor again one poll after it exited" || return 1
  await '[ "$(starts)" = 2 ]' "the compactor recorded its second start"
}

case_passes_sigterm_on() {
  start_compacting 1 || return 1
  kill -TERM "${GATE_PID}"
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got the gate's SIGTERM" || return 1
  # The gate may wait one sleep for its compactor to exit; move the clock while it does.
  deadline=$(($("${REAL_DATE}" +%s) + GUARD))
  until exited "${GATE_PID}"; do
    if [ "$("${REAL_DATE}" +%s)" -ge "${deadline}" ]; then
      report "the gate did not exit after its compactor stopped (not true after ${GUARD}s)"
      return 1
    fi
    asleep && { set_clock $(($(clock) + 1)); set_uptime $(($(uptime_s) + 1)); }
    "${REAL_SLEEP}" 0.05
  done
  wait "${GATE_PID}"
  status=$?
  GATE_PID=""
  check '[ "${status}" = 0 ]' "the gate exited ${status} on SIGTERM, not 0"
}

case_missing_setting_fails() {
  # shellcheck disable=SC2086
  env PATH=/nonexistent GATE_BUSYBOX="${WORK}/stubs/busybox" GATE_VALID_UNTIL="${WORK}/valid-until" \
    LEASE_GATE_POLL_SECONDS=1 ${GATE_SHELL} "${GATE}" -- "${WORK}/stubs/compactor" > "${WORK}/gate.log" 2>&1
  status=$?
  check '[ "${status}" != 0 ]' "the gate started without its grace setting" || return 1
  check '[ "$(logged LEASE_GATE_GRACE_SECONDS)" = 1 ]' "the gate did not name the missing setting"
}

# The real applets on the real clock: a start, a stop when the file goes, a start again. It
# asserts no time; every wait is for something that must happen. The gate reads the kernel's
# /proc/uptime where there is one; elsewhere (macOS) a writer keeps a stand-in moving.
case_real_clock() {
  busybox="${REAL_BUSYBOX:-${WORK}/stubs/busybox-real}"
  uptime_file=/proc/uptime
  if [ ! -r /proc/uptime ]; then
    uptime_file="${WORK}/real-uptime"
    (
      while :; do
        echo "$("${REAL_DATE}" +%s).00 0.00" > "${WORK}/real-uptime.tmp" && mv "${WORK}/real-uptime.tmp" "${uptime_file}"
        "${REAL_SLEEP}" 0.2
      done
    ) &
    echo "$!" > "${WORK}/uptime-writer.pid"
    await '[ -f "${uptime_file}" ]' "the uptime stand-in exists" || return 1
  fi
  echo "$(($("${REAL_DATE}" +%s) + 3600))" > "${WORK}/valid-until"
  start_gate_with 1 "${busybox}" "${uptime_file}"
  await '[ "$(starts)" = 1 ]' "the compactor started under a valid lease" || return 1
  pid="$(compactor_pid)"
  rm -f "${WORK}/valid-until"
  await 'grep -q "^term$" "${WORK}/events"' "the compactor got SIGTERM after the file went" || return 1
  await 'exited "${pid}"' "the compactor exited on SIGTERM" || return 1
  echo "$(($("${REAL_DATE}" +%s) + 3600))" > "${WORK}/valid-until"
  await '[ "$(starts)" = 2 ]' "the compactor started again when the lease was valid again" || return 1
  check no_applet_missing "the gate called an applet that is not on PATH"
}

for current in \
  waits_while_not_valid \
  starts_when_valid \
  stops_when_time_passes_then_starts_again \
  stops_when_file_goes \
  kills_after_the_grace \
  kills_on_the_clock_when_starved \
  kills_on_time_when_the_wall_clock_steps_back \
  restarts_after_exit_while_valid \
  passes_sigterm_on \
  missing_setting_fails \
  real_clock; do
  run=$((run + 1))
  setup
  "case_${current}" || failed=$((failed + 1))
done
cleanup

echo "compactor-gate [${GATE_SHELL}]: ${run} run, ${failed} failed"
[ "${failed}" = 0 ]
