#!/usr/bin/env bash
#
# Unit tests for the sysbench kit's bin/stop.sh.template.
#
# stop drops the benchmark's tables through a cleanup pod. When the target database is already
# gone, that pod fails; stop must still finish, remove its pods and say the tables were left, so the
# kit is not left marked running with a cleanup pod in Error.
#
# kubectl is stubbed: it records every call, and reports the cleanup pod in the phase STUB_PHASE.
# `kubectl wait` fails unless the pod succeeded, as a timeout would. No cluster, no network.
#
# Run directly:  bash src/test/shell/sysbench-stop.test.sh
# Or via gradle: ./gradlew testSysbenchStopScript

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEMPLATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench/bin/stop.sh.template"

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
  mkdir -p "${WORK}/stubs" "${WORK}/kit/bin"
  cp "${TEMPLATE}" "${WORK}/kit/bin/stop.sh"
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "${WORK}/kubectl.calls"
case " \$* " in
  *" get pod "*) printf '%s' "\${STUB_PHASE}" ;;
  *" logs "*) echo "FATAL: stub cleanup log" ;;
  *" describe pod "*) echo "Events: stub ErrImagePull" ;;
  *" wait "*) [ "\${STUB_PHASE}" = Succeeded ] || { echo "error: timed out waiting for the condition" >&2; exit 1; } ;;
esac
exit 0
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/sleep"
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/sleep"
}

# Runs stop.sh against a Postgres target, with the cleanup pod ending in phase $1.
run_script() {
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" STUB_PHASE="$1" KIT_NAME=sb1 KUBECONFIG="${WORK}/kubeconfig" \
        TARGET_PG_HOST=10.0.0.9 TARGET_PG_PORT=5432 TARGET_PG_USER=u TARGET_PG_DATABASE=d \
        bash "${WORK}/kit/bin/stop.sh"
  ) > "${WORK}/out.txt" 2>&1
}

deletes_cleanup_pods() {
  grep -q 'delete pods -l easydblab/kit=sb1,easydblab/role=sysbench-cleanup' "${WORK}/kubectl.calls"
}

test_a_gone_target_still_stops_and_removes_the_cleanup_pod() {
  tests_run=$((tests_run + 1))
  setup
  run_script Failed || { fail "stop exited non-zero with the target gone: $(cat "${WORK}/out.txt")"; return; }
  deletes_cleanup_pods || fail "the failed cleanup pod was left: $(cat "${WORK}/kubectl.calls")"
  grep -q 'cleanup ended Failed; the sbtest tables were left' "${WORK}/out.txt" || fail "stop did not say the tables were left: $(cat "${WORK}/out.txt")"
  grep -q 'FATAL: stub cleanup log' "${WORK}/out.txt" || fail "stop did not print the failed cleanup pod's log: $(cat "${WORK}/out.txt")"
  [ "$(grep -n 'logs ' "${WORK}/kubectl.calls" | head -1 | cut -d: -f1)" -lt "$(grep -n 'role=sysbench-cleanup' "${WORK}/kubectl.calls" | tail -1 | cut -d: -f1)" ] ||
    fail "the log was not read before the pod was deleted: $(cat "${WORK}/kubectl.calls")"
}

test_a_cleanup_pod_that_never_starts_is_described_and_removed() {
  tests_run=$((tests_run + 1))
  setup
  run_script Pending || { fail "stop exited non-zero with the cleanup pod pending: $(cat "${WORK}/out.txt")"; return; }
  deletes_cleanup_pods || fail "the pending cleanup pod was left: $(cat "${WORK}/kubectl.calls")"
  grep -q 'Events: stub ErrImagePull' "${WORK}/out.txt" || fail "stop did not describe the pending pod: $(cat "${WORK}/out.txt")"
  grep -q 'cleanup ended Pending; the sbtest tables were left' "${WORK}/out.txt" || fail "no neutral message: $(cat "${WORK}/out.txt")"
}

test_a_live_target_drops_the_tables_and_removes_the_cleanup_pod() {
  tests_run=$((tests_run + 1))
  setup
  run_script Succeeded || { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  deletes_cleanup_pods || fail "the cleanup pod was left: $(cat "${WORK}/kubectl.calls")"
  grep -q 'data cleaned up' "${WORK}/out.txt" || fail "stop did not report the cleanup: $(cat "${WORK}/out.txt")"
}

test_a_gone_target_still_stops_and_removes_the_cleanup_pod
test_a_live_target_drops_the_tables_and_removes_the_cleanup_pod
test_a_cleanup_pod_that_never_starts_is_described_and_removed

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
