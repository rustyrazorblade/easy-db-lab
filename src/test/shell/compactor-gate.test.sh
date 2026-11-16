#!/usr/bin/env bash
#
# Unit tests for the account compactor's gate, configuration/compactor/compactor-gate.sh, under
# every local POSIX shell found (sh, dash, bash, and busybox sh when installed). The cases are in
# compactor-gate.cases.sh; compactor-gate-busybox.test.sh runs the same cases under the static
# busybox sh the pod runs the gate with.
#
# Run directly:  bash src/test/shell/compactor-gate.test.sh
# Or via gradle: ./gradlew testCompactorGate

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GATE="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/compactor/compactor-gate.sh"
CASES="${ROOT}/src/test/shell/compactor-gate.cases.sh"

shells=()
for candidate in sh dash bash; do
  command -v "${candidate}" > /dev/null && shells+=("$(command -v "${candidate}")")
done
command -v busybox > /dev/null && shells+=("$(command -v busybox) sh")

failed=0
for shell_under_test in "${shells[@]}"; do
  GATE_SCRIPT="${GATE}" GATE_SHELL="${shell_under_test}" sh "${CASES}" || failed=$((failed + 1))
done

echo "compactor-gate: ${#shells[@]} shells, ${failed} with failures"
[ "${failed}" = 0 ]
