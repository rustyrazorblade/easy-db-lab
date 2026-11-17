#!/usr/bin/env bash
#
# Unit tests for use-cassandra — the script that points a node at one of its installed Cassandra
# versions and switches the node's JDK and Python to the ones the CLI chose. The CLI records the
# JDK and Python in the cluster state and passes them as arguments; the node reads no file.
#
# The guard these cover first is the one that stops it pointing `current` at a version that was
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

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"

cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

# Record the alternatives switches so a test can assert which JDK was selected.
# A test makes a switch fail by creating ${SANDBOX}/<tool>.fail.
for tool in update-java-alternatives update-alternatives; do
  cat >"${BIN}/${tool}" <<SHIM
#!/bin/bash
echo "${tool} \$*" >> "${SANDBOX}/alternatives.log"
if [[ -f "${SANDBOX}/${tool}.fail" ]]; then
  echo "${tool}: no such alternative" >&2
  exit 1
fi
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
export PYTHON_BIN_DIR="${SANDBOX}/pybin"
export JVM_DIR="${SANDBOX}/jvm"
for jdk in 8 11 17 21 25; do mkdir -p "${JVM_DIR}/java-1.${jdk}.0-openjdk-amd64"; done

mkdir -p "${CASSANDRA_INSTALL_DIR}/5.0/conf" "$PYTHON_BIN_DIR"
touch "${PYTHON_BIN_DIR}/python3.11"

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
  rm -f "${CASSANDRA_INSTALL_DIR}/current"
  OUTPUT="$(bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}

# A refused call changes nothing: no symlink, no switch.
assert_refused() {
  local desc="$1" expected="$2"
  shift 2
  run_script "$@"
  if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"$expected"* ]]; then
    pass "$desc"
  else
    fail "$desc: expected a non-zero exit mentioning [$expected], got ${STATUS}: ${OUTPUT}"
  fi
  if [[ -e "${CASSANDRA_INSTALL_DIR}/current" || -f "${SANDBOX}/alternatives.log" ]]; then
    fail "$desc: a refused call must change nothing on the node"
  else
    pass "$desc: nothing on the node changed"
  fi
}

# --- the CLI passes every argument; the node reads no file -------------------
assert_refused "a missing version is refused" "Usage: use-cassandra"
assert_refused "a missing --java is refused" "--java is required" 5.0 --python 3.11.9
assert_refused "a missing --python is refused" "--python is required" 5.0 --java 17
assert_refused "an unknown option is refused" "unknown option --bogus" 5.0 --java 17 --python 3.11.9 --bogus
assert_refused "an unknown JDK is refused before the switch" "Unknown java version 9" 5.0 --java 9 --python 3.11.9
assert_refused "a Python the node lacks is refused before the switch" "Python version 3.99.1" 5.0 --java 17 --python 3.99.1
assert_refused "--java with no value is refused, not looped on" "--java needs a value" 5.0 --python 3.11.9 --java
assert_refused "--python with no value is refused, not looped on" "--python needs a value" 5.0 --java 17 --python
rm -rf "${JVM_DIR}/java-1.21.0-openjdk-amd64"
assert_refused "a JDK missing from the image is refused before the symlinks move" "JDK 21" 5.0 --java 21 --python 3.11.9
mkdir -p "${JVM_DIR}/java-1.21.0-openjdk-amd64"
for bad in ".." "../../etc" "feature/x" "-x" ".hidden" "a b"; do
  assert_refused "version name [$bad] is refused" "invalid version name" "$bad" --java 17 --python 3.11.9
done

for gone in yq cassandra_versions.yaml CASSANDRA_VERSIONS; do
  if grep -qF "$gone" "$SCRIPT"; then
    fail "use-cassandra still refers to $gone: $(grep -nF "$gone" "$SCRIPT")"
  else
    pass "use-cassandra does not refer to $gone"
  fi
done

# --- an uninstalled version is refused before anything is changed ------------
assert_refused "an uninstalled version is refused" "is not installed on this node" 9.9 --java 17 --python 3.11.9

if [[ "$OUTPUT" == *"cassandra install 9.9"* && "$OUTPUT" == *"5.0"* ]]; then
  pass "an uninstalled version points at 'cassandra install' and lists what the node has"
else
  fail "expected the install hint and the installed list, got: ${OUTPUT}"
fi

# --- an installed version is selected, with the JDK and Python given ---------
run_script 5.0 --java 17 --python 3.11.9
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

if grep -q "update-java-alternatives -s java-1.17.0-openjdk-amd64" "${SANDBOX}/alternatives.log"; then
  pass "the JDK passed with --java is selected"
else
  fail "expected JDK 17 to be selected, got: $(cat "${SANDBOX}/alternatives.log")"
fi

if grep -q "update-alternatives --set python ${PYTHON_BIN_DIR}/python3.11" "${SANDBOX}/alternatives.log"; then
  pass "the Python passed with --python is selected"
else
  fail "expected python3.11 to be selected, got: $(cat "${SANDBOX}/alternatives.log")"
fi

# --- each supported JDK maps to its alternative ------------------------------
for jdk in 8 11 21 25; do
  run_script 5.0 --java "$jdk" --python 3.11.9
  if [[ "$STATUS" -eq 0 ]] && grep -q "update-java-alternatives -s java-1.${jdk}.0-openjdk-amd64" "${SANDBOX}/alternatives.log"; then
    pass "--java ${jdk} selects java-1.${jdk}.0-openjdk"
  else
    fail "--java ${jdk} should select java-1.${jdk}.0-openjdk (status ${STATUS}): $(cat "${SANDBOX}/alternatives.log" 2>/dev/null)"
  fi
done

# --- switching away updates 'current' ----------------------------------------
mkdir -p "${CASSANDRA_INSTALL_DIR}/4.1/conf"
run_script 4.1 --java 11 --python 3.11.9
if [[ "$(readlink "${CASSANDRA_INSTALL_DIR}/current")" == "${CASSANDRA_INSTALL_DIR}/4.1" ]]; then
  pass "selecting another installed version moves 'current'"
else
  fail "expected current -> 4.1, got $(readlink "${CASSANDRA_INSTALL_DIR}/current")"
fi

# --- a failed JDK switch fails the script -------------------------------------
# A wrong jinfo name leaves the node on its old JDK; exiting 0 then would hide that until Cassandra
# misbehaves on the wrong runtime.
touch "${SANDBOX}/update-java-alternatives.fail"
run_script 5.0 --java 25 --python 3.11.9
rm -f "${SANDBOX}/update-java-alternatives.fail"
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"JDK 25"* ]]; then
  pass "a failed JDK switch exits non-zero and names the requested JDK"
else
  fail "a failed JDK switch should exit non-zero naming JDK 25, got ${STATUS}: ${OUTPUT}"
fi

# --- a failed Python switch fails the script ----------------------------------
touch "${SANDBOX}/update-alternatives.fail"
run_script 5.0 --java 17 --python 3.11.9
rm -f "${SANDBOX}/update-alternatives.fail"
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"Python 3.11.9"* ]]; then
  pass "a failed Python switch exits non-zero and names the requested Python"
else
  fail "a failed Python switch should exit non-zero naming Python 3.11.9, got ${STATUS}: ${OUTPUT}"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
