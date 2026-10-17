#!/usr/bin/env bash
#
# Unit tests for the sysbench kit's bin/prepare.sh.template.
#
# prepare runs three pods in turn (createdb for a MySQL target, cleanup, prepare), each on an app
# node, and waits for each to end. A pod that ends in a second must not make it wait, and a pod that
# exits non-zero must fail it. kubectl, curl, date and sleep are stubbed (kit-stubs.sh).
#
# Run directly:  bash src/test/shell/sysbench-prepare.test.sh
# Or via gradle: ./gradlew testSysbenchPrepareScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEMPLATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/bin/prepare.sh.template"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
KIT_LIB_DIR="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/bin/lib"
# shellcheck source=kit-stubs.sh
. "${ROOT}/src/test/shell/kit-stubs.sh"

tests_run=0
tests_failed=0
current_test=""
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export STUB_STATE="${WORK}/state"
write_kit_stubs "${WORK}/stubs"
mkdir -p "${WORK}/kit/bin"
cp "${TEMPLATE}" "${WORK}/kit/bin/prepare.sh"
cp -R "${KIT_LIB_DIR}" "${WORK}/kit/bin/lib"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# Starts a case with an app node and pods that are already Succeeded on their first poll.
start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
  echo "node/app0" > "${STUB_STATE}/app-nodes"
  echo "Succeeded|||0" > "${STUB_STATE}/state.default"
}

# Runs prepare.sh against a MySQL target.
run_script() {
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 KIT_NAME=sysbench-tidb KUBECONFIG="${WORK}/kubeconfig" \
        TARGET_MYSQL_HOST=10.0.0.9 TARGET_MYSQL_PORT=4000 TARGET_MYSQL_USER=root TARGET_MYSQL_DATABASE=sbtest \
        WORKLOAD=oltp_read_write SCALE=10 TABLES=10 \
        "${BASH}" "${WORK}/kit/bin/prepare.sh"
  ) > "${WORK}/out.txt" 2>&1
}

run_calls() { grep ' run ' "${STUB_STATE}/kubectl.calls" 2>/dev/null; }

test_every_pod_is_pinned_and_a_quick_pod_does_not_wait() {
  start_test "pods pinned, Succeeded on the first poll"
  run_script || { fail "exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  [ "$(run_calls | wc -l | tr -d ' ')" -eq 3 ] || fail "expected createdb, cleanup and prepare pods: $(run_calls)"
  local n role
  for n in 1 2 3; do
    [ "$(jq -r '.spec.nodeSelector.type' "${STUB_STATE}/overrides.${n}")" = app ] || fail "pod ${n} is not pinned: $(run_calls)"
  done
  for role in sysbench-createdb sysbench-cleanup sysbench-prepare; do
    run_calls | grep -Fq -- "--labels=easydblab/kit=sysbench-tidb,easydblab/role=${role}" || fail "no ${role} pod: $(run_calls)"
  done
  # One poll to see it has left Pending and one to read its exit code, for each pod.
  [ "$(grep -c '^get pod ' "${STUB_STATE}/kubectl.calls")" -eq 6 ] || fail "polled more than needed: $(cat "${STUB_STATE}/kubectl.calls")"
  grep -q "Data preparation complete." "${WORK}/out.txt" || fail "output: $(cat "${WORK}/out.txt")"
}

test_a_failed_pod_fails_prepare() {
  start_test "non-zero pod exit"
  echo "Failed|||1" > "${STUB_STATE}/state.cleanup"
  echo "ERROR: cleanup failed" > "${STUB_STATE}/full-log"
  run_script && fail "exited 0"
  grep -Eq "pod sysbench-cleanup-sysbench-tidb-[0-9]+ failed with exit code 1" "${WORK}/out.txt" || fail "message: $(cat "${WORK}/out.txt")"
  grep -q "ERROR: cleanup failed" "${WORK}/out.txt" || fail "did not print the pod's log: $(cat "${WORK}/out.txt")"
  if run_calls | grep -q "role=sysbench-prepare"; then fail "went on to the prepare pod"; fi
}

test_no_app_node_fails_before_any_pod() {
  start_test "no app node"
  rm -f "${STUB_STATE}/app-nodes"
  run_script && fail "exited 0"
  grep -q "sysbench needs an app node (type=app); this cluster has none" "${WORK}/out.txt" || fail "message: $(cat "${WORK}/out.txt")"
  [ -z "$(run_calls)" ] || fail "created a pod: $(run_calls)"
}

test_every_pod_is_pinned_and_a_quick_pod_does_not_wait
test_a_failed_pod_fails_prepare
test_no_app_node_fails_before_any_pod

echo "sysbench prepare (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
