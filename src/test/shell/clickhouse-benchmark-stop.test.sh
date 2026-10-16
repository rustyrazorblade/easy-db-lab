#!/usr/bin/env bash
#
# Unit tests for the clickhouse-benchmark kit's bin/stop.sh.template: it deletes this instance's
# pods and ConfigMaps by label, and nothing else. kubectl is stubbed (kit-stubs.sh).
#
# Run directly:  bash src/test/shell/clickhouse-benchmark-stop.test.sh
# Or via gradle: ./gradlew testClickhouseBenchmarkStop

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEMPLATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/clickhouse-benchmark/bin/stop.sh.template"
# shellcheck source=kit-stubs.sh
. "${ROOT}/src/test/shell/kit-stubs.sh"

tests_run=0
tests_failed=0
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export STUB_STATE="${WORK}/state"
write_kit_stubs "${WORK}/stubs"
reset_kit_stub_state

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

test_stop_deletes_only_this_instances_pods_and_configmaps() {
  tests_run=$((tests_run + 1))
  (PATH="${WORK}/stubs:${PATH}" KIT_NAME=clickhouse-benchmark-clickhouse KUBECONFIG="${WORK}/kubeconfig" \
    "${BASH}" "${TEMPLATE}") > "${WORK}/out" 2>&1 || fail "exited non-zero: $(cat "${WORK}/out")"
  [ "$(cat "${STUB_STATE}/kubectl.calls")" = \
    "--kubeconfig=${WORK}/kubeconfig delete pods,configmaps -l easydblab/kit=clickhouse-benchmark-clickhouse --ignore-not-found" ] ||
    fail "kubectl calls: $(cat "${STUB_STATE}/kubectl.calls")"
}

test_stop_deletes_only_this_instances_pods_and_configmaps

echo "clickhouse-benchmark stop (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
