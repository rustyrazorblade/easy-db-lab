#!/usr/bin/env bash
#
# Unit tests for the bake-time loop's version resolution in install_cassandra.sh — which flags
# each declared version turns into, and which versions are skipped entirely.
#
# install_cassandra.sh returns immediately when INSTALL_CASSANDRA is unset, so sourcing it gives
# the functions with none of the effects. No network, no Docker, no root.
#
# Run directly:  ./install_cassandra.test.sh
# Or via gradle: ./gradlew testCassandraInstallLoop

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v yq >/dev/null 2>&1; then
  echo "SKIP - yq is not installed; the bake loop's version resolution needs it"
  exit 0
fi

FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_DIR"' EXIT

export YAML="${FIXTURE_DIR}/cassandra_versions.yaml"
cat >"$YAML" <<'YAMLFIXTURE'
- version: "5.0"
  java: "11"
  python: "3.11.9"

- version: "4.0"
  java: "11"
  python: "3.11.9"
  ant_flags: "-Duse.jdk11=true -Dno.tests=true"

- version: "nightly"
  url: https://example.com/apache-cassandra-nightly-bin.tar.gz
  java: "21"
  python: "3.11.9"

- version: "cep-45"
  url: https://github.com/apache/cassandra.git
  branch: cep-45
  java: "17"
  python: "3.11.9"
  lazy: true
YAMLFIXTURE

# shellcheck source=packer/cassandra/install/install_cassandra.sh
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/install_cassandra.sh"

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

# Asserts the flags a version resolves to, compared as a single space-joined string.
assert_args() {
  local desc="$1" version="$2" expected="$3"
  version_install_args "$version"
  local actual="${INSTALL_ARGS[*]}"
  if [[ "$actual" == "$expected" ]]; then
    pass "$desc"
  else
    fail "$desc: expected [$expected], got [$actual]"
  fi
}

assert_lazy() {
  local desc="$1" version="$2"
  if version_is_lazy "$version"; then pass "$desc"; else fail "$desc: expected lazy"; fi
}

assert_not_lazy() {
  local desc="$1" version="$2"
  if version_is_lazy "$version"; then fail "$desc: expected not lazy"; else pass "$desc"; fi
}

# --- an official release carries only its JDK ---------------------------------
assert_args "official release resolves to version + java" 5.0 "5.0 --java 11"

# --- ant flags with spaces stay one argument ----------------------------------
version_install_args 4.0
if [[ "${#INSTALL_ARGS[@]}" -eq 5 && "${INSTALL_ARGS[4]}" == "-Duse.jdk11=true -Dno.tests=true" ]]; then
  pass "multi-word ant_flags stays a single argument"
else
  fail "multi-word ant_flags split into ${#INSTALL_ARGS[@]} args: ${INSTALL_ARGS[*]}"
fi

# --- tarball and git-branch entries both round-trip their fields --------------
assert_args "tarball entry resolves its url" nightly \
  "nightly --url https://example.com/apache-cassandra-nightly-bin.tar.gz --java 21"
assert_args "git-branch entry resolves url and branch" cep-45 \
  "cep-45 --url https://github.com/apache/cassandra.git --branch cep-45 --java 17"

# --- lazy entries are skipped at bake time, others are not --------------------
assert_lazy "a lazy entry is skipped at bake time" cep-45
assert_not_lazy "an ordinary entry is not skipped" 5.0
assert_not_lazy "a tarball entry is not skipped" nightly

# --- sourcing must not have run any of the install work ----------------------
if [[ -d /usr/local/cassandra/cep-45 ]]; then
  fail "sourcing install_cassandra.sh installed something"
else
  pass "sourcing install_cassandra.sh has no side effects"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
