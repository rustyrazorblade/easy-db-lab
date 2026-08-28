#!/usr/bin/env bash
#
# Unit tests for install-cassandra-version — the one script both the AMI bake and
# `easy-db-lab cassandra install` use to install a single Cassandra version.
#
# These cover the decisions the script makes before it touches the network: argument handling,
# the already-installed early return, the guards on building from source, and JDK selection. The
# install paths themselves (download/extract/build) are exercised against a real container, not
# here. No network, no Docker, no root: sudo/dpkg/curl/git are stubbed on PATH and the install
# directory is redirected into a temp dir.
#
# Run directly:  ./install-cassandra-version.test.sh
# Or via gradle: ./gradlew testCassandraInstallScript

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/install-cassandra-version"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"

# sudo runs the command directly; the test never needs root.
cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

# Record any network or VCS reach so a test can assert the script never got that far.
for tool in curl git ant; do
  cat >"${BIN}/${tool}" <<SHIM
#!/bin/bash
echo "${tool} \$*" >> "${SANDBOX}/reached-out.log"
exit 1
SHIM
done

# dpkg is Debian-only; the script only asks it for the architecture.
cat >"${BIN}/dpkg" <<'SHIM'
#!/bin/bash
echo amd64
SHIM

chmod +x "${BIN}"/*
export PATH="${BIN}:${PATH}"

export CASSANDRA_INSTALL_DIR="${SANDBOX}/cassandra"
export CASSANDRA_IN_SH_SNIPPET="${SANDBOX}/cassandra.in.sh"
# The script's working directory comes from mktemp -d, so point that at the sandbox to make what
# it leaves behind observable.
export TMPDIR="${SANDBOX}/tmp"
mkdir -p "$CASSANDRA_INSTALL_DIR" "$TMPDIR"
echo '# test snippet' >"$CASSANDRA_IN_SH_SNIPPET"

# GNU mktemp (the nodes, and CI) honors TMPDIR; BSD mktemp (macOS) ignores it and uses the
# per-user Darwin temp dir, which would make the cleanup assertion below silently vacuous.
probe="$(mktemp -d)"
case "$probe" in
  "${TMPDIR}"/*) tmpdir_observable=true ;;
  *) tmpdir_observable=false ;;
esac
rmdir "$probe"

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

# Runs the script, capturing combined output in $OUTPUT and the exit code in $STATUS.
run_script() {
  rm -f "${SANDBOX}/reached-out.log"
  OUTPUT="$(bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}

assert_fails_with() {
  local desc="$1" expected="$2"
  shift 2
  run_script "$@"
  if [[ "$STATUS" -eq 0 ]]; then
    fail "$desc: expected non-zero exit, got 0"
  elif [[ "$OUTPUT" != *"$expected"* ]]; then
    fail "$desc: expected output to contain [$expected], got [$OUTPUT]"
  else
    pass "$desc"
  fi
}

assert_no_reach_out() {
  local desc="$1"
  if [[ -f "${SANDBOX}/reached-out.log" ]]; then
    fail "$desc: script reached out — $(cat "${SANDBOX}/reached-out.log")"
  else
    pass "$desc"
  fi
}

# --- argument handling --------------------------------------------------------
assert_fails_with "unknown flag is rejected" "unknown option --bogus" 5.0 --bogus
assert_fails_with "a second positional argument is rejected" "unexpected argument" 5.0 6.0
assert_fails_with "missing version is rejected" "<version> is required"

run_script --help
if [[ "$STATUS" -eq 0 && "$OUTPUT" == *"Usage: install-cassandra-version"* ]]; then
  pass "--help exits 0 with usage"
else
  fail "--help should exit 0 with usage, got status ${STATUS}"
fi

# --- already installed is a no-op --------------------------------------------
mkdir -p "${CASSANDRA_INSTALL_DIR}/5.0"
run_script 5.0
if [[ "$STATUS" -eq 0 && "$OUTPUT" == *"already installed"* ]]; then
  pass "an installed version exits 0 without reinstalling"
else
  fail "an installed version should exit 0 as a no-op, got status ${STATUS}: ${OUTPUT}"
fi
assert_no_reach_out "an installed version downloads nothing"

# --- building from source needs both a repo and a JDK ------------------------
assert_fails_with "a branch with no url is rejected" "requires both --url and --branch" \
  from-source --branch trunk --java 17
assert_no_reach_out "a branch with no url clones nothing"

assert_fails_with "building from source with no --java is rejected" "requires --java" \
  from-source --url https://github.com/apache/cassandra.git --branch trunk
assert_no_reach_out "building with no --java clones nothing"

# --- a branch cannot be checked out of a tarball ------------------------------
assert_fails_with "a branch against a tarball url is rejected, not silently ignored" \
  "cannot be used with a tarball" \
  from-tarball --url https://example.com/apache-cassandra-x-bin.tar.gz --branch trunk --java 17
assert_no_reach_out "a branch against a tarball url downloads nothing"

# --- JDK selection comes from --java, and never switches the node's default ---
assert_fails_with "an unavailable JDK is reported, not silently substituted" \
  "No JDK found on this node for java version 99" \
  from-source --url https://github.com/apache/cassandra.git --branch trunk --java 99
assert_no_reach_out "an unavailable JDK clones nothing"

if grep -q "update-java-alternatives" "$SCRIPT"; then
  fail "the script must never switch the node's default JDK (update-java-alternatives)"
else
  pass "the script never switches the node's default JDK"
fi

# --- a failed install leaves no working directory behind ---------------------
# The working directory holds the git clone, so a failed build would otherwise strand the
# credentialed remote in <workdir>/<version>/.git/config for the life of the node.
run_script leftovers --url https://example.com/apache-cassandra-leftovers-bin.tar.gz --java 11
if [[ "$STATUS" -eq 0 ]]; then
  fail "expected the download to fail (curl is stubbed to fail)"
else
  pass "a failing download exits non-zero"
fi

if [[ "$tmpdir_observable" != true ]]; then
  echo "skip - mktemp here ignores TMPDIR (BSD); this assertion runs on the nodes and in CI"
elif [[ -z "$(find "$TMPDIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  pass "a failed install removes its working directory"
else
  fail "a failed install left $(find "$TMPDIR" -mindepth 1 -maxdepth 1) behind"
fi

# --- the config snippet is required, and checked before any download ---------
mv "$CASSANDRA_IN_SH_SNIPPET" "${SANDBOX}/held"
assert_fails_with "a missing cassandra.in.sh snippet is reported" \
  "Missing ${CASSANDRA_IN_SH_SNIPPET}" 4.1
assert_no_reach_out "a missing snippet fails before downloading anything"
mv "${SANDBOX}/held" "$CASSANDRA_IN_SH_SNIPPET"

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
