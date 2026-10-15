# shellcheck shell=bash
#
# Reads clickhouse-benchmark's reports. The tool prints one report every --delay seconds and a final
# one at the end, to stderr, which `kubectl logs` merges into the pod log. A report looks like:
#
#   Queries executed: 513 (17100%).
#
#   localhost:9000, queries: 513, errors: 2, QPS: 127.443, RPS: 637215746.961, MiB/s: 5230.110, ...
#
#   0%		0.007 sec.
#   ...
#   50%		0.013 sec.
#   ...
#   99.9%		0.035 sec.
#   99.99%		0.038 sec.
#
# `errors:` appears only when a query failed. Older releases print percentiles as 99.900% and
# 99.990%, so percentiles are matched by value. Needs the shared library (edl_push_gauges).

# Reads one report on stdin and prints, space-separated: QPS, rows/s, MiB/s, queries executed,
# errors, and the p50, p90, p95, p99 and p99.9 latency in milliseconds. A missing or non-numeric
# field is printed as 0.
parse_report_block() {
  awk '
    function num(v) { return v ~ /^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$/ ? v + 0 : 0 }
    function clean(v) { sub(/[,.]$/, "", v); return v }
    /^Queries executed:/ { queries = num(clean($3)) }
    / queries: / {
      for (i = 1; i < NF; i++) {
        if ($(i - 1) == "result") continue
        if ($i == "QPS:") qps = num(clean($(i + 1)))
        if ($i == "RPS:") rps = num(clean($(i + 1)))
        if ($i == "MiB/s:") mibs = num(clean($(i + 1)))
        if ($i == "errors:") errors = num(clean($(i + 1)))
      }
    }
    /^[0-9.]+%[ \t]/ {
      pct = $1; sub(/%$/, "", pct); pct += 0
      ms = num($2) * 1000
      if (pct == 50) p50 = ms
      if (pct == 90) p90 = ms
      if (pct == 95) p95 = ms
      if (pct == 99) p99 = ms
      if (pct == 99.9) p999 = ms
    }
    # printf, not print: print would round a large rows/s figure to six significant digits.
    END { printf "%.3f %.3f %.3f %d %d %.3f %.3f %.3f %.3f %.3f\n", qps, rps, mibs, queries, errors, p50, p90, p95, p99, p999 }
  '
}

# Set when a report has begun, when one has been pushed, and when the parse warning was printed.
CH_REPORT_SEEN=""
CH_REPORT_PUSHED=""
CH_REPORT_PARSE_WARNED=""

# The image the reports come from, named in a warning: a changed report format is a new release.
ch_report_image() {
  printf 'clickhouse/clickhouse-server:%s\n' "${VERSION:-unknown}"
}

# Pushes the ten gauges of the report in $1, each labelled with the kit instance. Warns once when a
# report that ran queries parses to no QPS and no latency, which means its format changed.
ch_push_report() {
  local qps rps mibs queries errors p50 p90 p95 p99 p999
  read -r qps rps mibs queries errors p50 p90 p95 p99 p999 <<< "$(printf '%s\n' "$1" | parse_report_block)"
  CH_REPORT_PUSHED=1
  if [ -z "${CH_REPORT_PARSE_WARNED}" ] &&
    awk -v q="${queries}" -v rest="${qps} ${p50} ${p90} ${p95} ${p99} ${p999}" \
      'BEGIN { n = split(rest, v, " "); for (i = 1; i <= n; i++) if (v[i] + 0 != 0) exit 1; exit !(q + 0 > 0) }'; then
    CH_REPORT_PARSE_WARNED=1
    printf 'warning: a clickhouse-benchmark report from %s parsed to 0 QPS and 0 latency although it ran %s queries; its format may have changed, so the dashboard shows 0\n' \
      "$(ch_report_image)" "${queries}" >&2
  fi
  edl_push_gauges clickhouse-benchmark \
    clickhouse_benchmark_qps "${qps}" \
    clickhouse_benchmark_rows_per_second "${rps}" \
    clickhouse_benchmark_mib_per_second "${mibs}" \
    clickhouse_benchmark_queries_executed "${queries}" \
    clickhouse_benchmark_errors "${errors}" \
    clickhouse_benchmark_latency_p50_ms "${p50}" \
    clickhouse_benchmark_latency_p90_ms "${p90}" \
    clickhouse_benchmark_latency_p95_ms "${p95}" \
    clickhouse_benchmark_latency_p99_ms "${p99}" \
    clickhouse_benchmark_latency_p999_ms "${p999}"
}

CH_REPORT_BLOCK=""

# The handler for edl_follow_pod_logs: prints each line, collects each report, and pushes its gauges
# when the report's last percentile line arrives.
ch_report_handle_line() {
  printf '%s\n' "$1"
  case "$1" in
    "Queries executed:"*)
      CH_REPORT_BLOCK="$1"
      CH_REPORT_SEEN=1
      return 0
      ;;
  esac
  [ -n "${CH_REPORT_BLOCK}" ] || return 0
  CH_REPORT_BLOCK="${CH_REPORT_BLOCK}
$1"
  case "$1" in
    99.99%* | 99.990%*)
      ch_push_report "${CH_REPORT_BLOCK}"
      CH_REPORT_BLOCK=""
      ;;
  esac
  return 0
}

# Called when the stream ends: warns when a report began but none ended with its last percentile
# line, so no gauge was pushed for the run.
ch_report_check_pushed() {
  if [ -n "${CH_REPORT_SEEN}" ] && [ -z "${CH_REPORT_PUSHED}" ]; then
    printf 'warning: clickhouse-benchmark from %s printed a report, but no report was pushed: none ended with a 99.99%% line; its format may have changed, so the dashboard shows nothing for this run\n' \
      "$(ch_report_image)" >&2
  fi
  return 0
}

# Prints the last report in the full log on stdin: from its last "Queries executed:" line to the end
# of its percentile lines.
ch_final_report() {
  awk '
    /^Queries executed:/ { block = ""; inblock = 1 }
    inblock { block = block $0 "\n" }
    inblock && /^99\.990?%/ { last = block; inblock = 0 }
    END { printf "%s", last }
  '
}
