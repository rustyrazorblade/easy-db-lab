#!/usr/bin/env bash
#
# Unit tests for install_cassandra.sh, the bake-time setup of a db node. The bake installs no
# Cassandra version (the CLI installs one on demand), so the tests check that the setup runs, that
# it installs the files a runtime install needs, and that it installs no version and reads no
# version list.
#
# sudo, uv, dpkg, lsblk and install-cassandra-version are stubs on PATH that record their calls.
# No network, no Docker, no root.
#
# Run directly:  ./install_cassandra.test.sh
# Or via gradle: ./gradlew testCassandraInstallLoop

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/install_cassandra.sh"

STUB_DIR="$(mktemp -d)"
trap 'rm -rf "$STUB_DIR"' EXIT
CALLS="${STUB_DIR}/calls.log"
: >"$CALLS"

for tool in sudo uv dpkg lsblk install-cassandra-version yq; do
  printf '#!/bin/sh\necho "%s $*" >>"%s"\n' "$tool" "$CALLS" >"${STUB_DIR}/${tool}"
  chmod +x "${STUB_DIR}/${tool}"
done

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

# --- without INSTALL_CASSANDRA the script changes nothing ---------------------
output="$(PATH="${STUB_DIR}:${PATH}" bash "$SCRIPT" 2>&1)"
status=$?
if [[ "$status" -eq 0 && "$output" == *"INSTALL_CASSANDRA is not set"* && ! -s "$CALLS" ]]; then
  pass "without INSTALL_CASSANDRA it exits 0 and runs nothing"
else
  fail "without INSTALL_CASSANDRA: status $status, output [$output], calls [$(cat "$CALLS")]"
fi

# --- with INSTALL_CASSANDRA it does the one-time setup ------------------------
: >"$CALLS"
PATH="${STUB_DIR}:${PATH}" INSTALL_CASSANDRA=1 bash "$SCRIPT" >/dev/null 2>&1
status=$?
calls="$(cat "$CALLS")"

if [[ "$status" -eq 0 ]]; then pass "the setup succeeds"; else fail "the setup exited $status"; fi

for expected in \
  "sudo useradd -m -u 999 cassandra" \
  "sudo mkdir -p /usr/local/cassandra" \
  "uv tool install cqlsh" \
  "sudo install -D -m 0644 /tmp/cassandra.in.sh /usr/local/share/easy-db-lab/cassandra.in.sh" \
  "sudo install -D -m 0644 /tmp/edl-cassandra-agents.sh /usr/local/lib/edl-cassandra-agents.sh"; do
  if grep -qxF "$expected" <<<"$calls"; then pass "runs: $expected"; else fail "did not run: $expected"; fi
done

# --- no version is installed and no version list is read ----------------------
if grep -q "^install-cassandra-version\|^yq" <<<"$calls"; then
  fail "the bake installed a version or read a version list: $calls"
else
  pass "the bake installs no Cassandra version and reads no version list"
fi
if grep -q "cassandra_versions" "$SCRIPT"; then
  fail "install_cassandra.sh still names cassandra_versions"
else
  pass "install_cassandra.sh names no version file"
fi

# cqlsh runs under the Python the CLI selected. Every bundled cqlsh wrapper tries `python3` first,
# which on the AMI is the distro Python (3.12+), where a 4.x cqlsh's bundled six fails to import.
# CQLSH_PYTHON makes the wrapper use `python`, the alternative use-cassandra switches to --python.
ENVIRONMENT_FILE="${SCRIPT_DIR}/../environment"
USE_CASSANDRA="${SCRIPT_DIR}/../bin/use-cassandra"
if grep -qx 'CQLSH_PYTHON=/usr/bin/python' "$ENVIRONMENT_FILE"; then
  pass "the node environment points cqlsh at /usr/bin/python"
else
  fail "the node environment does not set CQLSH_PYTHON=/usr/bin/python"
fi
if grep -q 'update-alternatives --set python ' "$USE_CASSANDRA"; then
  pass "use-cassandra switches the /usr/bin/python alternative that cqlsh uses"
else
  fail "use-cassandra no longer switches the python alternative CQLSH_PYTHON names"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
