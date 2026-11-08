#!/usr/bin/env bash
#
# Unit tests for the account compactor's gate, configuration/compactor/compactor-gate.sh.
#
# The gate runs one compactor binary only while the lease agent's valid-until file holds a time in
# the future. These tests run the real script against a fake compactor and a real clock:
# - no start while the lease is not valid (no file, an expired time, garbage);
# - a start once it is valid;
# - SIGTERM, then SIGKILL after the grace, when the time passes or the file goes, and a new start
#   when it is valid again;
# - a new start of a binary that exits while the lease is valid;
# - SIGTERM to the gate passed on to the binary, then the gate exits.
#
# The gate runs with PATH pointing nowhere and GATE_BUSYBOX at a stub that runs the system's date
# and sleep, so any applet the script calls without the explicit busybox path fails the test, as it
# would in the distroless compactor images. Each case runs under every POSIX shell found (sh, dash,
# bash and busybox sh); the gate runs under busybox sh in the pod.
#
# Run directly:  bash src/test/shell/compactor-gate.test.sh
# Or via gradle: ./gradlew testCompactorGate

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/compactor/compactor-gate.sh"
SLEEP="$(command -v sleep)"
# How long a wait for the fake compactor's start may take. It checks that a start happens, not how
# fast, and returns as soon as it does; under a loaded build an 8 s budget missed a start the gate
# had already made.
START_BUDGET=15
DATE="$(command -v date)"

tests_run=0
tests_failed=0
WORK=""
GATE_PID=""

cleanup() {
  [ -n "${GATE_PID}" ] && { kill -KILL "${GATE_PID}" 2>/dev/null; wait "${GATE_PID}" 2>/dev/null; }
  [ -f "${WORK}/pid" ] && kill -KILL "$(cat "${WORK}/pid")" 2>/dev/null
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  return 0
}
trap cleanup EXIT

fail() {
  echo "FAIL [${SHELL_UNDER_TEST}]: $1"
  echo "  gate log:"; sed 's/^/    /' "${WORK}/gate.log" 2>/dev/null
  echo "  compactor events:"; sed 's/^/    /' "${WORK}/events" 2>/dev/null
  tests_failed=$((tests_failed + 1))
}

setup() {
  cleanup
  GATE_PID=""
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs"
  # The busybox stub: the two applets the gate may call, and nothing else.
  cat > "${WORK}/stubs/busybox" <<EOF
#!/bin/sh
case "\$1" in
  date) shift; exec "${DATE}" "\$@" ;;
  sleep) shift; exec "${SLEEP}" "\$@" ;;
esac
echo "busybox stub: unexpected applet \$1" >&2
exit 127
EOF
  # The fake compactor: records its start, its pid and any SIGTERM. FAKE_IGNORE_TERM makes it
  # ignore SIGTERM; FAKE_EXIT_AFTER makes it exit on its own after that many seconds.
  cat > "${WORK}/stubs/compactor" <<EOF
#!/bin/sh
# The trap comes first, so a SIGTERM that follows the recorded start is always recorded too.
if [ "\${FAKE_IGNORE_TERM:-0}" = 1 ]; then
  trap '' TERM
else
  trap 'echo term >> "${WORK}/events"; exit 0' TERM
fi
echo "\$\$" > "${WORK}/pid"
echo "start \$*" >> "${WORK}/events"
if [ -n "\${FAKE_EXIT_AFTER:-}" ]; then
  "${SLEEP}" "\${FAKE_EXIT_AFTER}"
  echo exit >> "${WORK}/events"
  exit 3
fi
while :; do "${SLEEP}" 1 & wait \$!; done
EOF
  chmod +x "${WORK}/stubs/busybox" "${WORK}/stubs/compactor"
}

now() { "${DATE}" +%s; }

grant() { echo "$(($(now) + $1))" > "${WORK}/valid-until"; }

starts() { grep -c '^start' "${WORK}/events" 2>/dev/null || echo 0; }

# Starts the gate in the background with grace $1 seconds.
start_gate() {
  # shellcheck disable=SC2086 # SHELL_UNDER_TEST may be "busybox sh".
  env PATH=/nonexistent GATE_BUSYBOX="${WORK}/stubs/busybox" GATE_VALID_UNTIL="${WORK}/valid-until" \
    LEASE_GATE_POLL_SECONDS=1 LEASE_GATE_GRACE_SECONDS="$1" \
    ${SHELL_UNDER_TEST} "${GATE}" -- "${WORK}/stubs/compactor" -target=compactor > "${WORK}/gate.log" 2>&1 &
  GATE_PID=$!
}

# Waits up to $2 seconds for the command $1 to succeed.
eventually() {
  local deadline=$(($(now) + $2))
  while [ "$(now)" -le "${deadline}" ]; do
    eval "$1" && return 0
    "${SLEEP}" 0.2
  done
  return 1
}

compactor_alive() { [ -f "${WORK}/pid" ] && kill -0 "$(cat "${WORK}/pid")" 2>/dev/null; }

no_applet_missing() { ! grep -qi 'not found' "${WORK}/gate.log"; }

test_no_start_while_not_valid() {
  tests_run=$((tests_run + 1))
  setup
  start_gate 1
  "${SLEEP}" 2
  echo "$(($(now) - 10))" > "${WORK}/valid-until"
  "${SLEEP}" 2
  echo "not-a-time" > "${WORK}/valid-until"
  "${SLEEP}" 2
  [ "$(starts)" = 0 ] || fail "the compactor started while the lease was not valid"
  kill -0 "${GATE_PID}" 2>/dev/null || fail "the gate exited while waiting for the lease"
  no_applet_missing || fail "the gate called an applet that is not on PATH"
}

test_start_when_valid() {
  tests_run=$((tests_run + 1))
  setup
  start_gate 1
  "${SLEEP}" 1
  grant 100
  eventually '[ "$(starts)" = 1 ]' "${START_BUDGET}" || fail "the compactor did not start once the lease was valid"
  grep -q '^start -target=compactor$' "${WORK}/events" || fail "the compactor did not get its arguments"
  no_applet_missing || fail "the gate called an applet that is not on PATH"
}

test_stop_when_time_passes_then_start_again() {
  tests_run=$((tests_run + 1))
  setup
  # The lease runs out only after the compactor has recorded its start: under a loaded build the
  # fake compactor can take longer than a short first grant to start, and the gate then rightly
  # stops it before it records anything.
  grant 100
  start_gate 1
  eventually '[ "$(starts)" = 1 ]' "${START_BUDGET}" || fail "the compactor did not start"
  grant 1
  eventually 'grep -q "^term$" "${WORK}/events"' 6 || fail "the compactor got no SIGTERM after the time passed"
  eventually '! compactor_alive' 2 || fail "the compactor still runs after the time passed"
  kill -0 "${GATE_PID}" 2>/dev/null || fail "the gate exited instead of waiting for the lease again"
  grant 100
  eventually '[ "$(starts)" = 2 ]' "${START_BUDGET}" || fail "the compactor did not start again when the lease was valid again"
}

test_stop_when_file_goes() {
  tests_run=$((tests_run + 1))
  setup
  grant 100
  start_gate 1
  eventually '[ "$(starts)" = 1 ]' "${START_BUDGET}" || fail "the compactor did not start"
  rm -f "${WORK}/valid-until"
  eventually 'grep -q "^term$" "${WORK}/events"' 3 || fail "the compactor got no SIGTERM after the file went"
}

test_kill_after_grace() {
  tests_run=$((tests_run + 1))
  setup
  grant 100
  FAKE_IGNORE_TERM=1 start_gate 2
  eventually '[ "$(starts)" = 1 ]' "${START_BUDGET}" || fail "the compactor did not start"
  rm -f "${WORK}/valid-until"
  "${SLEEP}" 2
  compactor_alive || fail "the compactor was killed before the grace passed"
  eventually '! compactor_alive' 4 || fail "the compactor still runs after the grace"
  grep -q SIGKILL "${WORK}/gate.log" || fail "the gate did not report the SIGKILL"
}

test_restart_after_exit_while_valid() {
  tests_run=$((tests_run + 1))
  setup
  grant 100
  FAKE_EXIT_AFTER=1 start_gate 1
  eventually '[ "$(starts)" -ge 2 ]' "${START_BUDGET}" || fail "the compactor was not started again after it exited"
  grep -q 'the compactor exited' "${WORK}/gate.log" || fail "the gate did not report the exit"
}

test_sigterm_is_passed_on() {
  tests_run=$((tests_run + 1))
  setup
  grant 100
  start_gate 1
  eventually '[ "$(starts)" = 1 ]' "${START_BUDGET}" || fail "the compactor did not start"
  kill -TERM "${GATE_PID}"
  eventually 'grep -q "^term$" "${WORK}/events"' 3 || fail "the compactor did not get the gate's SIGTERM"
  eventually '! kill -0 "${GATE_PID}" 2>/dev/null' 3 || fail "the gate did not exit after its compactor stopped"
  wait "${GATE_PID}"
  [ $? = 0 ] || fail "the gate did not exit 0 on SIGTERM"
  GATE_PID=""
}

test_missing_setting_fails() {
  tests_run=$((tests_run + 1))
  setup
  # shellcheck disable=SC2086
  env PATH=/nonexistent GATE_BUSYBOX="${WORK}/stubs/busybox" GATE_VALID_UNTIL="${WORK}/valid-until" \
    LEASE_GATE_POLL_SECONDS=1 ${SHELL_UNDER_TEST} "${GATE}" -- "${WORK}/stubs/compactor" > "${WORK}/gate.log" 2>&1
  [ $? != 0 ] || fail "the gate started without its grace setting"
  grep -q LEASE_GATE_GRACE_SECONDS "${WORK}/gate.log" || fail "the gate did not name the missing setting"
}

shells=()
for candidate in sh dash bash; do
  command -v "${candidate}" > /dev/null && shells+=("$(command -v "${candidate}")")
done
command -v busybox > /dev/null && shells+=("$(command -v busybox) sh")

for SHELL_UNDER_TEST in "${shells[@]}"; do
  echo "Under ${SHELL_UNDER_TEST}"
  test_no_start_while_not_valid
  test_start_when_valid
  test_stop_when_time_passes_then_start_again
  test_stop_when_file_goes
  test_kill_after_grace
  test_restart_after_exit_while_valid
  test_sigterm_is_passed_on
  test_missing_setting_fails
done

echo "compactor-gate: ${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" = 0 ]
