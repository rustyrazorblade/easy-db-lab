#!/usr/bin/env bash
#
# Runs the compactor gate's cases, compactor-gate.cases.sh, under the busybox sh the pod runs the
# gate with: the static busybox:1.37.0-musl image that the account compactor's init container
# copies into the pod. The driver runs under that busybox sh too, and the real-clock case uses the
# real busybox applets.
#
# Needs Docker. Run directly:  bash src/test/shell/compactor-gate-busybox.test.sh
# Or via gradle:               ./gradlew testCompactorGateBusybox

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
IMAGE="busybox:1.37.0-musl"

docker run --rm \
  -e GATE_SCRIPT=/gate/compactor-gate.sh \
  -e "GATE_SHELL=/bin/busybox sh" \
  -e REAL_BUSYBOX=/bin/busybox \
  -v "${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/compactor/compactor-gate.sh:/gate/compactor-gate.sh:ro" \
  -v "${ROOT}/src/test/shell/compactor-gate.cases.sh:/gate/cases.sh:ro" \
  "${IMAGE}" /bin/busybox sh /gate/cases.sh
