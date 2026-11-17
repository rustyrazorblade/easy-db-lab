#!/usr/bin/env bash
#
# Unit tests for install-axonops-agent — the node script `cassandra start` runs on every targeted
# node before any Cassandra start, to install the AxonOps agent for the node's release and JDK
# (design D6 of issue 1130). The bake no longer downloads the per-release agents.
#
# The contract with the CLI:
#   exit 0  the agent is installed; the last line of stdout is its name
#   exit 3  AxonOps ships no agent for the release and JDK; stdout lists the agents it ships
#   exit 1  anything else (bad arguments, a failed download)
#
# sudo, apt-cache, apt-get, aws, dpkg and dpkg-deb are stubbed on PATH, and the agent directory is
# redirected into a temp dir, so this needs no root, no network and no Docker.
#
# Run directly:  ./install-axonops-agent.test.sh
# Or via gradle: ./gradlew testCassandraAxonOpsAgentScript

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/install-axonops-agent"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"
CALLS="${SANDBOX}/calls"
export CALLS SANDBOX

cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

cat >"${BIN}/chown" <<'SHIM'
#!/bin/bash
exit 0
SHIM

cat >"${BIN}/dpkg" <<'SHIM'
#!/bin/bash
[[ "${1:-}" == "--print-architecture" ]] && echo amd64
SHIM

# APT_CANDIDATE: the version apt reports; NO_CANDIDATE_UNTIL_UPDATE=1 reports none until an update.
cat >"${BIN}/apt-cache" <<'SHIM'
#!/bin/bash
echo "apt-cache $*" >> "$CALLS"
if [[ -n "${NO_CANDIDATE_UNTIL_UPDATE:-}" && ! -f "$SANDBOX/updated" ]]; then
  echo "N: Unable to locate package $2"
  exit 0
fi
echo "$2:"
echo "  Installed: (none)"
echo "  Candidate: ${APT_CANDIDATE:-1.0.12}"
SHIM

# `apt-get download pkg=ver` writes pkg_ver_arch.deb into the working directory.
cat >"${BIN}/apt-get" <<'SHIM'
#!/bin/bash
echo "apt-get $*" >> "$CALLS"
case "$1" in
  update) touch "$SANDBOX/updated" ;;
  download)
    [[ -n "${APT_DOWNLOAD_FAILS:-}" ]] && exit 100
    pkg="${2%%=*}"; ver="${2#*=}"
    echo deb > "${pkg}_${ver}_all.deb"
    ;;
esac
SHIM

# aws s3 cp: a read of the cache is a miss unless AWS_CACHE_HIT is set.
cat >"${BIN}/aws" <<'SHIM'
#!/bin/bash
echo "aws $*" >> "$CALLS"
if [[ "$1 $2" == "s3 cp" ]]; then
  case "$3" in
    s3://*) [[ -n "${AWS_CACHE_HIT:-}" ]] || exit 1; echo deb > "$4" ;;
  esac
fi
exit 0
SHIM

# dpkg-deb -x <deb> <dir>: unpack the agent jar the way the real package lays it out.
cat >"${BIN}/dpkg-deb" <<'SHIM'
#!/bin/bash
echo "dpkg-deb $*" >> "$CALLS"
mkdir -p "$3/usr/share/axonops"
echo jar > "$3/usr/share/axonops/axon-cassandra-agent.jar"
SHIM

chmod +x "${BIN}"/*
export PATH="${BIN}:${PATH}"

export EDL_AXONOPS_BASE="${SANDBOX}/axonops"
export EDL_AGENTS_LIB="${SCRIPT_DIR}/../lib/edl-cassandra-agents.sh"
export EDL_CACHE_LIB="${SCRIPT_DIR}/../../base/install/edl-cache-lib.sh"
PREFIX="s3://acct-bucket/download-cache/axonops"

tests_run=0
tests_failed=0
pass() { tests_run=$((tests_run + 1)); echo "ok   - $1"; }
fail() { tests_run=$((tests_run + 1)); tests_failed=$((tests_failed + 1)); echo "FAIL - $1"; }

run_script() {
  : > "$CALLS"
  rm -f "$SANDBOX/updated"
  OUTPUT="$(bash "$SCRIPT" "$@" 2>"${SANDBOX}/stderr")"
  STATUS=$?
  ERR="$(cat "${SANDBOX}/stderr")"
}

reset_agents() { rm -rf "$EDL_AXONOPS_BASE"; }

# --- arguments ----------------------------------------------------------------
run_script
if [[ "$STATUS" -eq 1 && "$ERR$OUTPUT" == *"Usage: install-axonops-agent"* ]]; then
  pass "no arguments prints the usage and exits 1"
else
  fail "no arguments should exit 1 with the usage, got ${STATUS}: ${OUTPUT} ${ERR}"
fi

run_script 5.0 17 --cache-prefix
if [[ "$STATUS" -eq 1 && "$ERR" == *"--cache-prefix needs a value"* && ! -s "$CALLS" ]]; then
  pass "--cache-prefix with no value exits 1 instead of looping"
else
  fail "--cache-prefix with no value should exit 1, got ${STATUS}: ${OUTPUT} ${ERR}"
fi

run_script 5.0 17
if [[ "$STATUS" -eq 1 && "$ERR" == *"--cache-prefix is required"* && ! -s "$CALLS" ]]; then
  pass "a missing --cache-prefix is refused before any lookup"
else
  fail "a missing --cache-prefix should exit 1 with nothing run, got ${STATUS}: ${ERR}; calls: $(cat "$CALLS")"
fi

# --- no agent: exit 3 with the shipped list, nothing fetched -------------------
run_script 5.0 21 --cache-prefix "$PREFIX"
shipped="$(printf '%s\n' "$OUTPUT" | tr '\n' ' ')"
if [[ "$STATUS" -eq 3 && "$shipped" == *"4.1-agent "* && "$shipped" == *"5.0-agent-jdk17 "* ]]; then
  pass "5.0 on JDK 21 exits 3 and lists the agents AxonOps ships"
else
  fail "5.0 on JDK 21 should exit 3 with the shipped list, got ${STATUS}: ${OUTPUT}"
fi
if [[ "$ERR" == *"5.0"* && "$ERR" == *"JDK 21"* ]]; then
  pass "the refusal names the release and the JDK"
else
  fail "the refusal should name 5.0 and JDK 21, got: ${ERR}"
fi
if [[ ! -s "$CALLS" ]]; then
  pass "no agent means no apt lookup and no download"
else
  fail "no agent should reach nothing, calls: $(cat "$CALLS")"
fi

run_script 6.0 21 --cache-prefix "$PREFIX"
if [[ "$STATUS" -eq 3 ]]; then
  pass "a release AxonOps does not cover exits 3"
else
  fail "6.0 should exit 3, got ${STATUS}"
fi

# --- JDK 8 is passed as 8; the script maps it to the library's 1 --------------
reset_agents
run_script 4.1 8 --cache-prefix "$PREFIX"
if [[ "$STATUS" -eq 0 && "$(printf '%s\n' "$OUTPUT" | tail -n 1)" == "4.1-agent-jdk8" ]]; then
  pass "4.1 on JDK 8 installs 4.1-agent-jdk8"
else
  fail "4.1 on JDK 8 should install 4.1-agent-jdk8, got ${STATUS}: ${OUTPUT} ${ERR}"
fi

# --- a cache miss: apt-get download, unpack, write back with the version in the key ---
reset_agents
APT_CANDIDATE=1.0.12 run_script 5.0 17 --cache-prefix "$PREFIX"
key="${PREFIX}/axon-cassandra5.0-agent-jdk17/1.0.12/axon-cassandra5.0-agent-jdk17_1.0.12_amd64.deb"
if [[ "$STATUS" -eq 0 && "$(printf '%s\n' "$OUTPUT" | tail -n 1)" == "5.0-agent-jdk17" ]]; then
  pass "a miss installs the agent and prints its name last"
else
  fail "a miss should succeed printing 5.0-agent-jdk17 last, got ${STATUS}: ${OUTPUT} ${ERR}"
fi
if grep -q "apt-get download axon-cassandra5.0-agent-jdk17=1.0.12" "$CALLS"; then
  pass "a miss downloads the candidate version with apt-get download"
else
  fail "expected apt-get download of the candidate, calls: $(cat "$CALLS")"
fi
if grep -qF "aws s3 cp ${key}" "$CALLS" && grep -qE "aws s3 cp [^ ]*\.deb ${key}" "$CALLS"; then
  pass "the cache key holds the package and its version, and the miss writes it back"
else
  fail "expected a read and a write of ${key}, calls: $(cat "$CALLS")"
fi
if [[ -f "${EDL_AXONOPS_BASE}/5.0-agent-jdk17/lib/axon-cassandra-agent.jar" ]]; then
  pass "the jar lands in the agent's lib directory"
else
  fail "the jar should be under ${EDL_AXONOPS_BASE}/5.0-agent-jdk17/lib"
fi

# --- already installed: no-op --------------------------------------------------
run_script 5.0 17 --cache-prefix "$PREFIX"
if [[ "$STATUS" -eq 0 && "$OUTPUT" == "5.0-agent-jdk17" && ! -s "$CALLS" ]]; then
  pass "an installed agent prints its name and fetches nothing"
else
  fail "an installed agent should be a no-op, got ${STATUS}: ${OUTPUT}; calls: $(cat "$CALLS")"
fi

# --- a cache hit: no apt-get download, no write-back ---------------------------
reset_agents
AWS_CACHE_HIT=1 run_script 5.0 11 --cache-prefix "$PREFIX"
if [[ "$STATUS" -eq 0 ]] && ! grep -q "apt-get download" "$CALLS" && ! grep -qE "aws s3 cp [^ ]*\.deb s3://" "$CALLS"; then
  pass "a cache hit installs from the cache with no apt download and no write-back"
else
  fail "a hit should not download or write back, got ${STATUS}; calls: $(cat "$CALLS")"
fi

# --- a node with no apt lists updates them once (the image is baked with none) -
reset_agents
NO_CANDIDATE_UNTIL_UPDATE=1 run_script 4.1 11 --cache-prefix "$PREFIX"
if [[ "$STATUS" -eq 0 ]] && grep -q "apt-get update" "$CALLS"; then
  pass "with no apt lists, it updates them and then finds the candidate"
else
  fail "expected an apt-get update then success, got ${STATUS}: ${ERR}; calls: $(cat "$CALLS")"
fi

# --- a failed download fails, names the package, leaves no partial agent ------
reset_agents
APT_DOWNLOAD_FAILS=1 run_script 4.1 11 --cache-prefix "$PREFIX"
if [[ "$STATUS" -eq 1 && "$ERR" == *"axon-cassandra4.1-agent"* ]]; then
  pass "a failed download exits 1 and names the package"
else
  fail "a failed download should exit 1 naming the package, got ${STATUS}: ${ERR}"
fi
if [[ ! -d "${EDL_AXONOPS_BASE}/4.1-agent" ]]; then
  pass "a failed download leaves no agent directory behind"
else
  fail "a failed download left ${EDL_AXONOPS_BASE}/4.1-agent"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
