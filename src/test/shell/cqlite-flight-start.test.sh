#!/usr/bin/env bash
#
# Unit tests for the cqlite-flight kit's bin/start.sh.template.
#
# The script stages pod/detect-multidisk.sh in a labelled ConfigMap, applies the DaemonSet and
# waits for its rollout. A rollout that fails prints a hint naming the host-path args and the
# multi-disk note (pmcfadin/cqlite#2114), after naming the --rollout-timeout it waited and a slow
# image pull as a cause; a healthy start prints neither. The wait uses --rollout-timeout.
#
# kubectl is stubbed (kit-stubs.sh). No cluster.
#
# Run directly:  bash src/test/shell/cqlite-flight-start.test.sh
# Or via gradle: ./gradlew testCqliteFlightStartScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT_SRC="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-flight"
# shellcheck source=kit-stubs.sh
. "${ROOT}/src/test/shell/kit-stubs.sh"

tests_run=0
tests_failed=0
current_test=""
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export STUB_STATE="${WORK}/state"
write_kit_stubs "${WORK}/stubs"
mkdir -p "${WORK}/kit/bin" "${WORK}/kit/pod"
# Renders the template the way the kit installer does, with fixed install-time values.
sed -e 's|__KUBECONFIG__|kubeconfig|g' -e 's|__TAG__|v0.18.0|g' -e 's|__FLIGHT_PORT__|9915|g' \
  -e 's|__CASSANDRA_DATA_DIR__|/mnt/db1/cassandra/data|g' -e 's|__CASSANDRA_DATA_ROOT__|/mnt|g' \
  -e 's|__CASSANDRA_DATA_GID__|999|g' -e 's|__ROLLOUT_TIMEOUT_SECONDS__|420|g' \
  "${KIT_SRC}/bin/start.sh.template" > "${WORK}/kit/bin/start.sh"
cp "${KIT_SRC}/pod/detect-multidisk.sh" "${WORK}/kit/pod/"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
}

# Runs start.sh; its stdout and stderr go to out.txt, and it returns the script's exit status.
run_script() {
  env PATH="${WORK}/stubs:${PATH}" "${BASH}" "${WORK}/kit/bin/start.sh" > "${WORK}/out.txt" 2>&1
}

test_a_healthy_rollout_prints_no_multi_disk_note() {
  start_test "healthy rollout"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -Fq "cqlite-flight is ready" "${WORK}/out.txt" || fail "no ready line: $(cat "${WORK}/out.txt")"
  grep -Eq "^create configmap cqlite-flight-detect-multidisk .*--from-file=detect-multidisk.sh=\S*/pod/detect-multidisk.sh( |$)" \
    "${STUB_STATE}/kubectl.calls" || fail "the detector ConfigMap is not made from the packaged script: $(cat "${STUB_STATE}/kubectl.calls")"
  grep -Eq "^rollout status daemonset/cqlite-flight .*--timeout=420s( |$)" "${STUB_STATE}/kubectl.calls" ||
    fail "the rollout wait does not use --rollout-timeout: $(cat "${STUB_STATE}/kubectl.calls")"
  grep -Fq "label --local -f - easydblab/kit=cqlite-flight easydblab/role=flight" "${STUB_STATE}/kubectl.calls" ||
    fail "the detector ConfigMap lacks the labels stop deletes by: $(cat "${STUB_STATE}/kubectl.calls")"
  if grep -Eq "2114|multi-disk|detect-multidisk" "${WORK}/out.txt"; then fail "printed the multi-disk note on a healthy start: $(cat "${WORK}/out.txt")"; fi
}

test_a_failed_rollout_fails_with_the_host_path_hint_and_the_multi_disk_note() {
  start_test "failed rollout"
  echo 1 > "${STUB_STATE}/rollout-exit"
  if run_script; then fail "script exited 0 on a failed rollout"; return; fi
  local want
  for want in "--data-dir (/mnt/db1/cassandra/data)" "--data-root" "(/mnt)" "--data-gid (999)" \
    "hostPath type check failed" "#2114" "-c detect-multidisk --prefix" \
    "within 420s" "ghcr.io/pmcfadin/cqlite-flight:v0.18.0" "Pulling image" "--rollout-timeout. Otherwise"; do
    grep -Fq -- "${want}" "${WORK}/out.txt" || fail "no '${want}' in: $(cat "${WORK}/out.txt")"
  done
  if grep -Fq "cqlite-flight is ready" "${WORK}/out.txt"; then fail "claimed ready after a failed rollout"; fi
}

test_a_healthy_rollout_prints_no_multi_disk_note
test_a_failed_rollout_fails_with_the_host_path_hint_and_the_multi_disk_note

echo "cqlite-flight start: ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
