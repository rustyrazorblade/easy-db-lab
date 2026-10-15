#!/usr/bin/env bash
#
# Unit tests for the clickhouse-benchmark kit's bin/start.sh.template, its lib/args.sh, and the
# pod-side runner pod/run-benchmark.sh.
#
# start runs with the bash that runs this file ($BASH), against the stubs in kit-stubs.sh: kubectl
# reports the run pod's phases and replays a real clickhouse-benchmark log, curl answers the hits
# check and records the gauge pushes. run-benchmark.sh runs under sh with a stub
# clickhouse-benchmark. No cluster, no network.
#
# Run directly:  bash src/test/shell/clickhouse-benchmark-start.test.sh
# Or via gradle: ./gradlew testClickhouseBenchmarkStart

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/clickhouse-benchmark"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
FIXTURES="${ROOT}/src/test/shell/fixtures/clickhouse-benchmark"
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
cp -R "${KIT}/lib" "${KIT}/pod" "${KIT}/workloads" "${WORK}/kit/"
cp "${KIT}/bin/start.sh.template" "${WORK}/kit/bin/start.sh"
INSTANCE=clickhouse-benchmark-clickhouse

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# Starts a case with an app node, an existing hits table, and a run pod that streams the real report
# fixture and exits 0.
start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
  rm -f "${WORK}/kit/last-run.txt" "${WORK}/kit/last-run.json"
  echo "node/app0" > "${STUB_STATE}/app-nodes"
  echo "default" > "${STUB_STATE}/curl-out"
  printf '%s\n' "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  sed 's/^/2026-10-15T10:00:00.000000001Z /' "${FIXTURES}/report.txt" > "${STUB_STATE}/log.1"
  cp "${FIXTURES}/report.txt" "${STUB_STATE}/full-log"
}

# Runs start.sh with the default args, overridden by the NAME=value words in $@.
run_start() {
  (
    cd "${WORK}" &&
      env PATH="${WORK}/stubs:${PATH}" KIT_NAME="${INSTANCE}" KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 TARGET=clickhouse VERSION=25.4 \
        TARGET_NATIVE_HOST=10.0.1.5 TARGET_NATIVE_PORT=30900 TARGET_NATIVE_USER=default TARGET_NATIVE_DATABASE=default \
        TARGET_HTTP_URL=http://10.0.1.5:30123 \
        WORKLOAD= QUERIES= CONCURRENCY=4 DURATION=60 ITERATIONS=0 RANDOMIZE=true CONTINUE_ON_ERRORS=false SETTINGS= \
        "$@" "${BASH}" "${WORK}/kit/bin/start.sh"
  ) > "${WORK}/out" 2>&1
  rc=$?
  out="$(cat "${WORK}/out")"
}

calls() { cat "${STUB_STATE}/kubectl.calls" 2>/dev/null; }
run_call() { grep '^run ' "${STUB_STATE}/kubectl.calls" 2>/dev/null; }
no_pod() { [ -z "$(run_call)" ] || fail "created a pod: $(run_call)"; }

test_missing_target_native_variables_fail_before_a_pod() {
  local var
  for var in TARGET_NATIVE_HOST TARGET_NATIVE_PORT; do
    start_test "missing ${var}"
    run_start "${var}="
    [ "${rc}" -eq 1 ] || fail "exited ${rc}"
    grep -q "${var}" <<< "${out}" || fail "did not name ${var}: ${out}"
    no_pod
  done
}

test_args_are_mapped_to_clickhouse_benchmark() {
  start_test "arg mapping"
  run_start CONCURRENCY=8 DURATION=120 ITERATIONS=5 RANDOMIZE=true CONTINUE_ON_ERRORS=true SETTINGS=max_threads=8,use_query_cache=0
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  run_call | grep -Fq -- "--command -- sh /edl/run-benchmark.sh --host 10.0.1.5 --port 30900 --database default --user default -c 8 --timelimit 120 -i 5 --delay 10 --randomize --continue_on_errors --max_threads=8 --use_query_cache=0" ||
    fail "argv: $(run_call)"
}

test_randomize_false_and_no_settings_pass_no_flags() {
  start_test "--randomize=false"
  run_start RANDOMIZE=false TARGET_NATIVE_USER=
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  if run_call | grep -q -- "--randomize"; then fail "passed --randomize: $(run_call)"; fi
  if run_call | grep -q -- "--continue_on_errors"; then fail "passed --continue_on_errors: $(run_call)"; fi
  if run_call | grep -q -- "--user"; then fail "passed --user with no user: $(run_call)"; fi
  run_call | grep -q -- "--delay 10$" || fail "flags after --delay: $(run_call)"
}

test_workload_and_queries_together_fail() {
  start_test "exclusivity"
  echo "SELECT 1" > "${WORK}/mine.sql"
  run_start WORKLOAD=clickbench QUERIES="${WORK}/mine.sql"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "only one of --workload and --queries" <<< "${out}" || fail "message: ${out}"
  no_pod
}

test_missing_or_empty_queries_file_fails_naming_it() {
  start_test "missing --queries file"
  run_start QUERIES=nope.sql
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "nope.sql" <<< "${out}" || fail "did not name the path: ${out}"
  no_pod

  start_test "empty --queries file"
  : > "${WORK}/empty.sql"
  run_start QUERIES=empty.sql
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "empty.sql is empty" <<< "${out}" || fail "did not name the path: ${out}"
  no_pod
}

test_unknown_workload_lists_the_valid_ones() {
  start_test "unknown workload"
  run_start WORKLOAD=tpch
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "Unknown workload 'tpch'; valid workloads: clickbench" <<< "${out}" || fail "message: ${out}"
  no_pod
}

test_no_app_node_runs_no_pod() {
  start_test "no app node"
  rm -f "${STUB_STATE}/app-nodes"
  run_start
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "clickhouse-benchmark needs an app node (type=app); this cluster has none" <<< "${out}" || fail "message: ${out}"
  no_pod
}

test_missing_hits_fails_and_says_to_prepare() {
  start_test "hits missing"
  : > "${STUB_STATE}/curl-out"
  run_start
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "run prepare first" <<< "${out}" || fail "message: ${out}"
  no_pod
  grep -Fqx "http://10.0.1.5:30123/?database=default" "${STUB_STATE}/curl.1" || fail "not asked over the HTTP endpoint: $(cat "${STUB_STATE}/curl.1")"
}

test_own_queries_skip_the_hits_check_and_reach_the_pod() {
  start_test "--queries skips the hits check"
  : > "${STUB_STATE}/curl-out"
  echo "SELECT 1" > "${WORK}/mine.sql"
  run_start QUERIES=mine.sql
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  if grep -l ':30123' "${STUB_STATE}"/curl.[0-9]* > /dev/null 2>&1; then fail "checked hits"; fi
  grep -Fq "create configmap ${INSTANCE}-benchmark-run-" <<< "$(calls)" || fail "no ConfigMap: $(calls)"
  grep -Fq -- "--from-file=queries.sql=mine.sql" <<< "$(calls)" || fail "the ConfigMap does not hold the file: $(calls)"
}

test_queries_over_the_configmap_limit_fail_before_a_pod() {
  start_test "queries over the size limit"
  head -c 1000001 /dev/zero | tr '\0' 'a' > "${WORK}/big.sql"
  run_start QUERIES=big.sql
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "more than the limit of 1000000 bytes" <<< "${out}" || fail "message: ${out}"
  no_pod
}

test_the_pod_runs_on_an_app_node_with_the_queries_mounted() {
  start_test "pod placement and image"
  run_start
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  local pod
  pod="$(run_call | awk '{ print $2 }')"
  [[ "${pod}" =~ ^${INSTANCE}-benchmark-run-17000000[0-9][0-9]$ ]] || fail "pod name ${pod}"
  run_call | grep -Fq -- "--image=clickhouse/clickhouse-server:25.4" || fail "image: $(run_call)"
  run_call | grep -Fq -- "--labels=easydblab/kit=${INSTANCE},easydblab/role=benchmark-run" || fail "labels: $(run_call)"
  run_call | grep -Fq -- "--override-type=strategic" || fail "no strategic merge: $(run_call)"
  [ "$(jq -r '.spec.nodeSelector.type' "${STUB_STATE}/overrides.1")" = app ] || fail "overrides: $(cat "${STUB_STATE}/overrides.1")"
  [ "$(jq -r '.spec.volumes[0].configMap.name' "${STUB_STATE}/overrides.1")" = "${pod}" ] || fail "ConfigMap: $(cat "${STUB_STATE}/overrides.1")"
  [ "$(jq -r '.spec.containers[0].name' "${STUB_STATE}/overrides.1")" = "${pod}" ] || fail "container: $(cat "${STUB_STATE}/overrides.1")"
  grep -Fq "# label easydblab/role=benchmark-run" "${STUB_STATE}/configmap.1" || fail "ConfigMap unlabelled"
  grep -Fq "# label easydblab/kit=${INSTANCE}" "${STUB_STATE}/configmap.1" || fail "ConfigMap unlabelled"
  grep -Fq "delete configmap ${pod} --ignore-not-found" <<< "$(calls)" || fail "own ConfigMap not deleted: $(calls)"
}

test_a_successful_run_pushes_gauges_and_writes_last_run() {
  start_test "exit 0"
  run_start
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  grep -q "Run results written to ${WORK}/kit/last-run.txt" <<< "${out}" || fail "no success line: ${out}"
  grep -q "^exit code: *0$" "${WORK}/kit/last-run.txt" || fail "no exit code: $(cat "${WORK}/kit/last-run.txt")"
  grep -q "^concurrency: *4$" "${WORK}/kit/last-run.txt" || fail "no settings: $(cat "${WORK}/kit/last-run.txt")"
  grep -q "^Queries executed: 513 " "${WORK}/kit/last-run.txt" || fail "no final report: $(cat "${WORK}/kit/last-run.txt")"
  if grep -q "^Queries executed: 134 " "${WORK}/kit/last-run.txt"; then fail "an interval report in last-run.txt"; fi
  [ ! -e "${WORK}/kit/last-run.json" ] || fail "wrote last-run.json"
  if run_call | grep -q -- "--json"; then fail "passed --json, which clickhouse-benchmark does not have: $(run_call)"; fi
  local gauges
  gauges="$(pushed_gauges)"
  grep -Fqx "clickhouse_benchmark_qps ${INSTANCE} 127.443" <<< "${gauges}" || fail "final QPS not pushed: ${gauges}"
  grep -Fqx "clickhouse_benchmark_latency_p999_ms ${INSTANCE} 35" <<< "${gauges}" || fail "p999 not pushed: ${gauges}"
  [ "$(grep -c '^clickhouse_benchmark_qps ' <<< "${gauges}")" -eq 4 ] || fail "not one push per report: ${gauges}"
}

test_a_failed_run_reports_the_exit_code_and_exits_non_zero() {
  start_test "non-zero exit"
  printf '%s\n' "Running|||" "Failed|||60" > "${STUB_STATE}/state.default"
  cp "${FIXTURES}/stopped-on-error.txt" "${STUB_STATE}/full-log"
  sed 's/^/2026-10-15T10:00:00Z /' "${FIXTURES}/stopped-on-error.txt" > "${STUB_STATE}/log.1"
  echo "stale" > "${WORK}/kit/last-run.txt"
  run_start
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "failed with exit code 60" <<< "${out}" || fail "no failure line: ${out}"
  if grep -q "Run results written" <<< "${out}"; then fail "printed the success line"; fi
  grep -q "^exit code: *60$" "${WORK}/kit/last-run.txt" || fail "last-run.txt not replaced: $(cat "${WORK}/kit/last-run.txt")"
  grep -q "UNKNOWN_TABLE" "${WORK}/kit/last-run.txt" || fail "no log in last-run.txt: $(cat "${WORK}/kit/last-run.txt")"
  [ ! -e "${WORK}/kit/last-run.json" ] || fail "wrote last-run.json"
}

test_a_pod_deleted_mid_run_fails() {
  start_test "pod deleted mid-run"
  printf '%s\n' "Running|||" NOTFOUND > "${STUB_STATE}/state.default"
  run_start
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -Eq "pod ${INSTANCE}-benchmark-run-[0-9]+ was deleted" <<< "${out}" || fail "message: ${out}"
}

test_settings_entries_are_checked() {
  local entry
  for entry in max_threads max-threads=8 host=10.0.0.9; do
    start_test "--settings ${entry}"
    run_start SETTINGS="${entry}"
    [ "${rc}" -ne 0 ] || fail "exited 0"
    grep -Fq "'${entry}'" <<< "${out}" || fail "did not name ${entry}: ${out}"
    no_pod
  done
  for key in host port user password database secure queries_file query concurrency iterations timelimit delay json \
    randomize continue_on_errors cumulative reconnect query_id confidence stage max_consecutive_errors help; do
    current_test="--settings ${key}=1"
    tests_run=$((tests_run + 1))
    (
      PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" "${BASH}" -c \
        '. "$1"; . "$2"; ch_settings_flags "$3=1"' _ "${LIB}" "${KIT}/lib/args.sh" "${key}"
    ) > "${WORK}/out" 2>&1 && fail "accepted ${key}"
    grep -Fq "'${key}=1'" "${WORK}/out" || fail "did not name the entry: $(cat "${WORK}/out")"
  done
}

test_storage_policy_and_source_checks() {
  current_test="args checks"
  tests_run=$((tests_run + 1))
  # Runs the shell code $1 with the shared library and lib/args.sh sourced.
  check() {
    (EDL_KIT_LIB="${LIB}" "${BASH}" -c '. "$1"; . "$2"; '"$1" _ "${LIB}" "${KIT}/lib/args.sh") > "${WORK}/out" 2>&1
  }
  check "ch_check_storage_policy s3_main" || fail "rejected s3_main"
  check "ch_check_storage_policy 'p-1'" && fail "accepted p-1"
  grep -q "'p-1'" "${WORK}/out" || fail "did not name p-1: $(cat "${WORK}/out")"
  check "ch_check_source \"s3://b/x'y\"" && fail "accepted a quote"
  check 'ch_check_source "https://b/x\\y"' && fail "accepted a backslash"
  check "ch_check_source https://easy-db-lab-x.s3.amazonaws.com/h.parquet" || fail "rejected a plain URL"
  check 'ch_source_expression ""'
  grep -Fqx "s3('https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet', NOSIGN, 'Parquet')" "${WORK}/out" ||
    fail "default source: $(cat "${WORK}/out")"
  check 'ch_source_expression https://easy-db-lab-x.s3.amazonaws.com/h.parquet'
  grep -Fqx "s3('https://easy-db-lab-x.s3.amazonaws.com/h.parquet', 'Parquet')" "${WORK}/out" || fail "given source: $(cat "${WORK}/out")"
}

test_the_pod_runner_doubles_backslashes_and_keeps_the_exit_code() {
  current_test="run-benchmark.sh"
  tests_run=$((tests_run + 1))
  local pod="${WORK}/pod"
  rm -rf "${pod}" && mkdir -p "${pod}/bin"
  cp "${KIT}/pod/run-benchmark.sh" "${pod}/"
  printf '%s\n' "SELECT '\\1' AS x" > "${pod}/queries.sql"
  cat > "${pod}/bin/clickhouse-benchmark" <<EOF
#!/bin/sh
printf '%s\n' "\$@" > "${pod}/argv"
cat > "${pod}/stdin"
exit 7
EOF
  chmod +x "${pod}/bin/clickhouse-benchmark"
  (PATH="${pod}/bin:${PATH}" sh "${pod}/run-benchmark.sh" --host h -c 2) > "${pod}/out" 2>&1
  local code=$?
  [ "${code}" -eq 7 ] || fail "exited ${code}, not the tool's 7"
  [ "$(cat "${pod}/stdin")" = "SELECT '\\\\1' AS x" ] || fail "stdin: $(cat "${pod}/stdin")"
  [ "$(cat "${pod}/argv")" = "$(printf '%s\n' --host h -c 2)" ] || fail "argv: $(cat "${pod}/argv")"
  grep -q "exited with code 7" "${pod}/out" || fail "output: $(cat "${pod}/out")"
}

test_missing_target_native_variables_fail_before_a_pod
test_args_are_mapped_to_clickhouse_benchmark
test_randomize_false_and_no_settings_pass_no_flags
test_workload_and_queries_together_fail
test_missing_or_empty_queries_file_fails_naming_it
test_unknown_workload_lists_the_valid_ones
test_no_app_node_runs_no_pod
test_missing_hits_fails_and_says_to_prepare
test_own_queries_skip_the_hits_check_and_reach_the_pod
test_queries_over_the_configmap_limit_fail_before_a_pod
test_the_pod_runs_on_an_app_node_with_the_queries_mounted
test_a_successful_run_pushes_gauges_and_writes_last_run
test_a_failed_run_reports_the_exit_code_and_exits_non_zero
test_a_pod_deleted_mid_run_fails
test_settings_entries_are_checked
test_storage_policy_and_source_checks
test_the_pod_runner_doubles_backslashes_and_keeps_the_exit_code

echo "clickhouse-benchmark start (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
