#!/usr/bin/env bash
#
# Unit tests for the clickhouse-benchmark kit's bin/prepare.sh.template and the pod-side loader
# pod/prepare-load.sh.
#
# prepare runs with the bash that runs this file ($BASH), against the stubs in kit-stubs.sh.
# prepare-load.sh runs under sh with a stub clickhouse-client that records every statement and
# fails the one that matches FAIL_ON. No cluster, no network.
#
# Run directly:  bash src/test/shell/clickhouse-benchmark-prepare.test.sh
# Or via gradle: ./gradlew testClickhouseBenchmarkPrepare

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/clickhouse-benchmark"
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
cp -R "${KIT}/lib" "${KIT}/pod" "${KIT}/workloads" "${WORK}/kit/"
cp "${KIT}/bin/prepare.sh.template" "${WORK}/kit/bin/prepare.sh"
INSTANCE=clickhouse-benchmark-clickhouse

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# Starts a case with an app node, no hits table, and a prepare pod that exits 0.
start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
  echo "node/app0" > "${STUB_STATE}/app-nodes"
  : > "${STUB_STATE}/curl-out"
  printf '%s\n' "Running|||" "Succeeded|||0" > "${STUB_STATE}/state.default"
  echo "2026-10-15T10:00:00Z hits holds 99997497 rows on every replica (3 replicas)." > "${STUB_STATE}/log.1"
}

# Runs prepare.sh with the default args, overridden by the NAME=value words in $@.
run_prepare() {
  (
    cd "${WORK}" &&
      env PATH="${WORK}/stubs:${PATH}" KIT_NAME="${INSTANCE}" KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        EDL_KIT_LIB="${LIB}" EDL_POLL_SECONDS=0 TARGET=clickhouse VERSION=25.4 \
        TARGET_NATIVE_HOST=10.0.1.5 TARGET_NATIVE_PORT=30900 TARGET_NATIVE_USER=default TARGET_NATIVE_DATABASE=default \
        TARGET_HTTP_URL=http://10.0.1.5:30123 \
        WORKLOAD=clickbench SOURCE= STORAGE_POLICY=default RECREATE=false \
        "$@" "${BASH}" "${WORK}/kit/bin/prepare.sh"
  ) > "${WORK}/out" 2>&1
  rc=$?
  out="$(cat "${WORK}/out")"
}

run_call() { grep '^run ' "${STUB_STATE}/kubectl.calls" 2>/dev/null; }
no_pod() { [ -z "$(run_call)" ] || fail "created a pod: $(run_call)"; }

test_missing_target_variables_fail_before_a_pod() {
  local var
  for var in TARGET_NATIVE_HOST TARGET_NATIVE_PORT TARGET_HTTP_URL; do
    start_test "missing ${var}"
    run_prepare "${var}="
    [ "${rc}" -eq 1 ] || fail "exited ${rc}"
    grep -q "${var}" <<< "${out}" || fail "did not name ${var}: ${out}"
    no_pod
  done
}

test_a_failed_hits_check_shows_the_server_error() {
  start_test "hits check fails"
  echo 22 > "${STUB_STATE}/curl-exit"
  echo "Code: 516. DB::Exception: default: Authentication failed" > "${STUB_STATE}/curl-out"
  run_prepare
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "The query against http://10.0.1.5:30123 failed" <<< "${out}" || fail "message: ${out}"
  grep -q "Code: 516. DB::Exception: default: Authentication failed" <<< "${out}" || fail "no server error: ${out}"
  no_pod
  if grep -q "create configmap" "${STUB_STATE}/kubectl.calls"; then fail "created a ConfigMap"; fi
}

test_a_prepare_pod_that_cannot_pull_its_image_is_deleted() {
  start_test "pull error deletes the pod"
  echo "Pending|ErrImagePull|pull access denied|" > "${STUB_STATE}/state.default"
  run_prepare
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -Eq "^delete pod ${INSTANCE}-prepare-[0-9]+ --ignore-not-found$" "${STUB_STATE}/kubectl.calls" ||
    fail "left the pod: $(cat "${STUB_STATE}/kubectl.calls")"
}

test_an_active_run_refuses_and_names_the_pod() {
  start_test "active run"
  echo "${INSTANCE}-benchmark-run-1700000000 Running" > "${STUB_STATE}/pods"
  run_prepare RECREATE=true
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "${INSTANCE}-benchmark-run-1700000000" <<< "${out}" || fail "did not name the pod: ${out}"
  grep -q "run stop" <<< "${out}" || fail "did not say to run stop: ${out}"
  no_pod
}

test_a_finished_run_does_not_block() {
  start_test "finished run"
  echo "${INSTANCE}-benchmark-run-1700000000 Succeeded" > "${STUB_STATE}/pods"
  run_prepare
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${out}"
}

test_existing_hits_without_recreate_names_table_and_policy() {
  start_test "hits exists"
  echo "s3_main" > "${STUB_STATE}/curl-out"
  run_prepare
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "hits already exists" <<< "${out}" || fail "did not name hits: ${out}"
  grep -q "'s3_main'" <<< "${out}" || fail "did not name the policy: ${out}"
  grep -q -- "--recreate" <<< "${out}" || fail "did not mention --recreate: ${out}"
  no_pod
}

test_recreate_runs_the_pod_with_the_drop_and_the_default_source() {
  start_test "--recreate"
  echo "default" > "${STUB_STATE}/curl-out"
  run_prepare RECREATE=true STORAGE_POLICY=s3_main
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  run_call | grep -Fq -- "--image=clickhouse/clickhouse-server:25.4" || fail "image: $(run_call)"
  run_call | grep -Fq -- "--labels=easydblab/kit=${INSTANCE},easydblab/role=prepare" || fail "labels: $(run_call)"
  run_call | grep -Fq -- "--command -- sh /edl/prepare-load.sh 10.0.1.5 30900 default default s3_main true s3('https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet', NOSIGN, 'Parquet')" ||
    fail "argv: $(run_call)"
  [ "$(jq -r '.spec.nodeSelector.type' "${STUB_STATE}/overrides.1")" = app ] || fail "not on an app node"
  grep -q "hits holds 99997497 rows" <<< "${out}" || fail "did not show the pod log: ${out}"
}

test_a_given_source_is_read_with_the_role() {
  start_test "--source"
  run_prepare SOURCE=https://easy-db-lab-x.s3.amazonaws.com/hits.parquet
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  run_call | grep -Fq "s3('https://easy-db-lab-x.s3.amazonaws.com/hits.parquet', 'Parquet')" || fail "argv: $(run_call)"
}

test_no_app_node_fails() {
  start_test "no app node"
  rm -f "${STUB_STATE}/app-nodes"
  run_prepare
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "clickhouse-benchmark needs an app node (type=app); this cluster has none" <<< "${out}" || fail "message: ${out}"
  no_pod
}

test_bad_values_fail_before_a_pod() {
  local pair
  for pair in "SOURCE=s3://b/it's" "SOURCE=s3://b/a\\b" "STORAGE_POLICY=s3-main" "WORKLOAD=tpch"; do
    start_test "bad ${pair}"
    run_prepare "${pair}"
    [ "${rc}" -ne 0 ] || fail "exited 0"
    grep -Fq "${pair#*=}" <<< "${out}" || fail "did not name the value: ${out}"
    no_pod
    [ ! -f "${STUB_STATE}/kubectl.calls" ] || fail "called the cluster: $(cat "${STUB_STATE}/kubectl.calls")"
  done
}

test_a_failed_prepare_pod_fails() {
  start_test "non-zero pod exit"
  printf '%s\n' "Running|||" "Failed|||1" > "${STUB_STATE}/state.default"
  echo "2026-10-15T10:00:00Z The load failed. hits may be partly loaded; run prepare --recreate to reload it." > "${STUB_STATE}/log.1"
  run_prepare
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "exited with code 1" <<< "${out}" || fail "message: ${out}"
  grep -q "hits may be partly loaded" <<< "${out}" || fail "did not show the pod log: ${out}"
}

# --- pod/prepare-load.sh ---

LOADER="${WORK}/loader"
mkdir -p "${LOADER}/bin"
cp "${KIT}/pod/prepare-load.sh" "${KIT}/workloads/clickbench/create-hits.sql" "${LOADER}/"
cat > "${LOADER}/bin/clickhouse-client" <<EOF
#!/bin/sh
query=""
prev=""
for a in "\$@"; do
  [ "\${prev}" = --query ] && query="\${a}"
  prev="\${a}"
done
[ -n "\${query}" ] || query="\$(cat)"
printf '%s\n' "\$*" >> "${LOADER}/argv"
printf '%s\n---\n' "\${query}" >> "${LOADER}/statements"
case "\${query}" in
  *"\${FAIL_ON:-<never>}"*) echo "Code: 999. DB::Exception: stub failure" >&2; exit 1 ;;
  *clusterAllReplicas*) printf '%s' "\${COUNTS}" ;;
  *system.settings*) printf '%s' "\${SETTING_PRESENT:-0}" ;;
esac
exit 0
EOF
chmod +x "${LOADER}/bin/clickhouse-client"

# Runs prepare-load.sh with recreate $1 and replica counts $2, failing the statement holding FAIL_ON.
run_loader() {
  rm -f "${LOADER}/argv" "${LOADER}/statements"
  (PATH="${LOADER}/bin:${PATH}" COUNTS="$2" sh "${LOADER}/prepare-load.sh" 10.0.1.5 30900 default default s3_main "$1" \
    "${3:-s3('https://x/hits.parquet', NOSIGN, 'Parquet')}") > "${LOADER}/out" 2>&1
  rc=$?
  out="$(cat "${LOADER}/out")"
}

statements() { cat "${LOADER}/statements" 2>/dev/null; }

test_the_loader_drops_creates_loads_syncs_and_counts_in_order() {
  current_test="loader --recreate order"
  tests_run=$((tests_run + 1))
  FAIL_ON="" run_loader true "$(printf 'r0\t100\nr1\t100\nr2\t100\n')"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  local order
  order="$(statements | grep -n -E '^(DROP TABLE IF EXISTS hits ON CLUSTER clickhouse SYNC|CREATE TABLE hits ON CLUSTER clickhouse|INSERT INTO hits SELECT \* FROM s3|SYSTEM SYNC REPLICA ON CLUSTER clickhouse hits|SELECT hostName\(\) AS replica, count\(\) FROM clusterAllReplicas\(.clickhouse., currentDatabase\(\), .hits.\))' | cut -d: -f2- | cut -c1-20)"
  [ "$(printf '%s\n' "${order}" | wc -l | tr -d ' ')" -eq 5 ] || fail "statements: $(statements)"
  [ "$(printf '%s\n' "${order}" | head -1)" = "DROP TABLE IF EXISTS" ] || fail "did not drop first: ${order}"
  statements | grep -Fqx "SETTINGS storage_policy = 's3_main'" || fail "no storage policy: $(statements)"
  [ "$(grep -c -- '--distributed_ddl_task_timeout=-1' "${LOADER}/argv")" -eq "$(wc -l < "${LOADER}/argv")" ] ||
    fail "a statement without the negative DDL timeout: $(cat "${LOADER}/argv")"
  grep -q -- "--receive_timeout=86400 --send_timeout=86400 --max_execution_time=0" "${LOADER}/argv" || fail "client timeouts: $(cat "${LOADER}/argv")"
  grep -q "hits holds 100 rows on every replica (3 replicas)" <<< "${out}" || fail "output: ${out}"
}

# The client argv line of the INSERT, which holds its settings.
insert_argv() { grep -- "--query INSERT INTO hits" "${LOADER}/argv"; }

test_a_role_source_load_allows_server_credentials_when_the_server_has_the_setting() {
  current_test="loader --source, setting present"
  tests_run=$((tests_run + 1))
  SETTING_PRESENT=1 FAIL_ON="" run_loader false "$(printf 'r0\t5\n')" "s3('https://easy-db-lab-x.s3.amazonaws.com/h.parquet', 'Parquet')"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  statements | grep -Fq "FROM system.settings WHERE name = 's3_allow_server_credentials_in_user_queries'" || fail "did not ask: $(statements)"
  insert_argv | grep -Fq -- "--s3_allow_server_credentials_in_user_queries=1" || fail "INSERT argv: $(insert_argv)"
}

test_a_role_source_load_on_a_server_without_the_setting_does_not_pass_it() {
  current_test="loader --source, setting absent"
  tests_run=$((tests_run + 1))
  SETTING_PRESENT=0 FAIL_ON="" run_loader false "$(printf 'r0\t5\n')" "s3('https://easy-db-lab-x.s3.amazonaws.com/h.parquet', 'Parquet')"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  if insert_argv | grep -q "s3_allow_server_credentials_in_user_queries"; then fail "INSERT argv: $(insert_argv)"; fi
}

test_the_anonymous_default_load_never_allows_server_credentials() {
  current_test="loader NOSIGN default"
  tests_run=$((tests_run + 1))
  SETTING_PRESENT=1 FAIL_ON="" run_loader false "$(printf 'r0\t5\n')"
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: ${out}"; return; }
  if grep -q "s3_allow_server_credentials_in_user_queries" "${LOADER}/argv" "${LOADER}/statements"; then
    fail "the NOSIGN load used server credentials: $(cat "${LOADER}/argv")"
  fi
}

test_the_loader_never_drops_without_recreate() {
  current_test="loader without --recreate"
  tests_run=$((tests_run + 1))
  FAIL_ON="" run_loader false "$(printf 'r0\t5\n')"
  [ "${rc}" -eq 0 ] || fail "exited ${rc}: ${out}"
  if statements | grep -q '^DROP'; then fail "dropped: $(statements)"; fi
}

test_replica_count_mismatch_fails_with_each_count() {
  current_test="loader replica mismatch"
  tests_run=$((tests_run + 1))
  FAIL_ON="" run_loader false "$(printf 'r0\t100\nr1\t90\n')"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "r0: 100 rows" <<< "${out}" || fail "output: ${out}"
  grep -q "r1: 90 rows" <<< "${out}" || fail "output: ${out}"
}

test_a_failed_insert_says_partly_loaded_and_never_drops() {
  current_test="loader failed INSERT"
  tests_run=$((tests_run + 1))
  FAIL_ON="INSERT INTO hits" run_loader false "$(printf 'r0\t5\n')"
  [ "${rc}" -ne 0 ] || fail "exited 0"
  grep -q "stub failure" <<< "${out}" || fail "did not show the server error: ${out}"
  grep -q "hits may be partly loaded; run prepare --recreate to reload it" <<< "${out}" || fail "output: ${out}"
  if statements | grep -q '^DROP'; then fail "dropped hits: $(statements)"; fi
  if statements | grep -q '^SYSTEM SYNC'; then fail "went on after the failed load: $(statements)"; fi
}

test_missing_target_variables_fail_before_a_pod
test_a_failed_hits_check_shows_the_server_error
test_a_prepare_pod_that_cannot_pull_its_image_is_deleted
test_an_active_run_refuses_and_names_the_pod
test_a_finished_run_does_not_block
test_existing_hits_without_recreate_names_table_and_policy
test_recreate_runs_the_pod_with_the_drop_and_the_default_source
test_a_given_source_is_read_with_the_role
test_no_app_node_fails
test_bad_values_fail_before_a_pod
test_a_failed_prepare_pod_fails
test_the_loader_drops_creates_loads_syncs_and_counts_in_order
test_the_loader_never_drops_without_recreate
test_a_role_source_load_allows_server_credentials_when_the_server_has_the_setting
test_a_role_source_load_on_a_server_without_the_setting_does_not_pass_it
test_the_anonymous_default_load_never_allows_server_credentials
test_replica_count_mismatch_fails_with_each_count
test_a_failed_insert_says_partly_loaded_and_never_drops

echo "clickhouse-benchmark prepare (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
