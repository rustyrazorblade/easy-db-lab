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
# to what it printed. Any further arguments are the code's positional parameters.
run_lib() {
  local code="$1"
  shift
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" KIT_NAME=bench-ch KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 "${BASH}" -c "set -euo pipefail; . \"\${EDL_KIT_LIB}\"; ${code}" _ "$@"
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

SECRET_VALUE="p w*'d \"x"

# The value comes from stdin and reaches the Secret, but no kubectl command line, through a private
# temp file the function removes. It is applied server-side, so no last-applied annotation copies it.
test_secret_is_applied_with_its_labels_and_the_value_on_no_command_line() {
  start_test "Secret labelled apply"
  run_lib "printf '%s' \"\$1\" | edl_apply_secret bench-ch-credentials bench-credentials password" "${SECRET_VALUE}"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  local all from
  all="$(calls)"
  grep -Eqx "create secret generic bench-ch-credentials --from-file=password=[^ ]+ --dry-run=client -o yaml" <<< "${all}" ||
    fail "no client-side secret render: ${all}"
  grep -Fqx "label --local -f - easydblab/kit=bench-ch easydblab/role=bench-credentials -o yaml" <<< "${all}" || fail "no local label: ${all}"
  grep -Fqx "apply --server-side --force-conflicts -f -" <<< "${all}" || fail "not applied server-side: ${all}"
  grep -Fq -- "p w*" <<< "${all}" && fail "the value is on a kubectl command line: ${all}"
  grep -Fqx "# data password=${SECRET_VALUE}" "${STUB_STATE}/applied.1" || fail "the Secret does not hold the value: $(cat "${STUB_STATE}/applied.1")"
  grep -Fq "# label easydblab/role=bench-credentials" "${STUB_STATE}/applied.1" || fail "the Secret has no labels: $(cat "${STUB_STATE}/applied.1")"
  from="$(sed -n 's/^create secret generic [^ ]* --from-file=password=\([^ ]*\) .*/\1/p' <<< "${all}")"
  [ -n "${from}" ] && [ ! -e "${from}" ] || fail "the temp file ${from} was left behind"
  [ -z "${out}${err}" ] || fail "printed something: ${out}${err}"
}

test_an_empty_secret_value_fails_before_the_cluster() {
  start_test "empty Secret value"
  run_lib "printf '' | edl_apply_secret bench-ch-credentials bench-credentials password"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "bench-ch-credentials" <<< "${err}" || fail "did not name the Secret: ${err}"
  [ -z "$(calls)" ] || fail "called kubectl: $(calls)"
}

test_a_secret_with_a_bad_name_or_key_fails_before_the_cluster() {
  start_test "bad Secret name or key"
  run_lib "printf x | edl_apply_secret Bad_Name bench-credentials password"
  [ "${rc}" -ne 0 ] || fail "accepted a bad Secret name"
  run_lib "printf x | edl_apply_secret bench-ch-credentials bench-credentials 'pass word'"
  [ "${rc}" -ne 0 ] || fail "accepted a bad key"
  [ -z "$(calls)" ] || fail "called kubectl: $(calls)"
}

test_secret_env_overrides_pin_to_app_nodes_and_reference_the_secret() {
  start_test "overrides with a Secret env"
  run_lib 'edl_pod_overrides_secret_env run TARGET_PASSWORD bench-ch-credentials password'
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${err}"; return; }
  [ "$(jq -r '.spec.nodeSelector.type' <<< "${out}")" = app ] || fail "no app nodeSelector: ${out}"
  [ "$(jq -r '.spec.containers | length' <<< "${out}")" = 1 ] || fail "not one container: ${out}"
  [ "$(jq -r '.spec.containers[0].name' <<< "${out}")" = run ] || fail "wrong container: ${out}"
  [ "$(jq -c '.spec.containers[0].env' <<< "${out}")" = '[{"name":"TARGET_PASSWORD","valueFrom":{"secretKeyRef":{"name":"bench-ch-credentials","key":"password"}}}]' ] ||
    fail "env does not reference the Secret: ${out}"
}

test_secret_env_overrides_reject_bad_names() {
  start_test "Secret env overrides reject bad names"
  run_lib 'edl_pod_overrides_secret_env Run TARGET_PASSWORD bench-ch-credentials password'
  [ "${rc}" -ne 0 ] || fail "accepted a bad container name"
  run_lib "edl_pod_overrides_secret_env run 'TARGET-PASSWORD' bench-ch-credentials password"
  [ "${rc}" -ne 0 ] || fail "accepted a bad variable name"
  run_lib 'edl_pod_overrides_secret_env run TARGET_PASSWORD Bad_Name password'
  [ "${rc}" -ne 0 ] || fail "accepted a bad Secret name"
  run_lib "edl_pod_overrides_secret_env run TARGET_PASSWORD bench-ch-credentials 'pa\"ss'"
  [ "${rc}" -ne 0 ] || fail "accepted a bad key"
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
    grep -q "Events: stub events for p1" <<< "${err}" || fail "did not print the events: ${err}"
    # describe prints the pod's command line, which can hold a target kit's password.
    if grep -q '^describe' <<< "$(calls)"; then fail "ran kubectl describe: $(calls)"; fi
    [ "$(grep -c '^get pod' <<< "$(calls)")" -eq 1 ] || fail "did not fail on the first poll: $(calls)"
    # A pod left Pending would keep its role label and block the next start or prepare.
    grep -Fqx "delete pod p1 --ignore-not-found" <<< "$(calls)" || fail "did not delete the pod: $(calls)"
  done
}

test_a_pod_pending_past_the_limit_fails_and_shows_its_events() {
  start_test "start timeout"
  echo "Pending|ContainerCreating||" > "${STUB_STATE}/state.default"
  export EDL_POD_START_TIMEOUT_SECONDS=3
  run_lib 'edl_wait_for_pod_running p1'
  unset EDL_POD_START_TIMEOUT_SECONDS
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "pod p1 did not start within 3 seconds" <<< "${err}" || fail "no limit message: ${err}"
  grep -q "Events: stub events for p1" <<< "${err}" || fail "did not print the pod's events: ${err}"
  if grep -q '^describe' <<< "$(calls)"; then fail "ran kubectl describe: $(calls)"; fi
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
  grep -Fqx 'http://10.0.0.5:4318/v1/metrics' "${STUB_STATE}/curl.1" || fail "not sent to the collector: $(cat "${STUB_STATE}/curl.1")"
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
  [ "${err}" = "warning: could not push sysbench gauges to http://10.0.0.5:4318/v1/metrics (curl exit 7); the dashboard will not show this run" ] ||
    fail "not one warning on stderr across two failed pushes: ${err}"
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
test_secret_is_applied_with_its_labels_and_the_value_on_no_command_line
test_an_empty_secret_value_fails_before_the_cluster
test_a_secret_with_a_bad_name_or_key_fails_before_the_cluster
test_secret_env_overrides_pin_to_app_nodes_and_reference_the_secret
test_secret_env_overrides_reject_bad_names
test_active_pods_lists_only_pods_that_have_not_ended
test_a_pod_that_ended_before_it_was_seen_running_returns_at_once
test_each_pull_error_fails_fast_with_reason_message_and_events
test_a_pod_pending_past_the_limit_fails_and_shows_its_events
test_a_failed_get_is_a_retry_not_a_phase
test_a_deleted_pod_fails_every_wait
test_a_cut_stream_resumes_from_the_last_timestamp_while_the_pod_runs
test_the_phase_is_read_again_after_the_pause_before_a_resume
test_a_failed_log_stream_does_not_end_a_pipefail_script
test_exit_codes_are_reported
test_gauges_are_pushed_as_otlp_json_labelled_with_the_kit
test_a_failed_push_returns_0
test_sourcing_changes_no_option_and_defines_only_edl_names
test_a_script_run_outside_easy_db_lab_says_so

echo "kit-lib (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
