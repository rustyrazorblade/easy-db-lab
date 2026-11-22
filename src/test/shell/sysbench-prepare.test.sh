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

# The cluster namespace, as easy-db-lab sets it for every kit process (TemplateVariables).
export NAMESPACE=lab-a

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEMPLATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/bin/prepare.sh.template"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
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
      PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" SYSBENCH_IMAGE=test.example/sysbench@sha256:abc APP_NODE_LABELS=type=app APP_NODE_SELECTOR='{"type":"app"}' CONTROL_NODE_LABELS=type=control EDL_POLL_SECONDS=0 KIT_NAME=sysbench-tidb KUBECONFIG="${WORK}/kubeconfig" \
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

# The sysbench pods run SYSBENCH_IMAGE, with sysbench a child of `sh -c`, never the container's
# PID 1: an emulated sysbench that segfaulted as PID 1 left its pod Running until the wait timed out.
test_sysbench_pods_run_the_image_under_sh() {
  start_test "image and sh wrapper"
  run_script || { fail "exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  local n args
  for n in 2 3; do
    args="$(cat "${STUB_STATE}/run-args.${n}")"
    grep -Fqx -- "--image=test.example/sysbench@sha256:abc" <<< "${args}" || fail "pod ${n} does not run SYSBENCH_IMAGE: ${args}"
    [ "$(sed -n '/^--$/,$p' <<< "${args}" | sed -n '2,5p' | tr '\n' ' ')" = "sh -c sysbench \"\$@\"; exit \$? sysbench " ] ||
      fail "pod ${n} does not run sysbench under sh: ${args}"
  done
}

# The wrapper the pods run, run by a real sh, with a sysbench that dies of SIGSEGV: the shell exits
# with 139 at once, so the pod reports a failed exit code, and with 0 when sysbench succeeds. (That
# sysbench is not PID 1 cannot be seen outside a container; the case above pins the wrapper's form.)
test_the_sh_wrapper_exits_with_the_status_of_a_crashed_sysbench() {
  start_test "wrapper exit status"
  local bin="${WORK}/crash-bin" wrapper rc
  mkdir -p "${bin}"
  printf '#!/bin/sh\nkill -SEGV $$\n' > "${bin}/sysbench"
  chmod +x "${bin}/sysbench"
  wrapper="$(sed -n "s/^SYSBENCH=(sh -c '\(.*\)' sysbench)$/\1/p" "${WORK}/kit/bin/prepare.sh")"
  [ -n "${wrapper}" ] || { fail "no SYSBENCH wrapper line in prepare.sh"; return; }
  PATH="${bin}:${PATH}" /bin/sh -c "${wrapper}" sysbench --tables=1 oltp_read_write cleanup 2> /dev/null
  rc=$?
  [ "${rc}" -eq 139 ] || fail "exited ${rc}, not 139"
  printf '#!/bin/sh\nexit 0\n' > "${bin}/sysbench"
  PATH="${bin}:${PATH}" /bin/sh -c "${wrapper}" sysbench run || fail "a sysbench that exits 0 made the wrapper fail"
}

test_every_pod_is_pinned_and_a_quick_pod_does_not_wait
test_sysbench_pods_run_the_image_under_sh
test_the_sh_wrapper_exits_with_the_status_of_a_crashed_sysbench
test_a_failed_pod_fails_prepare
test_no_app_node_fails_before_any_pod

echo "sysbench prepare (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
