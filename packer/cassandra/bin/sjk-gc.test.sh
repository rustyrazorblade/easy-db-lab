#!/usr/bin/env bash
#
# Unit tests for sjk-gc, which streams Cassandra's GC activity until the operator presses Ctrl-C.
# The connection depends on the JDK: the pid attach on JDK 9 and later, the local JMX port on JDK
# 8. The script picks one before it starts and runs sjk once, so a stream ended with Ctrl-C (a
# non-zero exit) never starts a second session on the other connection.
#
# java, sudo and cassandra-pid are stubbed on PATH; java records each sjk call.
#
# Run directly:  ./sjk-gc.test.sh
# Or via gradle: ./gradlew testCassandraSjkGc

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/sjk-gc"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"
export SANDBOX

cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

cat >"${BIN}/cassandra-pid" <<'SHIM'
#!/bin/bash
echo 1234
SHIM

# `java -version` prints JAVA_VERSION_LINE on stderr, as java does; a sjk call is recorded and
# exits SJK_STATUS.
cat >"${BIN}/java" <<'SHIM'
#!/bin/bash
if [[ "$1" == "-version" ]]; then
  echo "$JAVA_VERSION_LINE" >&2
  exit 0
fi
echo "$*" >> "${SANDBOX}/sjk.calls"
exit "${SJK_STATUS:-0}"
SHIM
chmod +x "${BIN}"/*
export PATH="${BIN}:${PATH}"

tests_run=0
tests_failed=0

check() {
  local desc="$1" version_line="$2" sjk_status="$3" expected="$4" expected_status="$5"
  rm -f "${SANDBOX}/sjk.calls"
  JAVA_VERSION_LINE="$version_line" SJK_STATUS="$sjk_status" bash "$SCRIPT" --extra >/dev/null 2>&1
  local status=$?
  local calls
  calls="$(cat "${SANDBOX}/sjk.calls" 2>/dev/null)"
  tests_run=$((tests_run + 1))
  if [[ "$calls" == "$expected" && "$status" -eq "$expected_status" ]]; then
    echo "ok   - $desc"
  else
    tests_failed=$((tests_failed + 1))
    echo "FAIL - $desc: calls [$calls] status $status, expected [$expected] status $expected_status"
  fi
}

JAR=/usr/local/lib/sjk.jar
check "JDK 8 uses the local JMX port" 'openjdk version "1.8.0_432"' 0 "-jar $JAR gc -s localhost:7199 --extra" 0
check "JDK 11 attaches to the pid" 'openjdk version "11.0.25" 2024-10-15' 0 "-jar $JAR gc -p 1234 --extra" 0
check "JDK 21 attaches to the pid" 'openjdk version "21.0.5" 2024-10-15' 0 "-jar $JAR gc -p 1234 --extra" 0
check "a stream ended with Ctrl-C runs sjk once and passes its status on" \
  'openjdk version "17.0.13" 2024-10-15' 130 "-jar $JAR gc -p 1234 --extra" 130
check "on JDK 8 a failed stream is not retried on the pid" \
  'openjdk version "1.8.0_432"' 1 "-jar $JAR gc -s localhost:7199 --extra" 1

echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
