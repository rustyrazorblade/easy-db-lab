#!/usr/bin/env bash
# The check conditions are single-quoted on purpose: check() evals them after run_case sets
# status and output.
# shellcheck disable=SC2016
#
# Unit tests for platforms-present.sh, which fails the publish-cassandra-runtime job when a pushed
# tag's manifest list lacks linux/amd64 or linux/arm64. `docker` is a stub on PATH that prints the
# raw manifest list each case gives; no Docker, no network.
#
# Run directly:  ./platforms-present.test.sh
# Or via gradle: ./gradlew testCassandraRuntimePlatforms

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/platforms-present.sh"
IMAGE="ghcr.io/rustyrazorblade/cassandra-runtime:1-jre17"

tests_run=0
tests_failed=0

# manifest <os/arch>... -> a manifest list holding those platforms and a buildx attestation
manifest() {
  local entries="" p
  for p in "$@"; do
    entries="${entries}{\"platform\":{\"os\":\"${p%/*}\",\"architecture\":\"${p#*/}\"}},"
  done
  echo "{\"manifests\":[${entries}{\"platform\":{\"os\":\"unknown\",\"architecture\":\"unknown\"}}]}"
}

# run_case <stub-stdout> <stub-exit> -> sets status, output, args
run_case() {
  local stub_stdout="$1" stub_exit="$2" dir
  dir="$(mktemp -d)"
  mkdir "${dir}/bin"
  printf '%s\n' "${stub_stdout}" > "${dir}/stdout"
  cat > "${dir}/bin/docker" <<STUB
#!/usr/bin/env bash
echo "\$*" > "${dir}/args"
cat "${dir}/stdout"
exit ${stub_exit}
STUB
  chmod +x "${dir}/bin/docker"
  output="$(PATH="${dir}/bin:${PATH}" IMAGE="${IMAGE}" bash "${SCRIPT}" 2>&1)"
  status=$?
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
    echo "  status=${status} args='${args}'"
    echo "  output: ${output}"
  fi
}

run_case "$(manifest linux/amd64 linux/arm64)" 0
check "a tag with both platforms passes" '[ "$status" -eq 0 ]'
check "the check reads the raw manifest list of the image it was given" '[ "$args" = "buildx imagetools inspect --raw ${IMAGE}" ]'

run_case "$(manifest linux/amd64)" 0
check "a tag without arm64 fails" '[ "$status" -ne 0 ]'
check "the failure names the missing platform and the image" '[[ "$output" == *"linux/arm64"* && "$output" == *"${IMAGE}"* ]]'

run_case "$(manifest linux/arm64)" 0
check "a tag without amd64 fails, naming it" '[ "$status" -ne 0 ] && [[ "$output" == *"linux/amd64"* ]]'

run_case '{"schemaVersion":2,"config":{}}' 0
check "a single-platform image, not a list, fails" '[ "$status" -ne 0 ]'

run_case "ERROR: ${IMAGE}: not found" 1
check "an image that cannot be inspected fails" '[ "$status" -ne 0 ]'

output="$(IMAGE="" bash "${SCRIPT}" 2>&1)"
status=$?
args=""
check "no image named fails" '[ "$status" -ne 0 ]'

echo
echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
