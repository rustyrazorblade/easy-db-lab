#!/usr/bin/env bash
#
# Unit tests for edl-cassandra-agents.sh — the version derivation and agent selection that
# cassandra.in.sh runs on every Cassandra startup.
#
# The bug these exist for: the old inline sed only matched `apache-cassandra-X.Y.Z.jar`. A
# pre-release jar name such as `apache-cassandra-6.0-alpha3-SNAPSHOT.jar` did not match, so sed
# echoed the whole filename back, that matched no agent case, and the node started with no metrics
# agent and no message. Every jar shape the node holds is asserted here, including the one that
# cannot be parsed at all.
#
# The library is pure — no filesystem, no network, no root — so this just sources it.
#
# Run directly:  ./edl-cassandra-agents.test.sh
# Or via gradle: ./gradlew testCassandraAgentSelection

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=/dev/null
source "${SCRIPT_DIR}/edl-cassandra-agents.sh"

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

assert_version() {
  local jar="$1" expected="$2" actual
  actual="$(edl_cassandra_version_from_jar "$jar")"
  if [[ "$actual" == "$expected" ]]; then
    pass "${jar} -> ${expected}"
  else
    fail "${jar} should give ${expected}, got '${actual}'"
  fi
}

assert_unparseable() {
  local jar="$1" actual status
  actual="$(edl_cassandra_version_from_jar "$jar")"
  status=$?
  if [[ "$status" -eq 0 ]]; then
    fail "${jar} should be rejected, but parsed as '${actual}'"
  elif [[ -n "$actual" ]]; then
    fail "${jar} was rejected but still printed '${actual}' — callers must get nothing to carry forward"
  else
    pass "${jar} is rejected, printing nothing"
  fi
}

# --- version derivation: every jar shape the node actually holds --------------
assert_version "apache-cassandra-3.0.32.jar" "3.0"
assert_version "apache-cassandra-3.11.19.jar" "3.11"
assert_version "apache-cassandra-4.0.21.jar" "4.0"
assert_version "apache-cassandra-4.1.3.jar" "4.1"
assert_version "apache-cassandra-5.0.4.jar" "5.0"
assert_version "apache-cassandra-5.0.10-SNAPSHOT.jar" "5.0"
assert_version "apache-cassandra-6.0-alpha3-SNAPSHOT.jar" "6.0"
assert_version "apache-cassandra-7.0-SNAPSHOT.jar" "7.0"

# --- version derivation: shapes a release or branch build can produce ---------
assert_version "apache-cassandra-6.0.jar" "6.0"
assert_version "apache-cassandra-6.0-beta1.jar" "6.0"
assert_version "apache-cassandra-5.1-rc1.jar" "5.1"
assert_version "apache-cassandra-4.1.9-SNAPSHOT.jar" "4.1"

# The find in cassandra.in.sh passes a full path, not a basename.
assert_version "/usr/local/cassandra/current/lib/apache-cassandra-6.0-alpha3-SNAPSHOT.jar" "6.0"

# --- version derivation: what must NOT be accepted ---------------------------
# The whole point of the fix: an unrecognised name fails instead of being passed through.
assert_unparseable "cassandra-5.0.4.jar"
assert_unparseable "apache-cassandra-thrift-3.11.19.jar"
assert_unparseable "apache-cassandra-clientutil-3.0.32.jar"
assert_unparseable "apache-cassandra-.jar"
assert_unparseable "apache-cassandra-6.jar"
assert_unparseable "apache-cassandra-trunk.jar"
assert_unparseable ""

# --- MCAC/MAAC agent selection -----------------------------------------------
EDL_MAAC_BASE="/opt/management-api"
EDL_MAAC_VERSIONS="4.0 4.1 5.0 6.0 7.0"

for version in 4.0 4.1 5.0 6.0 7.0; do
  expected="/opt/management-api/${version}/datastax-mgmtapi-agent.jar"
  actual="$(edl_maac_agent_jar_for "$version")"
  if [[ "$actual" == "$expected" ]]; then
    pass "MAAC ${version} -> ${expected}"
  else
    fail "MAAC ${version} should give ${expected}, got '${actual}'"
  fi
done

# 6.0 is the release that had no agent at all and is the reason for this change; assert it is
# no longer an empty result.
if [[ -n "$(edl_maac_agent_jar_for 6.0)" ]]; then
  pass "MAAC 6.0 resolves to an agent (the regression this change fixes)"
else
  fail "MAAC 6.0 must resolve to an agent"
fi

for version in 3.0 3.11 5.1 8.0; do
  if edl_maac_agent_jar_for "$version" >/dev/null; then
    fail "MAAC ${version} should report no agent"
  else
    pass "MAAC ${version} reports no agent so the caller can say so"
  fi
done

# --- AxonOps agent selection --------------------------------------------------
assert_axonops() {
  local version="$1" java="$2" expected="$3" actual
  actual="$(edl_axonops_agent_for "$version" "$java")"
  if [[ "$actual" == "$expected" ]]; then
    pass "AxonOps ${version}/jdk${java} -> ${expected}"
  else
    fail "AxonOps ${version}/jdk${java} should give ${expected}, got '${actual}'"
  fi
}

assert_axonops 3.0 8 "3.0-agent"
assert_axonops 3.11 8 "3.11-agent"
assert_axonops 4.0 8 "4.0-agent-jdk8"
assert_axonops 4.0 11 "4.0-agent"
assert_axonops 4.1 1.8 "4.1-agent-jdk8"
assert_axonops 4.1 17 "4.1-agent"
assert_axonops 5.0 11 "5.0-agent-jdk11"
assert_axonops 5.0 17 "5.0-agent-jdk17"

for combo in "5.0 21" "5.1 17" "6.0 21" "7.0 21"; do
  # shellcheck disable=SC2086
  set -- $combo
  if edl_axonops_agent_for "$1" "$2" >/dev/null; then
    fail "AxonOps $1/jdk$2 should report no agent"
  else
    pass "AxonOps $1/jdk$2 reports no agent so the caller can say so"
  fi
done

# --- the library has to run under /bin/sh, not just bash ---------------------
# Cassandra's bin/cassandra is a /bin/sh script, so on a node this is sourced by dash. A bash-only
# construct here does not degrade: dash fails to parse the file and Cassandra will not start at
# all. Exercise the functions through a real /bin/sh so that can never ship again.
POSIX_SH="$(command -v dash || command -v sh)"

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_cassandra_version_from_jar apache-cassandra-6.0-alpha3-SNAPSHOT.jar' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   && [[ "$sh_out" == "6.0" ]]; then
  pass "version derivation works under ${POSIX_SH}"
else
  fail "version derivation must work under ${POSIX_SH}, got: ${sh_out}"
fi

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_cassandra_version_from_jar apache-cassandra-trunk.jar' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   || [[ -z "$sh_out" ]]; then
  if [[ -z "$sh_out" ]]; then
    pass "an unparseable name is rejected under ${POSIX_SH} too"
  else
    fail "an unparseable name printed '${sh_out}' under ${POSIX_SH}"
  fi
else
  fail "an unparseable name printed '${sh_out}' under ${POSIX_SH}"
fi

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_maac_agent_jar_for 6.0; edl_axonops_agent_for 4.0 8' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   && [[ "$sh_out" == *"/opt/management-api/6.0/datastax-mgmtapi-agent.jar"* && "$sh_out" == *"4.0-agent-jdk8"* ]]; then
  pass "agent selection works under ${POSIX_SH}"
else
  fail "agent selection must work under ${POSIX_SH}, got: ${sh_out}"
fi

echo
echo "${tests_run} assertions, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
