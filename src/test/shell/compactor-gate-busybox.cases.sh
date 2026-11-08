#!/bin/sh
#
# The compactor gate's cases, in POSIX sh, for busybox sh in busybox:1.37.0-musl. Driven by
# compactor-gate-busybox.test.sh. The gate runs with PATH pointing nowhere and with the real static
# busybox as its applet binary, as in the pod; the fake compactor and this driver use busybox too.

set -u

BB=/bin/busybox
GATE=/gate/compactor-gate.sh
run=0
failed=0
WORK=""
GATE_PID=""

cleanup() {
  [ -n "${GATE_PID}" ] && $BB kill -KILL "${GATE_PID}" 2>/dev/null
  [ -n "${WORK}" ] && [ -f "${WORK}/pid" ] && $BB kill -KILL "$($BB cat "${WORK}/pid")" 2>/dev/null
  [ -n "${WORK}" ] && $BB rm -rf "${WORK}"
  GATE_PID=""
  return 0
}
trap cleanup EXIT

fail() {
  echo "FAIL [busybox sh]: $1"
  $BB sed 's/^/    gate: /' "${WORK}/gate.log" 2>/dev/null
  $BB sed 's/^/    compactor: /' "${WORK}/events" 2>/dev/null
  failed=$((failed + 1))
}

setup() {
  cleanup
  WORK="$($BB mktemp -d)"
  $BB cat > "${WORK}/compactor" <<EOF
#!/bin/busybox sh
if [ "\${FAKE_IGNORE_TERM:-0}" = 1 ]; then trap '' TERM; else trap 'echo term >> "${WORK}/events"; exit 0' TERM; fi
echo "\$\$" > "${WORK}/pid"
echo "start \$*" >> "${WORK}/events"
if [ -n "\${FAKE_EXIT_AFTER:-}" ]; then $BB sleep "\${FAKE_EXIT_AFTER}"; echo exit >> "${WORK}/events"; exit 3; fi
while :; do $BB sleep 1 & wait \$!; done
EOF
  $BB chmod +x "${WORK}/compactor"
}

now() { $BB date +%s; }
grant() { echo "$(($(now) + $1))" > "${WORK}/valid-until"; }
starts() { $BB grep -c '^start' "${WORK}/events" 2>/dev/null || echo 0; }
alive() { [ -f "${WORK}/pid" ] && $BB kill -0 "$($BB cat "${WORK}/pid")" 2>/dev/null; }

start_gate() {
  PATH=/nonexistent GATE_BUSYBOX=$BB GATE_VALID_UNTIL="${WORK}/valid-until" \
    LEASE_GATE_POLL_SECONDS=1 LEASE_GATE_GRACE_SECONDS="$1" \
    $BB sh "${GATE}" -- "${WORK}/compactor" -target=compactor > "${WORK}/gate.log" 2>&1 &
  GATE_PID=$!
}

eventually() {
  deadline=$(($(now) + $2))
  while [ "$(now)" -le "${deadline}" ]; do
    eval "$1" && return 0
    $BB sleep 0.2
  done
  return 1
}

case_no_start_while_not_valid() {
  run=$((run + 1)); setup; start_gate 1
  $BB sleep 2; echo "$(($(now) - 10))" > "${WORK}/valid-until"; $BB sleep 2
  [ "$(starts)" = 0 ] || fail "started while the lease was not valid"
  $BB grep -qi 'not found' "${WORK}/gate.log" && fail "called an applet that is not on PATH"
}

case_start_stop_start() {
  # The lease runs out only after the start is recorded, so a slow start cannot race the expiry.
  run=$((run + 1)); setup; grant 100; start_gate 1
  eventually '[ "$(starts)" = 1 ]' 15 || fail "did not start once the lease was valid"
  grant 1
  eventually '$BB grep -q "^term$" "${WORK}/events"' 6 || fail "no SIGTERM after the time passed"
  grant 100
  eventually '[ "$(starts)" = 2 ]' 15 || fail "did not start again when the lease was valid again"
  $BB grep -qi 'not found' "${WORK}/gate.log" && fail "called an applet that is not on PATH"
}

case_kill_after_grace() {
  run=$((run + 1)); setup; grant 100; FAKE_IGNORE_TERM=1 start_gate 2
  eventually '[ "$(starts)" = 1 ]' 3 || fail "did not start"
  $BB rm -f "${WORK}/valid-until"; $BB sleep 2
  alive || fail "killed before the grace passed"
  eventually '! alive' 4 || fail "still runs after the grace"
}

case_restart_after_exit() {
  run=$((run + 1)); setup; grant 100; FAKE_EXIT_AFTER=1 start_gate 1
  eventually '[ "$(starts)" -ge 2 ]' 8 || fail "not started again after it exited"
}

case_sigterm_passed_on() {
  run=$((run + 1)); setup; grant 100; start_gate 1
  eventually '[ "$(starts)" = 1 ]' 3 || fail "did not start"
  $BB kill -TERM "${GATE_PID}"
  eventually '$BB grep -q "^term$" "${WORK}/events"' 3 || fail "the compactor did not get the gate's SIGTERM"
  wait "${GATE_PID}"; status=$?; GATE_PID=""
  [ "${status}" = 0 ] || fail "the gate exited ${status} on SIGTERM, not 0"
}

case_no_start_while_not_valid
case_start_stop_start
case_kill_after_grace
case_restart_after_exit
case_sigterm_passed_on

echo "compactor-gate under busybox sh: ${run} run, ${failed} failed"
[ "${failed}" = 0 ]
