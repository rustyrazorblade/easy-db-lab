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

# The JDK argument is always what edl_java_major_version printed, never a hand-written number, so a
# change to the parser's contract breaks these tests instead of silently picking the wrong agent.
# JDK 8 reads as 1, and 4.0/4.1 must still get their jdk8 agent from it.
JDK8="$(edl_java_major_version 'openjdk version "1.8.0_462"')"
JDK11="$(edl_java_major_version 'openjdk version "11.0.28" 2025-07-15')"
JDK17="$(edl_java_major_version 'openjdk version "17.0.16" 2025-07-15')"

assert_axonops 3.0 "$JDK8" "3.0-agent"
assert_axonops 3.11 "$JDK8" "3.11-agent"
assert_axonops 4.0 "$JDK8" "4.0-agent-jdk8"
assert_axonops 4.0 "$JDK11" "4.0-agent"
assert_axonops 4.1 "$JDK8" "4.1-agent-jdk8"
assert_axonops 4.1 "$JDK17" "4.1-agent"
assert_axonops 5.0 "$JDK11" "5.0-agent-jdk11"
assert_axonops 5.0 "$JDK17" "5.0-agent-jdk17"

# --- the agents AxonOps ships -------------------------------------------------
# The refusal message names these, and the list must come from the same mapping as the selection,
# so an agent added to one cannot be missing from the other.
expected_agents="3.0-agent 3.11-agent 4.0-agent-jdk8 4.0-agent 4.1-agent-jdk8 4.1-agent 5.0-agent-jdk11 5.0-agent-jdk17"
actual_agents="$(edl_axonops_agents | tr '\n' ' ')"
actual_agents="${actual_agents% }"
if [[ "$actual_agents" == "$expected_agents" ]]; then
  pass "edl_axonops_agents lists the eight agents AxonOps ships, each once"
else
  fail "edl_axonops_agents should list [${expected_agents}], got [${actual_agents}]"
fi

for agent in $(edl_axonops_agents); do
  found=""
  for v in 3.0 3.11 4.0 4.1 5.0; do
    for j in "$JDK8" "$JDK11" "$JDK17"; do
      if [[ "$(edl_axonops_agent_for "$v" "$j")" == "$agent" ]]; then found=1; fi
    done
  done
  if [[ -n "$found" ]]; then
    pass "listed agent ${agent} is one the selection picks"
  else
    fail "listed agent ${agent} is never picked by edl_axonops_agent_for"
  fi
done

# --- the AxonOps -javaagent option for the agent the CLI chose ---------------
# cassandra.in.sh no longer selects an agent: `cassandra start` writes EDL_AXONOPS_AGENT into the
# systemd drop-in, and this turns it into the option. Empty means no agent, on purpose.
AXON_SANDBOX="$(mktemp -d)"
trap 'rm -rf "$AXON_SANDBOX"' EXIT
mkdir -p "${AXON_SANDBOX}/5.0-agent-jdk17/lib" "${AXON_SANDBOX}/4.1-agent/lib"
# The jar inside is named for the release, never for the directory (no jdk suffix).
touch "${AXON_SANDBOX}/5.0-agent-jdk17/lib/axon-cassandra5.0-agent.jar"

if out="$(edl_axonops_javaagent "" "$AXON_SANDBOX")" && [[ -z "$out" ]]; then
  pass "an empty EDL_AXONOPS_AGENT gives no -javaagent option"
else
  fail "an empty agent should succeed with no output, got '${out}'"
fi

if out="$(edl_axonops_javaagent 5.0-agent-jdk17 "$AXON_SANDBOX")" \
   && [[ "$out" == "-javaagent:${AXON_SANDBOX}/5.0-agent-jdk17/lib/axon-cassandra5.0-agent.jar=/etc/axonops/axon-agent.yml" ]]; then
  pass "a set EDL_AXONOPS_AGENT loads that agent's jar, found by glob"
else
  fail "expected the 5.0-agent-jdk17 jar option, got '${out}'"
fi

if out="$(edl_axonops_javaagent 4.1-agent "$AXON_SANDBOX")"; then
  fail "an agent with no jar should fail, got '${out}'"
elif [[ -n "$out" ]]; then
  fail "an agent with no jar failed but printed '${out}'"
else
  pass "an agent with no jar fails, printing nothing, so the start can refuse"
fi

if sh_out="$("$(command -v dash || command -v sh)" -c '. "$1"; edl_axonops_javaagent 5.0-agent-jdk17 "$2"' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" "$AXON_SANDBOX" 2>&1)" \
   && [[ "$sh_out" == "-javaagent:"*"axon-cassandra5.0-agent.jar="* ]]; then
  pass "the -javaagent option works under /bin/sh"
else
  fail "the -javaagent option must work under /bin/sh, got: ${sh_out}"
fi

# cassandra.in.sh must load only the agent the CLI chose: no selection, no NOTE path.
in_sh="$(grep -v '^[[:space:]]*#' "${SCRIPT_DIR}/../cassandra.in.sh")"
for gone in edl_axonops_agent_for "NOTE: no AxonOps"; do
  if [[ "$in_sh" == *"$gone"* ]]; then
    fail "cassandra.in.sh still has ${gone}"
  else
    pass "cassandra.in.sh has no ${gone}"
  fi
done
if [[ "$in_sh" == *'edl_axonops_javaagent "${EDL_AXONOPS_AGENT:-}"'* ]]; then
  pass "cassandra.in.sh loads the agent named by EDL_AXONOPS_AGENT"
else
  fail "cassandra.in.sh should call edl_axonops_javaagent with EDL_AXONOPS_AGENT"
fi

for combo in "5.0 21" "5.1 17" "6.0 21" "7.0 21"; do
  # shellcheck disable=SC2086
  set -- $combo
  if edl_axonops_agent_for "$1" "$2" >/dev/null; then
    fail "AxonOps $1/jdk$2 should report no agent"
  else
    pass "AxonOps $1/jdk$2 reports no agent so the caller can say so"
  fi
done

# --- JDK major version from the first line of `java -version` ----------------
# JDK 25 prints `version "25"` with no minor part, which the old inline sed did not match: it left
# the whole line in ECL_JAVA_VERSION. JDK 8 reads as 1, and the GC log check relies on that.
assert_java_major() {
  local line="$1" expected="$2" actual
  actual="$(edl_java_major_version "$line")"
  if [[ "$actual" == "$expected" ]]; then
    pass "'${line}' -> ${expected}"
  else
    fail "'${line}' should give ${expected}, got '${actual}'"
  fi
}

assert_java_major 'openjdk version "25" 2025-09-16' "25"
assert_java_major 'openjdk version "25.0.1" 2025-10-21' "25"
assert_java_major 'openjdk version "21.0.8" 2025-07-15' "21"
assert_java_major 'openjdk version "17.0.16" 2025-07-15' "17"
assert_java_major 'openjdk version "11.0.28" 2025-07-15' "11"
assert_java_major 'openjdk version "1.8.0_462"' "1"

if actual="$(edl_java_major_version 'Error: could not find libjava.so')"; then
  fail "a line with no version should be rejected, but parsed as '${actual}'"
elif [[ -n "$actual" ]]; then
  fail "a line with no version was rejected but still printed '${actual}'"
else
  pass "a line with no version is rejected, printing nothing"
fi

# --- GC log on JDK 17 and higher ----------------------------------------------
for major in 17 21 25; do
  if edl_java_writes_gc_log "$major"; then
    pass "JDK ${major} writes the GC log"
  else
    fail "JDK ${major} should write the GC log"
  fi
done

for major in 1 11 ""; do
  if edl_java_writes_gc_log "$major"; then
    fail "JDK '${major}' should not write the GC log"
  else
    pass "JDK '${major}' does not write the GC log"
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

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_axonops_agent_for 4.0 "$(edl_java_major_version "openjdk version \"1.8.0_462\"")"' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   && [[ "$sh_out" == *"4.0-agent-jdk8"* ]]; then
  pass "agent selection works under ${POSIX_SH}"
else
  fail "agent selection must work under ${POSIX_SH}, got: ${sh_out}"
fi

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_axonops_agents' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   && [[ "$(printf '%s\n' "$sh_out" | tr '\n' ' ')" == "${expected_agents} " ]]; then
  pass "the shipped agent list works under ${POSIX_SH}"
else
  fail "the shipped agent list must work under ${POSIX_SH}, got: ${sh_out}"
fi

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_java_major_version "openjdk version \"25\" 2025-09-16"' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   && [[ "$sh_out" == "25" ]]; then
  pass "JDK version parsing works under ${POSIX_SH}"
else
  fail "JDK version parsing must work under ${POSIX_SH}, got: ${sh_out}"
fi

if sh_out="$("$POSIX_SH" -c '. "$1"; edl_java_writes_gc_log 25 && ! edl_java_writes_gc_log 11 && ! edl_java_writes_gc_log ""' _ "${SCRIPT_DIR}/edl-cassandra-agents.sh" 2>&1)" \
   && [[ -z "$sh_out" ]]; then
  pass "the GC log check works under ${POSIX_SH}"
else
  fail "the GC log check must work quietly under ${POSIX_SH}, got: ${sh_out}"
fi

# --- cassandra.in.sh itself has to parse under /bin/sh -----------------------
# The library above is exercised through dash, but the file that sources it never was, and that is
# the file agents get added to. A bashism in it does not degrade to "no metrics": dash fails to
# parse it and Cassandra does not start at all.
if "$POSIX_SH" -n "${SCRIPT_DIR}/../cassandra.in.sh" 2>/dev/null; then
  pass "cassandra.in.sh parses under ${POSIX_SH}"
else
  fail "cassandra.in.sh must parse under ${POSIX_SH}: $("$POSIX_SH" -n "${SCRIPT_DIR}/../cassandra.in.sh" 2>&1)"
fi

# The OTel agent must go on JVM_EXTRA_OPTS, never JVM_OPTS. bin/nodetool sources this file and puts
# $JVM_OPTS on its own java command line, so an agent there starts again for every nodetool,
# sstableloader and cassandra-stress run - each one a fresh agent minting its own service instance.
# nodetool discards JVM_EXTRA_OPTS, which is exactly what an agent wants.
if grep -v '^[[:space:]]*#' "${SCRIPT_DIR}/../cassandra.in.sh" | grep -q 'JVM_OPTS="\$JVM_OPTS.*-javaagent'; then
  fail "a -javaagent is being appended to JVM_OPTS; it belongs on JVM_EXTRA_OPTS"
else
  pass "no -javaagent on JVM_OPTS"
fi

# Every edl_* function cassandra.in.sh calls must exist. It guards each call with `command -v`, so a
# renamed function does not fail the start: the node just loses its GC log or its AxonOps agent with
# no error. Functions cassandra.in.sh defines for itself are left out.
in_sh_code="$(grep -v '^[[:space:]]*#' "${SCRIPT_DIR}/../cassandra.in.sh")"
in_sh_own="$(printf '%s\n' "$in_sh_code" | sed -nE 's/^[[:space:]]*(edl_[a-z0-9_]+)[[:space:]]*\(\).*/\1/p')"
# Anchored so a `_edl_`-prefixed variable such as _edl_opt is not read as a call.
for fn in $(printf '%s\n' "$in_sh_code" | grep -oE '(^|[^_[:alnum:]])edl_[a-z0-9_]+' | grep -oE 'edl_[a-z0-9_]+' | sort -u); do
  if printf '%s\n' "$in_sh_own" | grep -qx "$fn"; then
    continue
  fi
  if declare -F "$fn" >/dev/null; then
    pass "cassandra.in.sh calls ${fn}, which the library defines"
  else
    fail "cassandra.in.sh calls ${fn}, which edl-cassandra-agents.sh does not define"
  fi
done

echo
echo "${tests_run} assertions, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
