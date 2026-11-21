#!/usr/bin/env bash
#
# Unit tests for edl-cassandra-config.sh, the one cassandra.yaml merge the VM path (patch-config)
# and the cassandra-pods kit's render step share, and for patch-config's contract with
# UpdateConfig: the "Successfully patched" marker and every exit code.
#
# The library is sourced by a real /bin/sh (dash where present): the kit's render step runs it
# under sh, so a bashism would break every pod start.
#
# yq is the real binary (it is what the merge is); sudo is a stub that runs its arguments, so no
# root is needed. patch-config reads its install root from EDL_CASSANDRA_HOME and the library from
# EDL_CASSANDRA_CONFIG_LIB, both defaulting to the AMI paths.
#
# Run directly:  ./edl-cassandra-config.test.sh
# Or via gradle: ./gradlew testCassandraConfigMerge

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${SCRIPT_DIR}/edl-cassandra-config.sh"
PATCH_CONFIG="${SCRIPT_DIR}/../bin/patch-config"
POSIX_SH="$(command -v dash || command -v sh)"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

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

cat >"${SANDBOX}/orig.yaml" <<'EOF'
cluster_name: Test Cluster
concurrent_reads: 32
concurrent_writes: 32
endpoint_snitch: SimpleSnitch
data_file_directories:
  - /var/lib/cassandra/data
EOF
cat >"${SANDBOX}/a.yaml" <<'EOF'
concurrent_reads: 64
cluster_name: lab
EOF
cat >"${SANDBOX}/b.yaml" <<'EOF'
concurrent_reads: 128
endpoint_snitch: GossipingPropertyFileSnitch
EOF

# --- patches merge in order, under a real /bin/sh -----------------------------
out="${SANDBOX}/out.yaml"
if sh_out="$("$POSIX_SH" -c '. "$1"; edl_merge_cassandra_yaml "$2" "$3" "$4" "$5"' _ \
  "$LIB" "${SANDBOX}/orig.yaml" "$out" "${SANDBOX}/a.yaml" "${SANDBOX}/b.yaml" 2>&1)"; then
  pass "the merge succeeds under ${POSIX_SH}"
else
  fail "the merge must succeed under ${POSIX_SH}, got: ${sh_out}"
fi

assert_key() {
  local key="$1" expected="$2" actual
  actual="$(yq "$key" "$out" 2>/dev/null)"
  if [[ "$actual" == "$expected" ]]; then
    pass "${key} is ${expected}"
  else
    fail "${key} should be ${expected}, got '${actual}'"
  fi
}

assert_key '.concurrent_reads' "128"
assert_key '.cluster_name' "lab"
assert_key '.endpoint_snitch' "GossipingPropertyFileSnitch"
assert_key '.concurrent_writes' "32"
assert_key '.data_file_directories[0]' "/var/lib/cassandra/data"

# The original is never changed: the kit renders from a cached tree nothing may modify.
if [[ "$(yq '.concurrent_reads' "${SANDBOX}/orig.yaml")" == "32" ]]; then
  pass "the original cassandra.yaml is left as it was"
else
  fail "the merge changed the original cassandra.yaml"
fi

# --- a broken patch fails and leaves no output --------------------------------
printf 'concurrent_reads: [unclosed\n' >"${SANDBOX}/bad.yaml"
rm -f "${SANDBOX}/bad-out.yaml"
if "$POSIX_SH" -c '. "$1"; edl_merge_cassandra_yaml "$2" "$3" "$4"' _ \
  "$LIB" "${SANDBOX}/orig.yaml" "${SANDBOX}/bad-out.yaml" "${SANDBOX}/bad.yaml" >/dev/null 2>&1; then
  fail "a patch with broken YAML should fail the merge"
elif [[ -e "${SANDBOX}/bad-out.yaml" ]]; then
  fail "a failed merge left a partial output file"
else
  pass "a patch with broken YAML fails and leaves no output"
fi

# --- a missing patch fails ----------------------------------------------------
if "$POSIX_SH" -c '. "$1"; edl_merge_cassandra_yaml "$2" "$3" "$4"' _ \
  "$LIB" "${SANDBOX}/orig.yaml" "${SANDBOX}/x.yaml" "${SANDBOX}/missing.yaml" >/dev/null 2>&1; then
  fail "a missing patch file should fail the merge"
else
  pass "a missing patch file fails the merge"
fi

# --- patch-config keeps its contract with UpdateConfig ------------------------
STUB_DIR="${SANDBOX}/stubs"
mkdir -p "$STUB_DIR"
printf '#!/bin/sh\nif [ "$1" = chown ]; then exit 0; fi\nexec "$@"\n' >"${STUB_DIR}/sudo"
chmod +x "${STUB_DIR}/sudo"

HOME_DIR="${SANDBOX}/cassandra"
mkdir -p "${HOME_DIR}/current/conf.orig" "${HOME_DIR}/current/conf"
cp "${SANDBOX}/orig.yaml" "${HOME_DIR}/current/conf.orig/cassandra.yaml"

run_patch_config() {
  PATH="${STUB_DIR}:${PATH}" EDL_CASSANDRA_HOME="$HOME_DIR" EDL_CASSANDRA_CONFIG_LIB="$LIB" \
    bash "$PATCH_CONFIG" "$@" 2>&1
}

output="$(run_patch_config "${SANDBOX}/b.yaml")"
status=$?
if [[ "$status" -eq 0 && "$output" == *"Successfully patched ${HOME_DIR}/current/conf/cassandra.yaml"* ]]; then
  pass "patch-config prints the success marker and exits 0"
else
  fail "patch-config: status ${status}, output [${output}]"
fi
if [[ "$(yq '.concurrent_reads' "${HOME_DIR}/current/conf/cassandra.yaml")" == "128" ]]; then
  pass "patch-config writes the merged config"
else
  fail "patch-config did not write the merged config"
fi

assert_exit() {
  local expected="$1" label="$2"
  shift 2
  local status
  run_patch_config "$@" >/dev/null
  status=$?
  if [[ "$status" -eq "$expected" ]]; then
    pass "patch-config exits ${expected} when ${label}"
  else
    fail "patch-config should exit ${expected} when ${label}, got ${status}"
  fi
}

assert_exit 1 "no patch file is given"
assert_exit 2 "the patch file is missing" "${SANDBOX}/missing.yaml"
assert_exit 4 "the version has no original config" "${SANDBOX}/b.yaml" "9.9"
assert_exit 5 "the patch is broken YAML" "${SANDBOX}/bad.yaml"

# patch-config must not carry a merge of its own.
if grep -v '^[[:space:]]*#' "$PATCH_CONFIG" | grep -q 'load(env'; then
  fail "patch-config still merges cassandra.yaml itself"
else
  pass "patch-config merges only through edl_merge_cassandra_yaml"
fi

echo
echo "${tests_run} assertions, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
