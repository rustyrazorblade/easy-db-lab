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

# Feeds the lines in $1 through the stream handler in a subshell, as start does, then calls
# ch_report_check_pushed; prints its stderr.
handler_stderr() {
  (
    VERSION=99.1
    while IFS= read -r line; do ch_report_handle_line "${line}"; done <<< "$1"
    ch_report_check_pushed
  ) 2>&1 > /dev/null
}

test_a_report_that_parses_to_zero_warns_once_naming_the_version() {
  start_test "unreadable report"
  local block err
  block="$(printf '%s\n' "Queries executed: 40." "h:9000, queries: 40, QPS= 12" "50 pct 0.010 sec." "99.99%		later")"
  err="$(handler_stderr "${block}
${block}")"
  [ "$(grep -c '^warning: ' <<< "${err}")" -eq 1 ] || fail "not one warning: ${err}"
  grep -q "clickhouse/clickhouse-server:99.1" <<< "${err}" || fail "did not name the image: ${err}"
  grep -q "parsed to 0" <<< "${err}" || fail "message: ${err}"
}

test_a_good_report_does_not_warn() {
  start_test "readable report"
  local err
  err="$(handler_stderr "$(cat "${FIXTURES}/report.txt")")"
  [ -z "${err}" ] || fail "warned: ${err}"
}

test_a_run_that_printed_no_complete_report_warns_at_the_end() {
  start_test "no report pushed"
  local err
  err="$(handler_stderr "$(printf '%s\n' "Queries executed: 3." "h:9000, queries: 3, QPS: 1.0, RPS: 1, MiB/s: 1.")")"
  [ "$(grep -c '^warning: ' <<< "${err}")" -eq 1 ] || fail "not one warning: ${err}"
  grep -q "no report was pushed" <<< "${err}" || fail "message: ${err}"
  grep -q "clickhouse/clickhouse-server:99.1" <<< "${err}" || fail "did not name the image: ${err}"
}

test_the_final_report_of_a_real_run
test_an_interval_report_of_a_real_run
test_a_report_that_parses_to_zero_warns_once_naming_the_version
test_a_good_report_does_not_warn
test_a_run_that_printed_no_complete_report_warns_at_the_end
test_the_error_count_of_a_real_error_run
test_p999_from_the_three_decimal_spelling
test_a_missing_or_non_numeric_field_is_0
test_the_stream_handler_pushes_one_set_of_gauges_per_report

echo "clickhouse-benchmark report (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
