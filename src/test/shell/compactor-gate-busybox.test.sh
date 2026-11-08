#!/usr/bin/env bash
#
# Runs the compactor gate's tests under the busybox sh the pod runs it with: the static
# busybox:1.37.0-musl image that the account compactor's init container copies into the pod.
# compactor-gate.test.sh needs bash, which that image lacks, so this runs the gate's cases
# through a bash-free driver, compactor-gate-busybox.cases.sh, inside the image.
#
# Needs Docker. Run directly:  bash src/test/shell/compactor-gate-busybox.test.sh
# Or via gradle:               ./gradlew testCompactorGateBusybox

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
IMAGE="busybox:1.37.0-musl"

docker run --rm \
  -v "${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/compactor/compactor-gate.sh:/gate/compactor-gate.sh:ro" \
  -v "${ROOT}/src/test/shell/compactor-gate-busybox.cases.sh:/gate/cases.sh:ro" \
  "${IMAGE}" /bin/busybox sh /gate/cases.sh
