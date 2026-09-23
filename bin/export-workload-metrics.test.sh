#!/usr/bin/env bash
#
# Unit tests for bin/export-workload-metrics.
#
# The catalog must list only series that are live now. VictoriaMetrics answers /api/v1/series
# from its day-granular index, so a series from a pod that died hours ago (even with start=-3m)
# comes back. The script therefore runs an instant query, last_over_time over a short window,
# which returns only series with a sample inside that window.
#
# curl is stubbed: it records its arguments and prints a canned VictoriaMetrics response. No
# cluster, no network.
#
# Run directly:  bin/export-workload-metrics.test.sh
# Or via gradle: ./gradlew testExportWorkloadMetrics

set -uo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/export-workload-metrics"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

# Sets up a cluster working directory with env.sh and a stub curl that prints $1 and exits $2.
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs"
  echo 'export CONTROL_HOST_PRIVATE=10.0.0.5' > "${WORK}/env.sh"
  printf '%s' "$1" > "${WORK}/response.json"
  cat > "${WORK}/stubs/curl" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$@" > "${WORK}/curl.args"
cat "${WORK}/response.json"
exit ${2:-0}
EOF
  chmod +x "${WORK}/stubs/curl"
}

run_script() {
  (cd "${WORK}" && PATH="${WORK}/stubs:${PATH}" "${SCRIPT}" "$@") > "${WORK}/out.txt" 2>&1
}

LIVE_RESPONSE='{"status":"success","data":{"resultType":"vector","result":[
  {"metric":{"__name__":"memcached_up","job":"memcached","instance":"memcached-abc"},"value":[1,"1"]},
  {"metric":{"__name__":"memcached_current_items","job":"memcached","instance":"memcached-abc"},"value":[1,"42"]}
]}}'

test_queries_only_series_live_in_the_window() {
  tests_run=$((tests_run + 1))
  setup "${LIVE_RESPONSE}"
  run_script memcached || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  grep -Fqx 'http://10.0.0.5:8428/api/v1/query' "${WORK}/curl.args" \
    || fail "expected an instant query at /api/v1/query, got: $(cat "${WORK}/curl.args")"
  grep -Fqx 'query=last_over_time({job="memcached"}[5m]) keep_metric_names' "${WORK}/curl.args" \
    || fail "expected last_over_time over 5m keeping metric names, got: $(cat "${WORK}/curl.args")"
  if grep -q '/api/v1/series' "${WORK}/curl.args"; then
    fail "must not use /api/v1/series, which returns the whole day's series"
  fi
}

test_writes_each_live_series_as_name_and_labels() {
  tests_run=$((tests_run + 1))
  setup "${LIVE_RESPONSE}"
  run_script memcached || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  local catalog="${WORK}/memcached/metrics-catalog.json"
  [ "$(jq -r '.workload' "${catalog}")" = "memcached" ] || fail "workload field is wrong"
  [ "$(jq -c '[.series[].name]' "${catalog}")" = '["memcached_up","memcached_current_items"]' ] \
    || fail "series names are wrong: $(jq -c '.series' "${catalog}")"
  [ "$(jq -c '.series[0].labels' "${catalog}")" = '{"job":"memcached","instance":"memcached-abc"}' ] \
    || fail "labels must be the metric's labels without __name__: $(jq -c '.series[0]' "${catalog}")"
  grep -q "Wrote 2 series" "${WORK}/out.txt" || fail "expected 'Wrote 2 series', got: $(cat "${WORK}/out.txt")"
}

test_no_live_series_fails_without_writing_a_catalog() {
  tests_run=$((tests_run + 1))
  setup '{"status":"success","data":{"resultType":"vector","result":[]}}'
  if run_script memcached; then
    fail "expected a non-zero exit when no series are live"
  fi
  [ ! -f "${WORK}/memcached/metrics-catalog.json" ] || fail "must not write an empty catalog"
}

test_error_status_fails() {
  tests_run=$((tests_run + 1))
  setup '{"status":"error","errorType":"bad_data","error":"boom"}'
  if run_script memcached; then
    fail "expected a non-zero exit on an error status"
  fi
  grep -q "status=error" "${WORK}/out.txt" || fail "expected the status in the error, got: $(cat "${WORK}/out.txt")"
}

test_workload_name_is_required() {
  tests_run=$((tests_run + 1))
  setup "${LIVE_RESPONSE}"
  if run_script; then
    fail "expected a non-zero exit with no workload name"
  fi
}

test_queries_only_series_live_in_the_window
test_writes_each_live_series_as_name_and_labels
test_no_live_series_fails_without_writing_a_catalog
test_error_status_fails
test_workload_name_is_required

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
