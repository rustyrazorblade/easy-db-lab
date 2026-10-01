#!/usr/bin/env bash
#
# Unit tests for install_jdks.sh: the base AMI installs every JDK a supported Cassandra release can
# select (8, 11, 17, 21, 25) with its debug symbols, and makes Java 11 the default.
#
# sudo/apt-get/update-java-alternatives/sed are stubbed on PATH and record their arguments, so this
# needs no root, no network and no Docker.
#
# Run directly:  ./install_jdks.test.sh
# Or via gradle: ./gradlew testBaseJdkInstall

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/install_jdks.sh"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
CALLS="${SANDBOX}/calls.log"
mkdir -p "$BIN"

# `sudo VAR=value cmd` sets the variable for cmd, so run it through env.
cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec env "$@"
SHIM

for tool in apt-get update-java-alternatives sed; do
  cat >"${BIN}/${tool}" <<SHIM
#!/bin/bash
echo "${tool} \$*" >> "${CALLS}"
SHIM
done

chmod +x "${BIN}"/*

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

OUTPUT="$(PATH="${BIN}:${PATH}" ARCH=arm64 JDK_INSTALL_LOG="${SANDBOX}/jdk-install.log" bash "$SCRIPT" 2>&1)"
STATUS=$?

if [[ "$STATUS" -eq 0 ]]; then
  pass "install_jdks.sh exits 0"
else
  fail "install_jdks.sh should exit 0, got ${STATUS}: ${OUTPUT}"
fi

if [[ -f "${SANDBOX}/jdk-install.log" ]]; then
  pass "the install log goes where JDK_INSTALL_LOG points"
else
  fail "expected the install log at ${SANDBOX}/jdk-install.log"
fi

install_call="$(grep '^apt-get install' "$CALLS" 2>/dev/null)"
for jdk in 8 11 17 21 25; do
  for pkg in "openjdk-${jdk}-jdk" "openjdk-${jdk}-dbg"; do
    if [[ " ${install_call} " == *" ${pkg} "* ]]; then
      pass "apt-get installs ${pkg}"
    else
      fail "apt-get should install ${pkg}, got: ${install_call}"
    fi
  done
done

if grep -q '^update-java-alternatives -s /usr/lib/jvm/java-1.11.0-openjdk-arm64$' "$CALLS" 2>/dev/null; then
  pass "Java 11 for the node's architecture is the default"
else
  fail "expected java-1.11.0-openjdk-arm64 as the default, got: $(grep update-java-alternatives "$CALLS" 2>/dev/null)"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
