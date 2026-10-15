#!/usr/bin/env bash
#
# Unit tests for the clickhouse-benchmark kit's report parser, lib/report.sh.
#
# The fixtures under fixtures/clickhouse-benchmark/ were captured from real clickhouse-benchmark
# runs (ClickHouse 26.9, stderr included): report.txt is a run with --delay 1 and --randomize,
# error-run.txt one with --continue_on_errors and a query that fails every time. No Docker.
#
# Run directly:  bash src/test/shell/clickhouse-benchmark-report.test.sh
# Or via gradle: ./gradlew testClickhouseBenchmarkReport

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/clickhouse-benchmark"
FIXTURES="${ROOT}/src/test/shell/fixtures/clickhouse-benchmark"
# shellcheck source=/dev/null
. "${KIT}/lib/report.sh"

tests_run=0
tests_failed=0
current_test=""
PUSHED=""

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  PUSHED=""
}

# Records each push instead of sending it.
edl_push_gauges() {
  PUSHED="${PUSHED}$*
"
}

# Fails unless the space-separated numbers $1 and $2 are equal value for value.
same_numbers() {
  awk -v got="$1" -v want="$2" 'BEGIN {
    n = split(got, g, " "); m = split(want, w, " ")
    if (n != m) exit 1
    for (i = 1; i <= n; i++) if (g[i] + 0 != w[i] + 0) exit 1
  }'
}

test_the_final_report_of_a_real_run() {
  start_test "final report"
  local got
  got="$(ch_final_report < "${FIXTURES}/report.txt" | parse_report_block)"
  # QPS, rows/s, MiB/s, queries, errors, p50, p90, p95, p99, p99.9 (ms)
  same_numbers "${got}" "127.443 637215746.961 5230.110 513 0 13 20 22 26 35" || fail "parsed ${got}"
}

test_an_interval_report_of_a_real_run() {
  start_test "interval report"
  local got
  got="$(awk '/^Queries executed: 134 /, /^99\.99%/' "${FIXTURES}/report.txt" | parse_report_block)"
  same_numbers "${got}" "133.225 666125453.323 5537.250 134 0 13 17 18 24 38" || fail "parsed ${got}"
}

test_the_error_count_of_a_real_error_run() {
  start_test "error run"
  local got
  got="$(ch_final_report < "${FIXTURES}/error-run.txt" | parse_report_block)"
  same_numbers "${got}" "3.951 19754000.352 150.711 30 15 194 325 325 327 327" || fail "parsed ${got}"
}

test_p999_from_the_three_decimal_spelling() {
  start_test "99.900% spelling"
  local got
  got="$(printf '%s\n' "Queries executed: 5." "h:9000, queries: 5, QPS: 1.0, RPS: 2.0, MiB/s: 3.0, result RPS: 9, result MiB/s: 9." \
    "50.000%		0.010 sec." "99.900%		0.250 sec." "99.990%		0.300 sec." | parse_report_block)"
  same_numbers "${got}" "1 2 3 5 0 10 0 0 0 250" || fail "parsed ${got}"
}

test_a_missing_or_non_numeric_field_is_0() {
  start_test "missing and non-numeric fields"
  local got
  got="$(printf '%s\n' "Queries executed: lots." "h:9000, queries: 5, QPS: nan, MiB/s: 3.5." "50%		abc sec." | parse_report_block)"
  same_numbers "${got}" "0 0 3.5 0 0 0 0 0 0 0" || fail "parsed ${got}"
  got="$(printf '' | parse_report_block)"
  same_numbers "${got}" "0 0 0 0 0 0 0 0 0 0" || fail "an empty report parsed as ${got}"
}

test_the_stream_handler_pushes_one_set_of_gauges_per_report() {
  start_test "stream handler"
  local line printed
  printed="$(mktemp)"
  # The loop runs in this shell, so the stub's PUSHED survives it.
  while IFS= read -r line; do ch_report_handle_line "${line}"; done < "${FIXTURES}/report.txt" > "${printed}"
  [ "$(printf '%s' "${PUSHED}" | grep -c '^clickhouse-benchmark ')" -eq 4 ] || fail "pushed: ${PUSHED}"
  grep -q 'clickhouse_benchmark_qps 127.443 ' <<< "${PUSHED}" || fail "the final report was not pushed: ${PUSHED}"
  grep -q 'clickhouse_benchmark_latency_p999_ms 35.000$' <<< "${PUSHED}" || fail "no p999 gauge: ${PUSHED}"
  for gauge in qps rows_per_second mib_per_second queries_executed errors latency_p50_ms latency_p90_ms \
    latency_p95_ms latency_p99_ms latency_p999_ms; do
    [ "$(printf '%s' "${PUSHED}" | head -1 | grep -o "clickhouse_benchmark_${gauge} " | wc -l | tr -d ' ')" -eq 1 ] ||
      fail "the first push lacks clickhouse_benchmark_${gauge}: $(printf '%s' "${PUSHED}" | head -1)"
  done
  cmp -s "${printed}" "${FIXTURES}/report.txt" || fail "the handler did not print every line"
  rm -f "${printed}"
}

test_the_final_report_of_a_real_run
test_an_interval_report_of_a_real_run
test_the_error_count_of_a_real_error_run
test_p999_from_the_three_decimal_spelling
test_a_missing_or_non_numeric_field_is_0
test_the_stream_handler_pushes_one_set_of_gauges_per_report

echo "clickhouse-benchmark report (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
