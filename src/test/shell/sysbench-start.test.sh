#!/usr/bin/env bash
#
# Unit tests for the sysbench kit's bin/start.sh.template.
#
# The script runs its sysbench pod on an app node, streams the run's log and pushes each interval's
# figures, and the whole-run percentiles, as OTLP JSON gauges to the collector on the control node.
# Going through the collector is what gives the series their cluster label and tenant; Mimir has no
# import API. It then reads the run pod's exit code and fails when sysbench failed.
#
# kubectl, curl, date and sleep are stubbed (kit-stubs.sh): kubectl reports the run pod's phases
# and prints a canned sysbench log, curl records every call. No cluster, no network.
#
# Run directly:  bash src/test/shell/sysbench-start.test.sh
# Or via gradle: ./gradlew testSysbenchStartScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEMPLATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/bin/start.sh.template"
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
cp "${TEMPLATE}" "${WORK}/kit/bin/start.sh"
cp -R "${KIT_LIB_DIR}" "${WORK}/kit/bin/lib"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# Starts a case with an app node and a run pod that streams a canned sysbench log and exits 0.
start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
  echo "node/app0" > "${STUB_STATE}/app-nodes"
  printf '%s\n' "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  cat > "${WORK}/sysbench.log" <<'EOF'
Running the test with following options:
[ 10s ] thds: 4 tps: 120.50 qps: 2410.00 (r/w/o: 1687.00/482.00/241.00) lat (ms,99%): 45.79 err/s: 0.10 reconn/s: 0.00
[ 20s ] thds: 4 tps: 130.25 qps: 2605.00 (r/w/o: 1823.50/521.00/260.50) lat (ms,99%): 40.37 err/s: 0.00 reconn/s: 0.00
Latency histogram (values are in milliseconds)
       value  ------------- distribution ------------- count
       10.000 |****                                     10
       20.000 |****************************************  80
       45.000 |****                                      10
SQL statistics:
    queries performed:
EOF
  sed 's/^/2026-10-15T10:00:00Z /' "${WORK}/sysbench.log" > "${STUB_STATE}/log.1"
  cp "${WORK}/sysbench.log" "${STUB_STATE}/full-log"
}

run_script() {
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 KIT_NAME=sb1 KUBECONFIG="${WORK}/kubeconfig" \
        CONTROL_HOST_PRIVATE=10.0.0.5 \
        TARGET_PG_HOST=10.0.0.9 TARGET_PG_PORT=5432 TARGET_PG_USER=u TARGET_PG_DATABASE=d \
        WORKLOAD=oltp_read_write THREADS=4 DURATION=20 RATE=0 SKIP_TRX=off RAND_TYPE=special \
        "${BASH}" "${WORK}/kit/bin/start.sh"
  ) > "${WORK}/out.txt" 2>&1
}

run_call() { grep ' run ' "${STUB_STATE}/kubectl.calls" 2>/dev/null; }

# The body of call $1: the argument after --data-binary.
body_of() {
  awk 'prev == "--data-binary" { print; exit } { prev = $0 }' "$1"
}

test_pushes_otlp_json_to_the_collector() {
  start_test "OTLP JSON"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  local calls
  calls=$(ls "${STUB_STATE}"/curl.[0-9]* | wc -l | tr -d ' ')
  [ "${calls}" -ge 3 ] || fail "expected a push per interval and one for the run, got ${calls}"
  for call in "${STUB_STATE}"/curl.[0-9]*; do
    grep -Fqx 'http://10.0.0.5:4318/v1/metrics' "${call}" || fail "push not sent to the collector's OTLP HTTP port: $(cat "${call}")"
    grep -Fqx 'Content-Type: application/json' "${call}" || fail "push is not OTLP JSON: $(cat "${call}")"
    body_of "${call}" | jq -e . > /dev/null || fail "push body is not valid JSON: $(body_of "${call}")"
  done
  if grep -q ':8428' "${STUB_STATE}"/curl.[0-9]*; then
    fail "must not push to the VictoriaMetrics import API"
  fi
}

test_pushes_interval_and_run_figures_labelled_with_the_kit() {
  start_test "gauges"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  local gauges
  gauges="$(pushed_gauges)"

  for expected in "sysbench_tps sb1 120.5" "sysbench_qps sb1 2605" "sysbench_lat_p99_ms sb1 45.79" \
    "sysbench_errors_per_second sb1 0.1" "sysbench_lat_p50_ms sb1 20" "sysbench_lat_p95_ms sb1 45"; do
    grep -Fqx "${expected}" <<< "${gauges}" || fail "expected gauge '${expected}', pushed: ${gauges}"
  done
}

test_the_run_pod_is_pinned_to_an_app_node() {
  start_test "nodeSelector"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  run_call | grep -Fq -- "--labels=easydblab/kit=sb1,easydblab/role=sysbench-run" || fail "labels: $(run_call)"
  run_call | grep -Fq -- "--override-type=strategic" || fail "no strategic merge: $(run_call)"
  [ "$(jq -r '.spec.nodeSelector.type' "${STUB_STATE}/overrides.1")" = app ] || fail "overrides: $(cat "${STUB_STATE}/overrides.1" 2>/dev/null)"
}

test_no_app_node_runs_no_pod() {
  start_test "no app node"
  rm -f "${STUB_STATE}/app-nodes"
  run_script && fail "exited 0"
  grep -q "sysbench needs an app node (type=app); this cluster has none" "${WORK}/out.txt" || fail "message: $(cat "${WORK}/out.txt")"
  [ -z "$(run_call)" ] || fail "created a pod: $(run_call)"
}

test_a_pod_failed_on_the_first_poll_reports_the_exit_code() {
  start_test "Failed on the first poll"
  echo "Failed|||1" > "${STUB_STATE}/state.default"
  echo "2026-10-15T10:00:00Z FATAL: event queue is full" > "${STUB_STATE}/log.1"
  run_script && fail "exited 0"
  grep -q "The sysbench run failed with exit code 1" "${WORK}/out.txt" || fail "no failure line: $(cat "${WORK}/out.txt")"
  if grep -q "Run summary written to" "${WORK}/out.txt"; then fail "printed the success line"; fi
  if grep -q "did not start within" "${WORK}/out.txt"; then fail "reported a timeout"; fi
}

test_a_pull_error_fails_fast() {
  start_test "pull error"
  echo "Pending|ErrImagePull|pull access denied|" > "${STUB_STATE}/state.default"
  run_script && fail "exited 0"
  grep -q "ErrImagePull" "${WORK}/out.txt" || fail "no reason: $(cat "${WORK}/out.txt")"
  grep -q "Events: stub events for sysbench-sb1-" "${WORK}/out.txt" || fail "no events: $(cat "${WORK}/out.txt")"
  [ "$(grep -c '^get pod ' "${STUB_STATE}/kubectl.calls")" -eq 1 ] || fail "did not fail on the first poll"
  grep -q "delete pod sysbench-sb1-.* --ignore-not-found" "${STUB_STATE}/kubectl.calls" || fail "left the pending pod behind"
}

test_a_non_zero_run_fails_without_the_summary_line() {
  start_test "non-zero exit"
  printf '%s\n' "Running|||" "Failed|||1" > "${STUB_STATE}/state.default"
  run_script && fail "exited 0"
  grep -q "The sysbench run failed with exit code 1" "${WORK}/out.txt" || fail "no failure line: $(cat "${WORK}/out.txt")"
  if grep -q "Run summary written to" "${WORK}/out.txt"; then fail "printed the success line"; fi
}

test_a_failed_run_replaces_an_older_last_run() {
  start_test "failed run replaces last-run.txt"
  printf '%s\n' "sysbench run summary" "exit code: 0" "SQL statistics:" "    from an older run" > "${WORK}/kit/last-run.txt"
  printf '%s\n' "Running|||" "Failed|||1" > "${STUB_STATE}/state.default"
  printf '%s\n' "Running the test with following options:" "FATAL: event queue is full" > "${STUB_STATE}/full-log"
  run_script && fail "exited 0"
  if grep -q "from an older run" "${WORK}/kit/last-run.txt"; then fail "kept the older run: $(cat "${WORK}/kit/last-run.txt")"; fi
  grep -q "^exit code: *1$" "${WORK}/kit/last-run.txt" || fail "no exit code: $(cat "${WORK}/kit/last-run.txt")"
  grep -q "FATAL: event queue is full" "${WORK}/kit/last-run.txt" || fail "no end of the log: $(cat "${WORK}/kit/last-run.txt")"
  grep -q "${WORK}/kit/last-run.txt" "${WORK}/out.txt" || fail "the failure does not name last-run.txt: $(cat "${WORK}/out.txt")"
}

test_a_zero_run_prints_the_summary_line() {
  start_test "exit 0"
  run_script || fail "exited non-zero: $(cat "${WORK}/out.txt")"
  grep -q "Run summary written to ${WORK}/kit/last-run.txt" "${WORK}/out.txt" || fail "no summary line: $(cat "${WORK}/out.txt")"
  grep -q "^SQL statistics:" "${WORK}/kit/last-run.txt" || fail "no summary block: $(cat "${WORK}/kit/last-run.txt")"
  grep -q "^exit code: *0$" "${WORK}/kit/last-run.txt" || fail "no exit code: $(cat "${WORK}/kit/last-run.txt")"
}

test_a_repeated_stream_does_not_repeat_the_summary_or_the_histogram() {
  start_test "resumed stream repeats lines"
  # The stream is cut after the summary and resumes with the last second again.
  printf '%s\n' "Running|||" "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  printf '%s\n' "2026-10-15T10:00:20Z Latency histogram (values are in milliseconds)" \
    "2026-10-15T10:00:20Z        10.000 |****  10" "2026-10-15T10:00:20Z SQL statistics:" > "${STUB_STATE}/log.2"
  # The full log, which the summary and the percentiles must come from, has p50 at 30 ms.
  cat > "${STUB_STATE}/full-log" <<'EOF'
Latency histogram (values are in milliseconds)
       10.000 |****                                     10
       30.000 |****************************************  80
       45.000 |****                                      10
SQL statistics:
    queries performed:
EOF
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  [ "$(grep -c '^SQL statistics:' "${WORK}/kit/last-run.txt")" -eq 1 ] || fail "summary repeated: $(cat "${WORK}/kit/last-run.txt")"
  grep -Fqx "sysbench_lat_p50_ms sb1 30" <<< "$(pushed_gauges)" || fail "percentiles not from the full log: $(pushed_gauges)"
  grep -q "logs sysbench-sb1-" "${STUB_STATE}/kubectl.calls" || fail "did not read the full log"
}

test_pushes_otlp_json_to_the_collector
test_pushes_interval_and_run_figures_labelled_with_the_kit
test_a_repeated_stream_does_not_repeat_the_summary_or_the_histogram
test_the_run_pod_is_pinned_to_an_app_node
test_no_app_node_runs_no_pod
test_a_pod_failed_on_the_first_poll_reports_the_exit_code
test_a_pull_error_fails_fast
test_a_non_zero_run_fails_without_the_summary_line
test_a_failed_run_replaces_an_older_last_run
test_a_zero_run_prints_the_summary_line

echo "sysbench start (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
