#!/usr/bin/env bash
#
# Unit tests for wait-for-up-normal — the script `cassandra start` runs on each node to wait until
# Cassandra reports OperationMode NORMAL.  It used to wait for the JMX port with no liveness check
# and no deadline, so a Cassandra that died before JMX opened hung `cassandra start` for minutes.
#
# cassandra-pid, ss, sjk-mx, systemctl, journalctl, sleep and date are stubbed on PATH.  `sleep`
# advances a fake clock that `date +%s` reads, so the timeout cases run in well under a second.
# Each run is also killed after a real deadline, so a regression fails the test instead of
# hanging it.
#
# Run directly:  ./wait-for-up-normal.test.sh
# Or via gradle: ./gradlew testCassandraWaitScript

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/wait-for-up-normal"
REAL_SLEEP="$(command -v sleep)"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
STATE="${SANDBOX}/state"
mkdir -p "$BIN" "$STATE"
export STATE

# Each stub counts its calls in $STATE/<name>.calls.
cat >"${BIN}/count-call" <<'SHIM'
#!/bin/bash
f="${STATE}/$1.calls"
n=$(( $(cat "$f" 2>/dev/null || echo 0) + 1 ))
echo "$n" >"$f"
echo "$n"
SHIM

# Cassandra's pid is 1234 until call PID_GONE_AT, then 0 (no process).
cat >"${BIN}/cassandra-pid" <<'SHIM'
#!/bin/bash
n=$(count-call cassandra-pid)
if [[ $n -ge ${PID_GONE_AT:-999999} ]]; then echo 0; else echo 1234; fi
SHIM

# The JMX port is listening from call JMX_OPEN_AT on.
cat >"${BIN}/ss" <<'SHIM'
#!/bin/bash
n=$(count-call ss)
echo "Netid State  Recv-Q Send-Q Local Address:Port Peer Address:Port"
if [[ $n -ge ${JMX_OPEN_AT:-999999} ]]; then echo "tcp   LISTEN 0      50     *:7199             *:*"; fi
SHIM

# OperationMode is NORMAL from call NORMAL_AT on.  Before that, as sjk does while StorageService is
# not registered yet, each call prints a connection error on stderr.
cat >"${BIN}/sjk-mx" <<'SHIM'
#!/bin/bash
n=$(count-call sjk-mx)
if [[ $n -ge ${NORMAL_AT:-999999} ]]; then echo "NORMAL"; else echo "sjk-poll-error" >&2; echo "JOINING"; fi
SHIM

cat >"${BIN}/systemctl" <<'SHIM'
#!/bin/bash
if [[ "$1" == "is-active" ]]; then exit 3; fi
echo "cassandra.service - Apache Cassandra: failed"
SHIM

cat >"${BIN}/journalctl" <<'SHIM'
#!/bin/bash
echo "journal"
SHIM

cat >"${BIN}/sleep" <<'SHIM'
#!/bin/bash
now=$(cat "${STATE}/clock")
echo $(( now + ${1:-1} )) >"${STATE}/clock"
SHIM

cat >"${BIN}/date" <<'SHIM'
#!/bin/bash
if [[ "${1:-}" == "+%s" ]]; then cat "${STATE}/clock"; else exec /bin/date "$@"; fi
SHIM

chmod +x "${BIN}"/*
export PATH="${BIN}:${PATH}"

tests_run=0
tests_failed=0

pass() {
  tests_run=$((tests_run + 1))
  echo "ok   - $1"
}

fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  echo "FAIL - $1"
}

# run_script VAR=val ... — runs the script with a fresh clock and fresh call counts.  Sets OUTPUT,
# STATUS and ELAPSED (fake seconds).  A run whose fake clock has not moved for 20 real seconds is
# killed, and STATUS is set to "hung".  The watchdog follows progress, not total real time: the
# default-deadline case runs about 600 stubbed loop iterations, which took over 120 real seconds
# once `check` ran the other test tiers alongside, and a fixed real limit killed it at 546 fake
# seconds although it was still advancing.
run_script() {
  rm -f "${STATE}"/*.calls
  echo 1000 >"${STATE}/clock"
  env "$@" bash "$SCRIPT" >"${SANDBOX}/out" 2>&1 &
  local pid=$!
  local idle=0
  local last now
  last="$(cat "${STATE}/clock")"
  while kill -0 "$pid" 2>/dev/null && [[ $idle -lt 200 ]]; do
    "$REAL_SLEEP" 0.1
    now="$(cat "${STATE}/clock")"
    if [[ "$now" == "$last" ]]; then idle=$((idle + 1)); else idle=0; last="$now"; fi
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    STATUS="hung"
  else
    wait "$pid"
    STATUS=$?
  fi
  OUTPUT="$(cat "${SANDBOX}/out")"
  ELAPSED=$(( $(cat "${STATE}/clock") - 1000 ))
}

# --- Cassandra dies before JMX opens -------------------------------------------
run_script PID_GONE_AT=4
if [[ "$STATUS" == "1" ]]; then
  pass "a Cassandra that dies before JMX opens exits 1"
else
  fail "a Cassandra that dies before JMX opens should exit 1, got ${STATUS}: ${OUTPUT}"
fi

if [[ "$OUTPUT" == *"CASSANDRA HAS SHUT DOWN: FAIL"* ]]; then
  pass "a Cassandra that dies before JMX opens says it has shut down"
else
  fail "expected the shut-down FAIL message, got: ${OUTPUT}"
fi

if [[ "$OUTPUT" == *"journalctl -u cassandra"* ]]; then
  pass "a Cassandra that dies before JMX opens points at journalctl"
else
  fail "expected the journalctl hint, got: ${OUTPUT}"
fi

if [[ "$ELAPSED" -le 5 ]]; then
  pass "a Cassandra that dies before JMX opens fails at once (${ELAPSED}s)"
else
  fail "a dead Cassandra should fail at once, waited ${ELAPSED}s"
fi

# --- JMX never opens while the process stays up --------------------------------
run_script WAIT_FOR_UP_NORMAL_TIMEOUT=30
if [[ "$STATUS" == "1" ]]; then
  pass "a JMX port that never opens exits 1"
else
  fail "a JMX port that never opens should exit 1, got ${STATUS}: ${OUTPUT}"
fi

if [[ "$OUTPUT" == *"TIMED OUT"*"JMX"*"FAIL"* ]]; then
  pass "a JMX port that never opens says the wait timed out"
else
  fail "expected a JMX timeout FAIL message, got: ${OUTPUT}"
fi

if [[ "$ELAPSED" -ge 30 && "$ELAPSED" -le 32 ]]; then
  pass "a JMX port that never opens fails at the timeout (${ELAPSED}s)"
else
  fail "expected to fail at the 30s timeout, waited ${ELAPSED}s"
fi

# --- JMX opens but the node never reaches NORMAL --------------------------------
run_script WAIT_FOR_UP_NORMAL_TIMEOUT=30 JMX_OPEN_AT=3
if [[ "$STATUS" == "1" && "$OUTPUT" == *"TIMED OUT"*"NORMAL"*"FAIL"* ]]; then
  pass "a node that never reaches NORMAL times out with a FAIL message"
else
  fail "a node that never reaches NORMAL should time out, got ${STATUS}: ${OUTPUT}"
fi

if [[ "$ELAPSED" -ge 30 && "$ELAPSED" -le 32 ]]; then
  pass "the JMX and NORMAL waits share one deadline (${ELAPSED}s)"
else
  fail "expected to fail at the 30s deadline, waited ${ELAPSED}s"
fi

# --- the default deadline allows a slow start -----------------------------------
run_script
if [[ "$STATUS" == "1" && "$ELAPSED" -ge 600 && "$ELAPSED" -le 602 ]]; then
  pass "the default deadline is 600s"
else
  fail "expected the default 600s deadline, got ${STATUS} after ${ELAPSED}s: ${OUTPUT}"
fi

# --- the normal path -------------------------------------------------------------
run_script JMX_OPEN_AT=3 NORMAL_AT=2
if [[ "$STATUS" == "0" ]]; then
  pass "a node that reaches NORMAL exits 0"
else
  fail "a node that reaches NORMAL should exit 0, got ${STATUS}: ${OUTPUT}"
fi

if [[ "$OUTPUT" == *"Waiting for JMX"* && "$(tail -n 1 <<<"$OUTPUT")" == "OK" ]]; then
  pass "a node that reaches NORMAL prints 'Waiting for JMX' and ends with OK"
else
  fail "expected 'Waiting for JMX' and a final OK, got: ${OUTPUT}"
fi

# The wait polls once a second and reads only the exit status, so the errors sjk prints before
# NORMAL are not shown: each start would otherwise print one per poll.
run_script JMX_OPEN_AT=1 NORMAL_AT=4
if [[ "$STATUS" == "0" && "$OUTPUT" != *"sjk-poll-error"* ]]; then
  pass "the polls before NORMAL print no sjk error"
else
  fail "expected no sjk error in the wait output, got ${STATUS}: ${OUTPUT}"
fi

# --- sjk-mx: the connection the JDK supports, once --------------------------------
# The pid attach fails on JDK 8 (root cannot attach to a JVM another user owns); the JMX port fails
# on JDK 9+ (sjk cannot reach java.rmi internals).  sjk-mx picks one from `java -version` and runs
# sjk once, so a real query error is neither run twice nor hidden.  The java stub prints
# JAVA_VERSION_LINE for -version, records each sjk call's connection flag, and exits SJK_STATUS
# with "sjk-error" on stderr when it is non-zero.
SJK_DIR="${SANDBOX}/sjk"
mkdir -p "$SJK_DIR"
printf '#!/bin/bash\nexec "$@"\n' >"${SJK_DIR}/sudo"
printf '#!/bin/bash\necho 1234\n' >"${SJK_DIR}/cassandra-pid"
printf '#!/bin/bash\nif [[ "$1" == -version ]]; then echo "$JAVA_VERSION_LINE" >&2; exit 0; fi\necho "$4 $5 $6" >>"%s/java.calls"\nif [[ "${SJK_STATUS:-0}" != 0 ]]; then echo sjk-error >&2; exit "$SJK_STATUS"; fi\necho "OperationMode NORMAL"\n' "$STATE" >"${SJK_DIR}/java"
chmod +x "${SJK_DIR}"/*

# java -version line | sjk status | expected exit | expected calls | stderr shown
while IFS='|' read -r version_line sjk_status want_status want_calls want_err; do
  rm -f "${STATE}/java.calls"
  got_err="$(JAVA_VERSION_LINE="$version_line" SJK_STATUS="$sjk_status" PATH="${SJK_DIR}:${PATH}" \
    bash "${SCRIPT_DIR}/sjk-mx" -b x 2>&1 >/dev/null)"
  got_status=$?
  got_calls="$(paste -sd ';' "${STATE}/java.calls")"
  if [[ "$got_status" == "$want_status" && "$got_calls" == "$want_calls" && "$got_err" == "$want_err" ]]; then
    pass "sjk-mx on [${version_line}] with sjk status ${sjk_status} exits ${want_status} after: ${want_calls}"
  else
    fail "sjk-mx on [${version_line}] status ${sjk_status}: want ${want_status} '${want_calls}' [${want_err}], got ${got_status} '${got_calls}' [${got_err}]"
  fi
done <<'TABLE'
openjdk version "1.8.0_432"|0|0|-s localhost:7199 -b|
openjdk version "11.0.25" 2024-10-15|0|0|-p 1234 -b|
openjdk version "21.0.5" 2024-10-15|0|0|-p 1234 -b|
openjdk version "17.0.13" 2024-10-15|1|1|-p 1234 -b|sjk-error
openjdk version "1.8.0_432"|1|1|-s localhost:7199 -b|sjk-error
TABLE

# --- Cassandra is not running at all ---------------------------------------------
run_script PID_GONE_AT=1
if [[ "$STATUS" == "1" && "$OUTPUT" == *"CASSANDRA NOT RUNNING: FAIL"* ]]; then
  pass "a Cassandra that is not running fails at once"
else
  fail "a Cassandra that is not running should fail with NOT RUNNING, got ${STATUS}: ${OUTPUT}"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
