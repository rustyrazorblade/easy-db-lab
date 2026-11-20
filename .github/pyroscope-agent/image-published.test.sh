#!/usr/bin/env bash
# The check conditions are single-quoted on purpose: check() evals them after run_case sets
# status, written and args.
# shellcheck disable=SC2016
#
# Unit tests for image-published.sh, the check the publish-pyroscope-agent job runs before it
# pushes: a published tag is never overwritten, only a definite "not found" leads to a push, and
# any other inspect failure (auth, network, rate limit) fails the job.
#
# `docker` is a stub on PATH whose stderr and exit status each case sets; no Docker, no network.
#
# Run directly:  ./image-published.test.sh
# Or via gradle: ./gradlew testPyroscopeAgentPublished

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/image-published.sh"
IMAGE="ghcr.io/rustyrazorblade/pyroscope-java-agent:2.3.0"

tests_run=0
tests_failed=0

# run_case <stub-stderr> <stub-exit> -> sets status, output, written (the GITHUB_OUTPUT content)
run_case() {
  local stub_stderr="$1" stub_exit="$2" dir
  dir="$(mktemp -d)"
  mkdir "${dir}/bin"
  cat > "${dir}/bin/docker" <<STUB
#!/usr/bin/env bash
echo "\$*" > "${dir}/args"
[ -n "${stub_stderr}" ] && echo "${stub_stderr}" >&2
exit ${stub_exit}
STUB
  chmod +x "${dir}/bin/docker"
  : > "${dir}/out"
  output="$(PATH="${dir}/bin:${PATH}" IMAGE="${IMAGE}" GITHUB_OUTPUT="${dir}/out" bash "${SCRIPT}" 2>&1)"
  status=$?
  written="$(cat "${dir}/out")"
  args="$(cat "${dir}/args" 2>/dev/null)"
  rm -rf "${dir}"
}

check() {
  local desc="$1" cond="$2"
  tests_run=$((tests_run + 1))
  if eval "$cond"; then
    echo "PASS: ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "FAIL: ${desc}"
    echo "  status=${status} written='${written}' args='${args}'"
    echo "  output: ${output}"
  fi
}

run_case "" 0
check "a published tag is skipped" '[ "$status" -eq 0 ] && [ "$written" = "exists=true" ]'
check "the check inspects the image it was given" '[ "$args" = "buildx imagetools inspect ${IMAGE}" ]'

run_case "ERROR: ${IMAGE}: not found" 1
check "a tag the registry does not have is pushed" '[ "$status" -eq 0 ] && [ "$written" = "exists=false" ]'

run_case "ERROR: failed to authorize: denied: denied" 1
check "an auth failure fails the job and decides nothing" '[ "$status" -ne 0 ] && [ -z "$written" ]'
check "an auth failure shows the inspect error" '[[ "$output" == *"denied: denied"* ]]'

run_case "ERROR: dial tcp: lookup ghcr.io: no such host" 1
check "a network failure fails the job and decides nothing" '[ "$status" -ne 0 ] && [ -z "$written" ]'

run_case "ERROR: unexpected status from HEAD request: 429 Too Many Requests" 1
check "a rate limit fails the job and decides nothing" '[ "$status" -ne 0 ] && [ -z "$written" ]'

output="$(IMAGE="" GITHUB_OUTPUT=/dev/null bash "${SCRIPT}" 2>&1)"
status=$?
written=""
args=""
check "no image named fails the job" '[ "$status" -ne 0 ]'

echo
echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
