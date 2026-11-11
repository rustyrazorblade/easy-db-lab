#!/usr/bin/env bash
#
# Unit tests for the trino-loadtest kit's bin/start.sh.template.
#
# The script stages driver.py in a labelled ConfigMap, runs the driver pod on an app node, streams
# its log and pushes each interval's figures as OTLP JSON gauges to the collector on the control
# node (Mimir has no import API), and fails when the driver exits non-zero (a failed query set or a
# snapshot leak).
#
# kubectl, curl, date and sleep are stubbed (kit-stubs.sh). No cluster, no network.
#
# Run directly:  bash src/test/shell/trino-loadtest-start.test.sh
# Or via gradle: ./gradlew testTrinoLoadtestStartScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT_SRC="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/trino-loadtest"
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
cp "${KIT_SRC}/bin/start.sh.template" "${WORK}/kit/bin/start.sh"
cp "${KIT_SRC}/driver.py" "${WORK}/kit/driver.py"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# Starts a case with an app node and a driver pod that prints two interval lines and exits 0.
start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
  echo "node/app0" > "${STUB_STATE}/app-nodes"
  printf '%s\n' "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  cat > "${WORK}/driver.log" <<'EOF'
trino-loadtest: 4 threads, 20s
[ 10s ] threads: 4 qps: 12.50 rows_s: 1250.00 lat_p50_ms: 80.10 lat_p99_ms: 310.25 err_s: 0.10
[ 20s ] threads: 4 qps: 13.00 rows_s: 1300.00 lat_p50_ms: 75.00 lat_p99_ms: 290.00 err_s: 0.00
[ final ] queries: 255 qps: 12.75
EOF
  sed 's/^/2026-10-15T10:00:00Z /' "${WORK}/driver.log" > "${STUB_STATE}/log.1"
  cp "${WORK}/driver.log" "${STUB_STATE}/full-log"
}

# Runs start.sh with the kit runner's environment; extra VAR=value pairs in "$@" override it.
run_script() {
  (
    cd "${WORK}" &&
      env PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 KIT_NAME=tl1 \
        KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        TARGET_HTTP_URL=http://10.0.0.9:8080 \
        TRINO_LOADTEST_KEYSPACE=ks1 TRINO_LOADTEST_TABLE=t1 TRINO_LOADTEST_QUERIES_FILE=null \
        THREADS=4 DURATION=20 INTERVAL=10 TRACEPARENT=false \
        TRINO_LOADTEST_SNAPSHOT_CHECK_CMD=null TRINO_LOADTEST_SNAPSHOT_CHECK_TIMEOUT_S=null \
        "$@" "${BASH}" "${WORK}/kit/bin/start.sh"
  ) > "${WORK}/out.txt" 2>&1
}

run_call() { grep '^run ' "${STUB_STATE}/kubectl.calls" 2>/dev/null; }

test_pushes_interval_figures_as_otlp_gauges_to_the_collector() {
  start_test "OTLP gauges"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  local call
  for call in "${STUB_STATE}"/curl.[0-9]*; do
    [ -e "${call}" ] || { fail "nothing was pushed"; return; }
    grep -Fqx 'http://10.0.0.5:4318/v1/metrics' "${call}" || fail "push not sent to the collector: $(cat "${call}")"
  done
  if grep -q ':8428' "${STUB_STATE}"/curl.[0-9]*; then
    fail "must not push to the VictoriaMetrics import API"
  fi

  local gauges
  gauges="$(pushed_gauges)"
  for expected in "trino_loadtest_qps tl1 12.5" "trino_loadtest_rows_per_sec tl1 1250" \
    "trino_loadtest_lat_p50_ms tl1 80.1" "trino_loadtest_lat_p99_ms tl1 310.25" \
    "trino_loadtest_errors_per_second tl1 0.1" "trino_loadtest_qps tl1 13"; do
    grep -Fqx "${expected}" <<< "${gauges}" || fail "expected gauge '${expected}', pushed: ${gauges}"
  done
  [ "$(grep -c '^trino_loadtest_qps ' <<< "${gauges}")" -eq 2 ] || fail "the final summary line was pushed: ${gauges}"
}

test_the_driver_pod_is_labelled_and_pinned_to_an_app_node() {
  start_test "labels and placement"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  run_call | grep -Fq -- "--labels=easydblab/kit=tl1,easydblab/role=run" || fail "labels: $(run_call)"
  if grep -q 'easydblab.com/' "${STUB_STATE}/kubectl.calls"; then fail "uses the easydblab.com/ labels"; fi
  local overrides="${STUB_STATE}/overrides.1"
  [ "$(jq -r '.spec.nodeSelector.type' "${overrides}")" = app ] || fail "overrides: $(cat "${overrides}" 2>/dev/null)"
  [ "$(jq -r '.spec.containers[0].volumeMounts[0].mountPath' "${overrides}")" = /driver ] ||
    fail "driver not mounted at /driver: $(cat "${overrides}")"
}

test_the_driver_configmap_carries_the_role_labels() {
  start_test "ConfigMap"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -Fq '# label easydblab/kit=tl1' "${STUB_STATE}/configmap.1" || fail "configmap: $(cat "${STUB_STATE}/configmap.1" 2>/dev/null)"
  grep -Fq '# label easydblab/role=run' "${STUB_STATE}/configmap.1" || fail "configmap role: $(cat "${STUB_STATE}/configmap.1")"
  grep -q '# from-file driver.py=' "${STUB_STATE}/configmap.1" || fail "driver.py not staged: $(cat "${STUB_STATE}/configmap.1")"
  if grep -q 'queries.txt' "${STUB_STATE}/configmap.1"; then fail "staged a queries file that was not given"; fi
}

test_driver_flags_are_passed_as_separate_arguments() {
  start_test "argv"
  run_script TRINO_LOADTEST_SNAPSHOT_CHECK_CMD="nodetool listsnapshots | cat" TRINO_LOADTEST_SNAPSHOT_CHECK_TIMEOUT_S=30 \
    || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  local call
  call="$(run_call)"
  for expected in "--host 10.0.0.9 --port 8080" "--ks ks1 --tbl t1" "--threads 4 --duration 20 --interval 10" \
    "--snapshot-check-cmd nodetool listsnapshots | cat --snapshot-check-timeout-s 30"; do
    grep -Fq -- "${expected}" <<< "${call}" || fail "expected '${expected}' in: ${call}"
  done
  if grep -q -- '--traceparent' <<< "${call}"; then fail "passed --traceparent while it is off"; fi
}

test_unset_optional_args_are_omitted() {
  start_test "unset args"
  run_script TRINO_LOADTEST_KEYSPACE=null TRINO_LOADTEST_TABLE=null || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  if run_call | grep -Eq -- '--ks|--tbl|--snapshot-check|--queries-file|null'; then fail "passed an unset arg: $(run_call)"; fi
}

test_a_failed_driver_fails_the_run() {
  start_test "non-zero exit"
  printf '%s\n' "Running|||" "Failed|||3" > "${STUB_STATE}/state.default"
  run_script && fail "exited 0"
  grep -q "trino-loadtest run failed with exit code 3" "${WORK}/out.txt" || fail "no failure line: $(cat "${WORK}/out.txt")"
}

test_no_app_node_runs_no_pod() {
  start_test "no app node"
  rm -f "${STUB_STATE}/app-nodes"
  run_script && fail "exited 0"
  grep -q "trino-loadtest needs an app node (type=app); this cluster has none" "${WORK}/out.txt" || fail "message: $(cat "${WORK}/out.txt")"
  [ -z "$(run_call)" ] || fail "created a pod: $(run_call)"
}

test_no_target_runs_no_pod() {
  start_test "no target"
  run_script TARGET_HTTP_URL= && fail "exited 0"
  grep -q "TARGET_HTTP_URL" "${WORK}/out.txt" || fail "message: $(cat "${WORK}/out.txt")"
  [ -z "$(run_call)" ] || fail "created a pod: $(run_call)"
}

test_pushes_interval_figures_as_otlp_gauges_to_the_collector
test_the_driver_pod_is_labelled_and_pinned_to_an_app_node
test_the_driver_configmap_carries_the_role_labels
test_driver_flags_are_passed_as_separate_arguments
test_unset_optional_args_are_omitted
test_a_failed_driver_fails_the_run
test_no_app_node_runs_no_pod
test_no_target_runs_no_pod

echo "trino-loadtest start (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
