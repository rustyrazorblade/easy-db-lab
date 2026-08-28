#!/usr/bin/env bash
#
# Unit tests for use-cassandra — the script that points a node at one of its installed Cassandra
# versions. The guard these cover is the one that stops it pointing `current` at a version that was
# never installed, which used to leave a dangling symlink and surface much later as a confusing
# startup failure.
#
# sudo/update-java-alternatives/update-alternatives are stubbed on PATH and every node path is
# redirected into a temp dir, so this needs no root and touches nothing outside the sandbox.
#
# Run directly:  ./use-cassandra.test.sh
# Or via gradle: ./gradlew testCassandraUseScript

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/use-cassandra"

if ! command -v yq >/dev/null 2>&1; then
  echo "SKIP - yq is not installed; use-cassandra reads the version list with it"
  exit 0
fi

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"

cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

# Record the alternatives switches so a test can assert which JDK was selected.
for tool in update-java-alternatives update-alternatives; do
  cat >"${BIN}/${tool}" <<SHIM
#!/bin/bash
echo "${tool} \$*" >> "${SANDBOX}/alternatives.log"
SHIM
done

# The sandbox has no cassandra user/group.
cat >"${BIN}/chown" <<'SHIM'
#!/bin/bash
exit 0
SHIM

# The script maps the node's architecture; nodes are Linux, so report what one reports.
cat >"${BIN}/uname" <<'SHIM'
#!/bin/bash
if [[ "${1:-}" == "-m" ]]; then echo x86_64; else exec /usr/bin/uname "$@"; fi
SHIM

chmod +x "${BIN}"/*
export PATH="${BIN}:${PATH}"

export CASSANDRA_INSTALL_DIR="${SANDBOX}/cassandra"
export CASSANDRA_CONF_LINK="${SANDBOX}/etc-cassandra"
export CASSANDRA_VERSIONS="${SANDBOX}/cassandra_versions.yaml"
export PYTHON_BIN_DIR="${SANDBOX}/pybin"

mkdir -p "${CASSANDRA_INSTALL_DIR}/5.0/conf" "$PYTHON_BIN_DIR"
touch "${PYTHON_BIN_DIR}/python3.11"
cat >"$CASSANDRA_VERSIONS" <<'YAMLFIXTURE'
- version: "5.0"
  java: "11"
  python: "3.11.9"
YAMLFIXTURE

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

run_script() {
  rm -f "${SANDBOX}/alternatives.log"
  OUTPUT="$(bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}

# --- an uninstalled version is refused before anything is changed ------------
run_script 9.9
if [[ "$STATUS" -eq 0 ]]; then
  fail "an uninstalled version should exit non-zero"
else
  pass "an uninstalled version exits non-zero"
fi

if [[ "$OUTPUT" == *"is not installed on this node"* ]]; then
  pass "an uninstalled version says so plainly"
else
  fail "expected a not-installed message, got: ${OUTPUT}"
fi

if [[ "$OUTPUT" == *"cassandra install 9.9"* ]]; then
  pass "an uninstalled version points at 'cassandra install'"
else
  fail "expected the install hint, got: ${OUTPUT}"
fi

if [[ "$OUTPUT" == *"5.0"* ]]; then
  pass "an uninstalled version lists what the node does have"
else
  fail "expected the installed versions to be listed, got: ${OUTPUT}"
fi

if [[ -e "${CASSANDRA_INSTALL_DIR}/current" ]]; then
  fail "a refused version must not leave a dangling 'current' symlink"
else
  pass "a refused version leaves no dangling symlink"
fi

if [[ -f "${SANDBOX}/alternatives.log" ]]; then
  fail "a refused version must not switch the node's java or python"
else
  pass "a refused version switches nothing"
fi

# --- an installed version is selected ----------------------------------------
run_script 5.0
if [[ "$STATUS" -eq 0 ]]; then
  pass "an installed version exits 0"
else
  fail "an installed version should exit 0, got ${STATUS}: ${OUTPUT}"
fi

if [[ "$(readlink "${CASSANDRA_INSTALL_DIR}/current")" == "${CASSANDRA_INSTALL_DIR}/5.0" ]]; then
  pass "an installed version becomes 'current'"
else
  fail "expected current -> 5.0, got $(readlink "${CASSANDRA_INSTALL_DIR}/current")"
fi

if [[ "$(readlink "$CASSANDRA_CONF_LINK")" == "${CASSANDRA_INSTALL_DIR}/5.0/conf" ]]; then
  pass "the conf symlink follows the selected version"
else
  fail "expected the conf link to point at 5.0/conf, got $(readlink "$CASSANDRA_CONF_LINK")"
fi

if grep -q "update-java-alternatives -s java-1.11.0-openjdk" "${SANDBOX}/alternatives.log"; then
  pass "the JDK declared for the version is selected"
else
  fail "expected JDK 11 to be selected, got: $(cat "${SANDBOX}/alternatives.log")"
fi

# --- switching away updates 'current' ----------------------------------------
mkdir -p "${CASSANDRA_INSTALL_DIR}/4.1/conf"
cat >"$CASSANDRA_VERSIONS" <<'YAMLFIXTURE'
- version: "5.0"
  java: "11"
  python: "3.11.9"
- version: "4.1"
  java: "11"
  python: "3.11.9"
YAMLFIXTURE
run_script 4.1
if [[ "$(readlink "${CASSANDRA_INSTALL_DIR}/current")" == "${CASSANDRA_INSTALL_DIR}/4.1" ]]; then
  pass "selecting another installed version moves 'current'"
else
  fail "expected current -> 4.1, got $(readlink "${CASSANDRA_INSTALL_DIR}/current")"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
