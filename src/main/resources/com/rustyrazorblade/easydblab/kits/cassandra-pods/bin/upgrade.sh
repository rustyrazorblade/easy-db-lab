#!/usr/bin/env bash
# cassandra-pods upgrade --version <v> | --build <b> [--java <n>]: rolls the running pods onto
# another tarball, one pod at a time, then waits until every node is UN again.
#
# The cassandra-artifact contributor has already resolved the new artifact (a failed resolution
# ends the command before this runs, changing nothing). This script:
#   1. refuses anything but exactly one of --version and --build, a kit started with --image, and a
#      kit that is not running, before changing anything;
#   2. refuses a major-version downgrade (the running build's major against the new one's), naming
#      both, before changing anything. A build whose name does not start with a number (trunk) has
#      no major to compare, so it is never refused;
#   3. applies the ConfigMap and the StatefulSet; the new artifact changes the pod template, so the
#      StatefulSet restarts the pods one at a time (RollingUpdate, OrderedReady, preStop drain);
#   4. waits for the rollout, then for every node to be UN.
# On success the kit runner records --version, --build and --java (kit.yaml `records`), so a later
# start runs what was applied.
#
# A command script runs from the workspace; the lib scripts and the resolved files are in the kit
# directory, this script's parent, so it changes there first.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

version="${CASSANDRA_VERSION:-}"
build="${CASSANDRA_BUILD:-}"
if [ -n "${version}" ] && [ -n "${build}" ]; then
  fail "ERROR: upgrade takes --version or --build, not both (--version ${version}, --build ${build})."
fi
[ -n "${version}${build}" ] || fail "ERROR: upgrade needs --version or --build, such as 'upgrade --version 5.0.6'."
[ -z "${CASSANDRA_IMAGE:-}" ] ||
  fail "ERROR: this kit runs the image ${CASSANDRA_IMAGE}; upgrade changes the tarball a kit runs. Stop the kit and start it with --version or --build."

target="${EDL_CASSANDRA_BUILD:?run upgrade through easy-db-lab, whose cassandra-artifact contributor sets EDL_CASSANDRA_BUILD}"

running="$(kubectl get statefulset "${KIT_NAME}" --namespace default \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="cassandra")].env[?(@.name=="EDL_CASSANDRA_BUILD")].value}')" ||
  fail "ERROR: the ${KIT_NAME} StatefulSet was not found; the kit is not running. Run '${KIT_NAME} start' first."
[ -n "${running}" ] ||
  fail "ERROR: the running ${KIT_NAME} StatefulSet names no EDL_CASSANDRA_BUILD, so it was not started from a tarball. Stop the kit and start it with --version or --build."

# The leading number of a build name: 5 for 5.0.6, 5.0-HEAD or 5.0.6-rrb; empty for trunk.
major() {
  case "$1" in
    [0-9]*) printf '%s' "${1%%[!0-9]*}" ;;
    *) printf '' ;;
  esac
}
from="$(major "${running}")"
to="$(major "${target}")"
if [ -n "${from}" ] && [ -n "${to}" ] && [ "${to}" -lt "${from}" ]; then
  fail "ERROR: upgrade from ${running} to ${target} is a major-version downgrade, which Cassandra does not support; the pods were not changed."
fi

echo "Upgrading ${KIT_NAME} from ${running} to ${target}, one pod at a time."
bash lib/apply-config.sh
bash lib/apply-statefulset.sh

# The same bound as wait-un.sh: each pod restarts in turn and must pass its readiness probe.
timeout="${CASSANDRA_UN_WAIT_SECONDS:-$((300 + CASSANDRA_NODES * 720))}"
kubectl rollout status "statefulset/${KIT_NAME}" --namespace default --timeout="${timeout}s" ||
  fail "ERROR: the ${KIT_NAME} rollout to ${target} did not finish within ${timeout}s; 'kubectl get pods -l easydblab/kit=${KIT_NAME}' shows which pod is not Ready."
bash lib/wait-un.sh
echo "${KIT_NAME} runs ${target}."
