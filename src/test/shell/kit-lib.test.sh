#!/usr/bin/env bash
#
# Unit tests for the shared kit shell library, configuration/edl-kit-lib.sh.
#
# Every case runs the library in a fresh shell under `set -euo pipefail`, with the bash that runs
# this file ($BASH), so a run under /bin/bash checks bash 3.2. kubectl, curl, date and sleep are
# stubbed (kit-stubs.sh): kubectl reports the pod phases a case scripts, so no wait loop hangs.
#
# Run directly:  bash src/test/shell/kit-lib.test.sh
# Or via gradle: ./gradlew testKitShellLib

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
# shellcheck source=kit-stubs.sh
. "${ROOT}/src/test/shell/kit-stubs.sh"

tests_run=0
tests_failed=0
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export STUB_STATE="${WORK}/state"
write_kit_stubs "${WORK}/stubs"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
}

# Runs shell code $1 after sourcing the library, under set -euo pipefail. Sets rc, and out and err
# to what it printed.
run_lib() {
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" KIT_NAME=bench-ch KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 "${BASH}" -c "set -euo pipefail; . \"\${EDL_KIT_LIB}\"; $1"
  ) > "${WORK}/out" 2> "${WORK}/err"
  rc=$?
  out="$(cat "${WORK}/out")"
  err="$(cat "${WORK}/err")"
}

calls() { cat "${STUB_STATE}/kubectl.calls" 2>/dev/null; }

test_no_app_node_fails_with_the_message() {
  start_test "no app node"
  run_lib 'edl_require_app_node clickhouse-benchmark'
  [ "${rc}" -ne 0 ] || fail "exited 0"
  [ "${err}" = "clickhouse-benchmark needs an app node (type=app); this cluster has none" ] || fail "message: ${err}"
  grep -Fqx 'get nodes -l type=app -o name' <<< "$(calls)" || fail "did not read the app nodes: $(calls)"
}

test_an_app_node_passes() {
  start_test "app node"
  echo "node/app0" > "${STUB_STATE}/app-nodes"
  run_lib 'edl_require_app_node sysbench'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
}

test_overrides_without_a_configmap_pin_to_app_nodes() {
  start_test "overrides without a ConfigMap"
  run_lib 'edl_pod_overrides'
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  [ "$(jq -r '.spec.nodeSelector.type' <<< "${out}")" = app ] || fail "no app nodeSelector: ${out}"
  [ "$(jq -r '.spec.volumes // "none"' <<< "${out}")" = none ] || fail "a volume with no ConfigMap: ${out}"
}

test_overrides_with_a_configmap_mount_it_in_the_container() {
  start_test "overrides with a ConfigMap"
  run_lib 'edl_pod_overrides edl-q-1 /edl run'
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  [ "$(jq -r '.spec.nodeSelector.type' <<< "${out}")" = app ] || fail "no app nodeSelector: ${out}"
  [ "$(jq -r '.spec.volumes | length' <<< "${out}")" = 1 ] || fail "not one volume: ${out}"
  local volume
  volume="$(jq -r '.spec.volumes[0].name' <<< "${out}")"
  [ "$(jq -r '.spec.volumes[0].configMap.name' <<< "${out}")" = edl-q-1 ] || fail "wrong ConfigMap: ${out}"
  [ "$(jq -r '.spec.containers[0].name' <<< "${out}")" = run ] || fail "wrong container: ${out}"
  [ "$(jq -r '.spec.containers[0].volumeMounts[0].mountPath' <<< "${out}")" = /edl ] || fail "wrong mount path: ${out}"
  [ "$(jq -r '.spec.containers[0].volumeMounts[0].name' <<< "${out}")" = "${volume}" ] || fail "mount does not name the volume: ${out}"
}

test_overrides_reject_names_that_are_not_dns_names() {
  start_test "overrides reject bad names"
  run_lib "edl_pod_overrides 'Bad_Name' /edl run"
  [ "${rc}" -ne 0 ] || fail "accepted a bad ConfigMap name"
  grep -q "Bad_Name" <<< "${err}" || fail "did not name the value: ${err}"
  run_lib "edl_pod_overrides edl-q-1 /edl 'Run\"'"
  [ "${rc}" -ne 0 ] || fail "accepted a bad container name"
  run_lib "edl_pod_overrides edl-q-1 'edl' run"
  [ "${rc}" -ne 0 ] || fail "accepted a relative mount path"
}

test_resource_names_end_in_a_timestamp_and_labels_name_kit_and_role() {
  start_test "names and labels"
  run_lib 'KIT_NAME=clickhouse-benchmark-clickhouse edl_resource_name run'
  [ "${out}" = clickhouse-benchmark-clickhouse-run-1700000000 ] || fail "name: ${out}"
  run_lib 'KIT_NAME=sysbench-tidb edl_labels sysbench-run'
  [ "${out}" = "easydblab/kit=sysbench-tidb,easydblab/role=sysbench-run" ] || fail "labels: ${out}"
}

test_configmap_missing_file_calls_no_create() {
  start_test "ConfigMap missing file"
  run_lib "edl_create_configmap cm1 benchmark-run queries.sql=${WORK}/nope.sql"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -Fq "${WORK}/nope.sql" <<< "${err}" || fail "did not name the path: ${err}"
  if grep -q '^create' <<< "$(calls)"; then fail "called kubectl create: $(calls)"; fi
}

test_configmap_empty_file_fails() {
  start_test "ConfigMap empty file"
  : > "${WORK}/empty.sql"
  run_lib "edl_create_configmap cm1 benchmark-run queries.sql=${WORK}/empty.sql"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -Fq "${WORK}/empty.sql" <<< "${err}" || fail "did not name the path: ${err}"
}

test_configmap_over_the_size_limit_fails_before_the_cluster() {
  start_test "ConfigMap size limit"
  head -c 600000 /dev/zero | tr '\0' 'a' > "${WORK}/a.sql"
  head -c 400001 /dev/zero | tr '\0' 'b' > "${WORK}/b.sh"
  run_lib "edl_create_configmap cm1 benchmark-run queries.sql=${WORK}/a.sql run.sh=${WORK}/b.sh"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "1000001" <<< "${err}" || fail "did not give the size: ${err}"
  grep -q "1000000" <<< "${err}" || fail "did not give the limit: ${err}"
  [ -z "$(calls)" ] || fail "called kubectl: $(calls)"
}

test_configmap_is_created_with_its_labels_in_one_call() {
  start_test "ConfigMap labelled create"
  echo "SELECT 1" > "${WORK}/q.sql"
  echo "echo run" > "${WORK}/run.sh"
  run_lib "edl_create_configmap cm1 benchmark-run queries.sql=${WORK}/q.sql run-benchmark.sh=${WORK}/run.sh"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  local all
  all="$(calls)"
  grep -Fqx "create configmap cm1 --from-file=queries.sql=${WORK}/q.sql --from-file=run-benchmark.sh=${WORK}/run.sh --dry-run=client -o yaml" <<< "${all}" ||
    fail "no client-side configmap render: ${all}"
  grep -Fqx "label --local -f - easydblab/kit=bench-ch easydblab/role=benchmark-run -o yaml" <<< "${all}" || fail "no local label: ${all}"
  grep -Fqx "create -f -" <<< "${all}" || fail "did not end with create: ${all}"
  if grep -q '^apply' <<< "${all}"; then fail "used apply: ${all}"; fi
  grep -Fq "# label easydblab/role=benchmark-run" "${STUB_STATE}/configmap.1" || fail "the created ConfigMap has no labels: $(cat "${STUB_STATE}/configmap.1")"
}

test_active_pods_lists_only_pods_that_have_not_ended() {
  start_test "active pods"
  printf '%s\n' "bench-ch-benchmark-run-1 Pending" "bench-ch-benchmark-run-0 Succeeded" > "${STUB_STATE}/pods"
  run_lib 'edl_active_pods benchmark-run'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = bench-ch-benchmark-run-1 ] || fail "listed: ${out}"
  grep -Fq -- "-l easydblab/kit=bench-ch,easydblab/role=benchmark-run" <<< "$(calls)" || fail "wrong selector: $(calls)"
}

test_a_pod_that_ended_before_it_was_seen_running_returns_at_once() {
  start_test "Succeeded on the first poll"
  echo "Succeeded|||0" > "${STUB_STATE}/state.default"
  EDL_POD_START_TIMEOUT_SECONDS=600 run_lib 'edl_wait_for_pod_running p1'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "$(grep -c '^get pod' <<< "$(calls)")" -eq 1 ] || fail "polled more than once: $(calls)"
}

test_each_pull_error_fails_fast_with_reason_message_and_events() {
  local reason
  for reason in ErrImagePull ImagePullBackOff InvalidImageName; do
    start_test "pull error ${reason}"
    echo "Pending|${reason}|Back-off pulling image clickhouse/clickhouse-server:25.44|" > "${STUB_STATE}/state.default"
    run_lib 'edl_wait_for_pod_running p1'
    [ "${rc}" -ne 0 ] || fail "exited 0"
    grep -q "${reason}" <<< "${err}" || fail "did not print the reason: ${err}"
    grep -q "Back-off pulling image clickhouse/clickhouse-server:25.44" <<< "${err}" || fail "did not print the message: ${err}"
    grep -q "Events: stub describe events for p1" <<< "${err}" || fail "did not print the events: ${err}"
    [ "$(grep -c '^get pod' <<< "$(calls)")" -eq 1 ] || fail "did not fail on the first poll: $(calls)"
    # A pod left Pending would keep its role label and block the next start or prepare.
    grep -Fqx "delete pod p1 --ignore-not-found" <<< "$(calls)" || fail "did not delete the pod: $(calls)"
  done
}

test_a_pod_pending_past_the_limit_fails_and_is_described() {
  start_test "start timeout"
  echo "Pending|ContainerCreating||" > "${STUB_STATE}/state.default"
  export EDL_POD_START_TIMEOUT_SECONDS=3
  run_lib 'edl_wait_for_pod_running p1'
  unset EDL_POD_START_TIMEOUT_SECONDS
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "pod p1 did not start within 3 seconds" <<< "${err}" || fail "no limit message: ${err}"
  grep -q "Events: stub describe events for p1" <<< "${err}" || fail "did not describe the pod: ${err}"
  grep -Fqx "delete pod p1 --ignore-not-found" <<< "$(calls)" || fail "did not delete the pod: $(calls)"
}

test_a_failed_get_is_a_retry_not_a_phase() {
  start_test "failed get retried"
  printf '%s\n' ERROR ERROR "Running|||" > "${STUB_STATE}/state.default"
  run_lib 'edl_wait_for_pod_running p1'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "$(grep -c '^get pod' <<< "$(calls)")" -eq 3 ] || fail "did not retry: $(calls)"
}

test_a_deleted_pod_fails_every_wait() {
  local fn
  for fn in edl_wait_for_pod_running edl_wait_for_pod_exit "edl_follow_pod_logs p1 echo; :"; do
    start_test "NotFound in ${fn%% *}"
    echo NOTFOUND > "${STUB_STATE}/state.default"
    case "${fn}" in
      edl_follow*) run_lib "${fn}" ;;
      *) run_lib "${fn} p1" ;;
    esac
    [ "${rc}" -ne 0 ] || fail "exited 0"
    grep -Fqx "pod p1 was deleted" <<< "${err}" || fail "message: ${err}"
  done
}

handler_code='seen() { printf "%s\n" "$1" >> "${STUB_STATE}/handled"; }'

test_a_cut_stream_resumes_from_the_last_timestamp_while_the_pod_runs() {
  start_test "resume after a cut stream"
  printf '%s\n' "2026-10-15T10:00:01.000000001Z first" "2026-10-15T10:00:11.000000002Z second" > "${STUB_STATE}/log.1"
  printf '%s\n' "2026-10-15T10:00:21.000000003Z third" > "${STUB_STATE}/log.2"
  printf '%s\n' "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  run_lib "${handler_code}; edl_follow_pod_logs p1 seen"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  [ "$(cat "${STUB_STATE}/handled")" = "$(printf '%s\n' first second third)" ] || fail "handled: $(cat "${STUB_STATE}/handled")"
  [ "$(cat "${STUB_STATE}/since-times")" = "2026-10-15T10:00:11.000000002Z" ] || fail "since-time: $(cat "${STUB_STATE}/since-times" 2>/dev/null)"
}

test_the_phase_is_read_again_after_the_pause_before_a_resume() {
  start_test "phase checked after the pause"
  echo "2026-10-15T10:00:01Z one" > "${STUB_STATE}/log.1"
  printf '%s\n' "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  run_lib "${handler_code}; edl_follow_pod_logs p1 seen"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  # Every resumed stream must come right after a phase read, never right after a pause, so a pod
  # that ended during the pause is not streamed again.
  awk '/^logs -f/ && n++ && prev !~ /^get pod / { bad = 1 } { prev = $0 } END { exit bad }' "${STUB_STATE}/kubectl.calls" ||
    fail "resumed without reading the phase after the pause: $(calls)"
  [ "$(grep -c '^logs -f' "${STUB_STATE}/kubectl.calls")" -eq 2 ] || fail "streams: $(calls)"
}

test_a_failed_log_stream_does_not_end_a_pipefail_script() {
  start_test "non-zero logs -f"
  echo "2026-10-15T10:00:01Z one" > "${STUB_STATE}/log.1"
  echo 1 > "${STUB_STATE}/logs-exit.1"
  echo "2026-10-15T10:00:02Z two" > "${STUB_STATE}/log.2"
  printf '%s\n' "Running|||" "Failed|||3" > "${STUB_STATE}/state.default"
  run_lib "${handler_code}; edl_follow_pod_logs p1 seen; echo after"
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = after ] || fail "the script did not continue: ${out}"
  [ "$(cat "${STUB_STATE}/handled")" = "$(printf '%s\n' one two)" ] || fail "did not resume: $(cat "${STUB_STATE}/handled")"
}

test_exit_codes_are_reported() {
  start_test "exit code non-zero"
  printf '%s\n' "Running|||" "Failed|||3" > "${STUB_STATE}/state.default"
  run_lib 'edl_wait_for_pod_exit p1'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = 3 ] || fail "printed ${out}"

  start_test "exit code 0"
  echo "Succeeded|||0" > "${STUB_STATE}/state.default"
  run_lib 'edl_wait_for_pod_exit p1'
  [ "${out}" = 0 ] || fail "printed ${out}"

  start_test "Failed with no exit code"
  echo "Failed|||" > "${STUB_STATE}/state.default"
  run_lib 'edl_wait_for_pod_exit p1'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = unknown ] || fail "printed ${out}"
}

test_gauges_are_pushed_as_otlp_json_labelled_with_the_kit() {
  start_test "gauge body"
  run_lib 'edl_push_gauges clickhouse-benchmark clickhouse_benchmark_qps 12.5 clickhouse_benchmark_errors abc clickhouse_benchmark_rows_per_second ""'
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  grep -Fqx 'http://127.0.0.1:18001/api/v1/namespaces/default/pods/otel-collector-c0:4318/proxy/v1/metrics' "${STUB_STATE}/curl.1" || fail "not sent to the control node's core collector pod through the API proxy: $(cat "${STUB_STATE}/curl.1")"
  local gauges body
  gauges="$(pushed_gauges)"
  for expected in "clickhouse_benchmark_qps bench-ch 12.5" "clickhouse_benchmark_errors bench-ch 0" \
    "clickhouse_benchmark_rows_per_second bench-ch 0"; do
    grep -Fqx "${expected}" <<< "${gauges}" || fail "expected '${expected}', pushed: ${gauges}"
  done
  body="$(awk 'prev == "--data-binary" { print; exit } { prev = $0 }' "${STUB_STATE}/curl.1")"
  [ "$(jq -r '.resourceMetrics[0].resource.attributes[0].value.stringValue' <<< "${body}")" = clickhouse-benchmark ] ||
    fail "wrong service name: ${body}"
}

test_a_failed_push_returns_0() {
  start_test "collector unreachable"
  echo 7 > "${STUB_STATE}/curl-exit"
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; edl_push_gauges sysbench sysbench_tps 2; echo after'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}"
  [ "${out}" = after ] || fail "the script did not continue: ${out}"
  [ "${err}" = "warning: could not push sysbench gauges to collector pod otel-collector-c0 (curl exit 7); the dashboard will not show this run" ] ||
    fail "not one warning on stderr across two failed pushes: ${err}"
}

test_a_push_whose_proxy_never_serves_returns_0_without_curl() {
  start_test "kubectl proxy fails"
  touch "${STUB_STATE}/proxy-fail"
  run_lib 'EDL_PROXY_START_TRIES=3; edl_push_gauges sysbench sysbench_tps 1; echo after'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}"
  [ "${out}" = after ] || fail "the script did not continue: ${out}"
  [ ! -e "${STUB_STATE}/curl.1" ] || fail "posted with no proxy: $(cat "${STUB_STATE}/curl.1")"
  [ "${err}" = "warning: could not push sysbench gauges: kubectl proxy did not start: error: unable to listen; the dashboard will not show this run" ] ||
    fail "the warning does not carry kubectl's own error: ${err}"
}

# The core stamps host.name with its own node, so every push must reach a core pod on one node, or
# one gauge splits into a series per node. Pods on other nodes and a pod replaced between pushes
# must not move it off the control node.
test_every_push_goes_to_the_control_nodes_core_pod() {
  start_test "one node for every push"
  printf '%s\n' "otel-collector-a1 app0" "otel-collector-d0 db0" "otel-collector-c0 control0" > "${STUB_STATE}/core-pods"
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; printf "%s\n" "otel-collector-a1 app0" "otel-collector-c9 control0" > "${STUB_STATE}/core-pods"; edl_push_gauges sysbench sysbench_tps 2'
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  grep -Fqx 'http://127.0.0.1:18001/api/v1/namespaces/default/pods/otel-collector-c0:4318/proxy/v1/metrics' "${STUB_STATE}/curl.1" ||
    fail "first push not sent to the control node's pod: $(cat "${STUB_STATE}/curl.1")"
  grep -Fqx 'http://127.0.0.1:18001/api/v1/namespaces/default/pods/otel-collector-c9:4318/proxy/v1/metrics' "${STUB_STATE}/curl.2" ||
    fail "second push not sent to the control node's replacement pod: $(cat "${STUB_STATE}/curl.2")"
  grep -Fq -- '--field-selector=status.phase=Running,spec.nodeName=control0' <<< "$(calls)" ||
    fail "did not select the core pod by the control node: $(calls)"
}

test_a_push_with_no_core_pod_on_the_control_node_warns_without_curl() {
  start_test "no core pod on the control node"
  echo "otel-collector-d0 db0" > "${STUB_STATE}/core-pods"
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; echo after'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}"
  [ "${out}" = after ] || fail "the script did not continue: ${out}"
  [ ! -e "${STUB_STATE}/curl.1" ] || fail "posted to a pod on another node: $(cat "${STUB_STATE}/curl.1")"
  if grep -q '^proxy' <<< "$(calls)"; then fail "started a proxy with nowhere to send: $(calls)"; fi
  [ "${err}" = "warning: could not push sysbench gauges: no running otel-collector pod on control node control0; the dashboard will not show this run" ] ||
    fail "message: ${err}"
}

# A hung API server must not block the kit: both lookups carry a request timeout, so the push
# only warns and the script goes on.
test_a_push_against_a_hung_api_server_is_bounded_and_only_warns() {
  start_test "hung API server"
  echo nodes > "${STUB_STATE}/api-hang"
  local started=${SECONDS}
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; echo after'
  [ $((SECONDS - started)) -lt 3 ] || fail "the push waited on the hung API server"
  [ "${rc}" -eq 0 ] || fail "exited ${rc}"
  [ "${out}" = after ] || fail "the script did not continue: ${out}"
  [ "${err}" = "warning: could not push sysbench gauges: could not find the control node; the dashboard will not show this run" ] ||
    fail "message: ${err}"
  [ ! -e "${STUB_STATE}/curl.1" ] || fail "posted with no control node"
}

test_a_push_whose_core_pod_lookup_hangs_is_bounded_and_only_warns() {
  start_test "hung core pod lookup"
  echo pods > "${STUB_STATE}/api-hang"
  local started=${SECONDS}
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; echo after'
  [ $((SECONDS - started)) -lt 3 ] || fail "the push waited on the hung API server"
  [ "${rc}" -eq 0 ] || fail "exited ${rc}"
  [ "${out}" = after ] || fail "the script did not continue: ${out}"
  [ "${err}" = "warning: could not push sysbench gauges: could not list the otel-collector pods on control node control0; the dashboard will not show this run" ] ||
    fail "message: ${err}"
}

# A TERM (or a Ctrl-C) during the push must not leave the background `kubectl proxy` running or its
# banner file behind, and must still reach the caller: its own trap runs, and its traps are back.
test_a_term_during_a_push_stops_the_proxy_and_reaches_the_callers_trap() {
  start_test "TERM during a push"
  touch "${STUB_STATE}/proxy-hold"; echo TERM > "${STUB_STATE}/curl-signal"
  mkdir -p "${WORK}/tmp"
  rm -rf "${WORK}/tmp/"*
  run_lib "export TMPDIR='${WORK}/tmp'; trap 'echo caller-term' TERM; edl_push_gauges sysbench sysbench_tps 1; trap -p TERM; echo after"
  local pid
  pid="$(cat "${STUB_STATE}/proxy.pid" 2>/dev/null)"
  [ -n "${pid}" ] || { fail "the proxy never started: $(calls)"; return; }
  if kill -0 "${pid}" 2> /dev/null; then
    kill "${pid}" 2> /dev/null
    fail "kubectl proxy ${pid} is still running"
  fi
  [ -z "$(ls -A "${WORK}/tmp")" ] || fail "left files behind: $(ls -A "${WORK}/tmp")"
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = "caller-term
trap -- 'echo caller-term' SIGTERM
after" ] || fail "the caller's trap did not run, or was not restored: ${out}"
}

# A TERM with no trap of the caller's ends the script, as it would have without the push.
test_a_term_during_a_push_ends_a_script_with_no_trap() {
  start_test "TERM with no caller trap"
  touch "${STUB_STATE}/proxy-hold"; echo TERM > "${STUB_STATE}/curl-signal"
  # The test shell reports the subshell TERM ended ("Terminated: 15"); that line is expected here.
  { run_lib 'trap "echo exit-trap" EXIT; edl_push_gauges sysbench sysbench_tps 1; echo after'; } 2> /dev/null
  local pid
  pid="$(cat "${STUB_STATE}/proxy.pid" 2>/dev/null)"
  if [ -n "${pid}" ] && kill -0 "${pid}" 2> /dev/null; then
    kill "${pid}" 2> /dev/null
    fail "kubectl proxy ${pid} is still running"
  fi
  [ "${rc}" -eq 143 ] || fail "exited ${rc}, not as killed by TERM"
  [ "${out}" = exit-trap ] || fail "the script went on, or its EXIT trap did not run: ${out}"
}

# A Ctrl-C reaches the push as INT: the proxy stops, and the caller's INT trap runs.
test_an_int_during_a_push_stops_the_proxy_and_reaches_the_callers_trap() {
  start_test "INT during a push"
  touch "${STUB_STATE}/proxy-hold"; echo INT > "${STUB_STATE}/curl-signal"
  mkdir -p "${WORK}/tmp"
  rm -rf "${WORK}/tmp/"*
  run_lib "export TMPDIR='${WORK}/tmp'; trap 'echo caller-int' INT; edl_push_gauges sysbench sysbench_tps 1; trap -p INT; echo after"
  local pid
  pid="$(cat "${STUB_STATE}/proxy.pid" 2>/dev/null)"
  [ -n "${pid}" ] || { fail "the proxy never started: $(calls)"; return; }
  if kill -0 "${pid}" 2> /dev/null; then
    kill "${pid}" 2> /dev/null
    fail "kubectl proxy ${pid} is still running"
  fi
  [ -z "$(ls -A "${WORK}/tmp")" ] || fail "left files behind: $(ls -A "${WORK}/tmp")"
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = "caller-int
trap -- 'echo caller-int' SIGINT
after" ] || fail "the caller's trap did not run, or was not restored: ${out}"
}

# An INT with no trap of the caller's ends the script by INT, and the proxy is not left running.
test_an_int_during_a_push_ends_a_script_with_no_trap() {
  start_test "INT with no caller trap"
  touch "${STUB_STATE}/proxy-hold"; echo INT > "${STUB_STATE}/curl-signal"
  { run_lib 'edl_push_gauges sysbench sysbench_tps 1; echo after'; } 2> /dev/null
  local pid
  pid="$(cat "${STUB_STATE}/proxy.pid" 2>/dev/null)"
  [ -n "${pid}" ] || { fail "the proxy never started: $(calls)"; return; }
  if kill -0 "${pid}" 2> /dev/null; then
    kill "${pid}" 2> /dev/null
    fail "kubectl proxy ${pid} is still running"
  fi
  [ "${rc}" -eq 130 ] || fail "exited ${rc}, not as killed by INT"
  [ -z "${out}" ] || fail "the script went on: ${out}"
}

# The push keeps its state in its own globals only while it runs, so nothing of it is left in the
# caller's shell, and a caller variable named like a push local is not touched.
test_a_push_leaves_no_state_in_the_callers_shell() {
  start_test "no push state left behind"
  run_lib 'proxy_pid=mine; banner=mine; saved_traps=mine; edl_push_gauges sysbench sysbench_tps 1
    echo "${proxy_pid} ${banner} ${saved_traps}"; set | grep "^EDL_PUSH_" || true'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${err}"
  [ "${out}" = "mine mine mine" ] || fail "push state left in the caller's shell: ${out}"
}

# During a rolling update a terminating core pod is still Running; the push must not pick it.
test_a_push_skips_a_terminating_core_pod() {
  start_test "terminating core pod skipped"
  printf '%s\n' "otel-collector-old control0 2026-11-14T00:00:00Z" "otel-collector-new control0" > "${STUB_STATE}/core-pods"
  run_lib 'edl_push_gauges sysbench sysbench_tps 1'
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  grep -Fqx 'http://127.0.0.1:18001/api/v1/namespaces/default/pods/otel-collector-new:4318/proxy/v1/metrics' "${STUB_STATE}/curl.1" ||
    fail "did not post to the pod that is not terminating: $(cat "${STUB_STATE}/curl.1" 2>/dev/null)"
}

test_a_push_with_only_a_terminating_core_pod_warns_without_curl() {
  start_test "only a terminating core pod"
  echo "otel-collector-old control0 2026-11-14T00:00:00Z" > "${STUB_STATE}/core-pods"
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; echo after'
  [ "${rc}" -eq 0 ] || fail "exited ${rc}"
  [ ! -e "${STUB_STATE}/curl.1" ] || fail "posted to a terminating pod: $(cat "${STUB_STATE}/curl.1")"
  [ "${err}" = "warning: could not push sysbench gauges: no running otel-collector pod on control node control0; the dashboard will not show this run" ] ||
    fail "message: ${err}"
}

test_a_push_leaves_the_callers_traps_as_they_were() {
  start_test "traps restored"
  run_lib "trap 'echo int' INT; before=\"\$(trap -p)\"; edl_push_gauges sysbench sysbench_tps 1; [ \"\$(trap -p)\" = \"\${before}\" ] || { trap -p; exit 9; }"
  [ "${rc}" -eq 0 ] || fail "traps changed: ${out} ${err}"
  run_lib 'edl_push_gauges sysbench sysbench_tps 1; trap -p'
  [ -z "${out}" ] || fail "left a trap set: ${out}"
}

test_sourcing_changes_no_option_and_defines_only_edl_names() {
  start_test "sourcing is side-effect free"
  (
    PATH="${WORK}/stubs:${PATH}" "${BASH}" -c '
      set -euo pipefail
      before_opts="$(set -o)"; before_vars="$(compgen -v | sort)"; before_fns="$( (compgen -A function || true) | sort)"; dir="$PWD"
      . "$1"
      [ "$(set -o)" = "${before_opts}" ] || { echo "set -o changed"; exit 1; }
      [ "$PWD" = "${dir}" ] || { echo "working directory changed"; exit 1; }
      [ -z "$(trap -p)" ] || { echo "a trap was set"; exit 1; }
      new_vars="$(comm -13 <(echo "${before_vars}") <(compgen -v | sort) | grep -v -e "^EDL_" -e "^before_" -e "^dir$" || true)"
      [ -z "${new_vars}" ] || { echo "non-EDL_ variables: ${new_vars}"; exit 1; }
      new_fns="$(comm -13 <(echo "${before_fns}") <(compgen -A function | sort) | grep -v "^edl_" || true)"
      [ -z "${new_fns}" ] || { echo "non-edl_ functions: ${new_fns}"; exit 1; }
    ' _ "${LIB}"
  ) > "${WORK}/out" 2>&1 || fail "$(cat "${WORK}/out")"
}

test_a_script_run_outside_easy_db_lab_says_so() {
  start_test "missing EDL_KIT_LIB"
  (env -u EDL_KIT_LIB "${BASH}" -c '. "${EDL_KIT_LIB:?run this through easy-db-lab, which sets EDL_KIT_LIB}"; echo sourced') \
    > "${WORK}/out" 2>&1 && fail "exited 0"
  grep -q "run this through easy-db-lab, which sets EDL_KIT_LIB" "${WORK}/out" || fail "message: $(cat "${WORK}/out")"
}

test_no_app_node_fails_with_the_message
test_an_app_node_passes
test_overrides_without_a_configmap_pin_to_app_nodes
test_overrides_with_a_configmap_mount_it_in_the_container
test_overrides_reject_names_that_are_not_dns_names
test_resource_names_end_in_a_timestamp_and_labels_name_kit_and_role
test_configmap_missing_file_calls_no_create
test_configmap_empty_file_fails
test_configmap_over_the_size_limit_fails_before_the_cluster
test_configmap_is_created_with_its_labels_in_one_call
test_active_pods_lists_only_pods_that_have_not_ended
test_a_pod_that_ended_before_it_was_seen_running_returns_at_once
test_each_pull_error_fails_fast_with_reason_message_and_events
test_a_pod_pending_past_the_limit_fails_and_is_described
test_a_failed_get_is_a_retry_not_a_phase
test_a_deleted_pod_fails_every_wait
test_a_cut_stream_resumes_from_the_last_timestamp_while_the_pod_runs
test_the_phase_is_read_again_after_the_pause_before_a_resume
test_a_failed_log_stream_does_not_end_a_pipefail_script
test_exit_codes_are_reported
test_gauges_are_pushed_as_otlp_json_labelled_with_the_kit
test_a_failed_push_returns_0
test_a_push_whose_proxy_never_serves_returns_0_without_curl
test_every_push_goes_to_the_control_nodes_core_pod
test_a_push_with_no_core_pod_on_the_control_node_warns_without_curl
test_a_push_against_a_hung_api_server_is_bounded_and_only_warns
test_a_push_whose_core_pod_lookup_hangs_is_bounded_and_only_warns
test_a_term_during_a_push_stops_the_proxy_and_reaches_the_callers_trap
test_a_term_during_a_push_ends_a_script_with_no_trap
test_an_int_during_a_push_stops_the_proxy_and_reaches_the_callers_trap
test_an_int_during_a_push_ends_a_script_with_no_trap
test_a_push_leaves_no_state_in_the_callers_shell
test_a_push_skips_a_terminating_core_pod
test_a_push_with_only_a_terminating_core_pod_warns_without_curl
test_a_push_leaves_the_callers_traps_as_they_were
test_sourcing_changes_no_option_and_defines_only_edl_names
test_a_script_run_outside_easy_db_lab_says_so

echo "kit-lib (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
