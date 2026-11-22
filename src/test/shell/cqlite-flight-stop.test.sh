#!/usr/bin/env bash
#
# Unit tests for the cqlite-flight kit's bin/stop.sh.template.
#
# stop deletes the kit's objects by label selector, naming both the kit and the role
# (easydblab/kit=cqlite-flight,easydblab/role=<role>), never by name and never by the kit label
# alone. kubectl is stubbed (kit-stubs.sh). No cluster.
#
# Run directly:  bash src/test/shell/cqlite-flight-stop.test.sh
# Or via gradle: ./gradlew testCqliteFlightStopScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT_SRC="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-flight"
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
cp "${KIT_SRC}/bin/stop.sh.template" "${WORK}/kit/bin/stop.sh"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  reset_kit_stub_state
}

run_script() {
  env PATH="${WORK}/stubs:${PATH}" KUBECONFIG="${WORK}/kubeconfig" "${BASH}" "${WORK}/kit/bin/stop.sh" > "${WORK}/out.txt" 2>&1
}

deletes() { grep '^delete ' "${STUB_STATE}/kubectl.calls" 2>/dev/null; }

test_stop_deletes_every_kind_by_kit_and_role() {
  start_test "kit and role selector"
  run_script || { fail "script exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  [ -n "$(deletes)" ] || { fail "deleted nothing"; return; }
  local call kind
  while IFS= read -r call; do
    case " ${call} " in
      *" -l easydblab/kit=cqlite-flight,easydblab/role="[a-z]*) ;;
      *) fail "delete without a kit and role selector: ${call}" ;;
    esac
    case " ${call} " in *" --ignore-not-found "*) ;; *) fail "a re-run of stop would fail: ${call}" ;; esac
  done <<< "$(deletes)"
  for kind in daemonset service configmap; do
    deletes | grep -Eq "^delete ([a-z]+,)*${kind}(,| )[^ ]* ?-l easydblab/kit=cqlite-flight,easydblab/role=flight( |$)" ||
      fail "the role=flight ${kind} is not deleted: $(deletes)"
  done
}

test_stop_deletes_every_kind_by_kit_and_role

echo "cqlite-flight stop: ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
