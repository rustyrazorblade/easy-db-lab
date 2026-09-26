#!/usr/bin/env bash
#
# Unit tests for the sysbench kit's bin/start.sh.template.
#
# The script streams a sysbench run's log and pushes each interval's figures, and the whole-run
# percentiles, as OTLP JSON gauges to the collector on the control node. Going through the collector
# is what gives the series their cluster label and tenant; Mimir has no import API.
#
# kubectl and curl are stubbed: kubectl prints a canned sysbench log, curl records every call. No
# cluster, no network.
#
# Run directly:  bash src/test/shell/sysbench-start.test.sh
# Or via gradle: ./gradlew testSysbenchStartScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEMPLATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/bin/start.sh.template"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/kit/bin" "${WORK}/calls"
  cp "${TEMPLATE}" "${WORK}/kit/bin/start.sh"
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
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
case " \$* " in
  *" logs "*) cat "${WORK}/sysbench.log" ;;
esac
exit 0
EOF
  cat > "${WORK}/stubs/curl" <<EOF
#!/usr/bin/env bash
n=\$(ls "${WORK}/calls" | wc -l | tr -d ' ')
printf '%s\n' "\$@" > "${WORK}/calls/\${n}.args"
exit 0
EOF
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/curl"
}

run_script() {
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" KIT_NAME=sb1 KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        TARGET_PG_HOST=10.0.0.9 TARGET_PG_PORT=5432 TARGET_PG_USER=u TARGET_PG_DATABASE=d \
        WORKLOAD=oltp_read_write THREADS=4 DURATION=20 RATE=0 SKIP_TRX=off RAND_TYPE=special \
        bash "${WORK}/kit/bin/start.sh"
  ) > "${WORK}/out.txt" 2>&1
}

# The body of call $1: the argument after --data-binary.
body_of() {
  awk 'prev == "--data-binary" { print; exit } { prev = $0 }' "$1"
}

# Every gauge in every pushed body as "name kit value", one per line.
pushed_gauges() {
  for call in "${WORK}"/calls/*.args; do
    body_of "${call}" | jq -r '
      .resourceMetrics[].scopeMetrics[].metrics[]
      | .name as $name
      | .gauge.dataPoints[]
      | "\($name) \(.attributes[] | select(.key == "kit") | .value.stringValue) \(.asDouble * 1)"'
  done
}

test_pushes_otlp_json_to_the_collector() {
  tests_run=$((tests_run + 1))
  setup
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  local calls
  calls=$(ls "${WORK}/calls" | wc -l | tr -d ' ')
  [ "${calls}" -ge 3 ] || fail "expected a push per interval and one for the run, got ${calls}"
  for call in "${WORK}"/calls/*.args; do
    grep -Fqx 'http://10.0.0.5:4318/v1/metrics' "${call}" || fail "push not sent to the collector's OTLP HTTP port: $(cat "${call}")"
    grep -Fqx 'Content-Type: application/json' "${call}" || fail "push is not OTLP JSON: $(cat "${call}")"
    body_of "${call}" | jq -e . > /dev/null || fail "push body is not valid JSON: $(body_of "${call}")"
  done
  if grep -q ':8428' "${WORK}"/calls/*.args; then
    fail "must not push to the VictoriaMetrics import API"
  fi
}

test_pushes_interval_and_run_figures_labelled_with_the_kit() {
  tests_run=$((tests_run + 1))
  setup
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  local gauges
  gauges="$(pushed_gauges)"

  for expected in "sysbench_tps sb1 120.5" "sysbench_qps sb1 2605" "sysbench_lat_p99_ms sb1 45.79" \
    "sysbench_errors_per_second sb1 0.1" "sysbench_lat_p50_ms sb1 20" "sysbench_lat_p95_ms sb1 45"; do
    grep -Fqx "${expected}" <<< "${gauges}" || fail "expected gauge '${expected}', pushed: ${gauges}"
  done
}

test_pushes_otlp_json_to_the_collector
test_pushes_interval_and_run_figures_labelled_with_the_kit

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
