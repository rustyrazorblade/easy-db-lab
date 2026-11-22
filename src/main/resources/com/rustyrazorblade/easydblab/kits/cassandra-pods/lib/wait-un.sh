#!/usr/bin/env bash
# Waits until `nodetool status` on pod 0 lists CASSANDRA_NODES nodes as UN. On timeout it prints
# the pods' recent events and exits 1.
#
# The bound is BASE + NODES x PER_NODE. StatefulSet pods start one at a time (OrderedReady), and
# each must pass its readiness probe, which allows initialDelaySeconds 30 + 60 failures x 10s =
# 630s, plus image pulls. PER_NODE covers that window with margin; BASE covers the first
# scheduling and the final nodetool poll. Keep PER_NODE >= the probe window in the template.
#
# CASSANDRA_UN_WAIT_SECONDS overrides the bound, for tests only.
set -euo pipefail

BASE_SECONDS=300
PER_NODE_SECONDS=720
timeout="${CASSANDRA_UN_WAIT_SECONDS:-$((BASE_SECONDS + CASSANDRA_NODES * PER_NODE_SECONDS))}"
echo "Waiting for ${CASSANDRA_NODES} nodes to be UN, up to ${timeout}s."
poll="${EDL_POLL_SECONDS:-5}"
pod="${KIT_NAME}-0"
deadline=$(($(date +%s) + timeout))

# Run in the container: a tarball pod has nodetool under its EDL_CASSANDRA_HOME, off PATH; an
# --image pod sets no EDL_CASSANDRA_HOME and has nodetool on PATH.
NODETOOL_STATUS='if [ -n "${EDL_CASSANDRA_HOME:-}" ]; then exec "$EDL_CASSANDRA_HOME/bin/nodetool" status; fi; exec nodetool status'

nt_err="$(mktemp)"
trap 'rm -f "${nt_err}"' EXIT

# A node is UN in gossip before its native transport listens, so UN alone does not mean the node
# serves CQL. The readiness probe (statusbinary) does, so the wait also needs every pod Ready.
ready=0
while :; do
  up="$(kubectl exec "${pod}" --namespace default -c cassandra -- sh -c "${NODETOOL_STATUS}" 2> "${nt_err}" | grep -c '^UN' || true)"
  ready="$(kubectl get statefulset "${KIT_NAME}" --namespace default -o jsonpath='{.status.readyReplicas}' 2> /dev/null || true)"
  ready="${ready:-0}"
  if [ "${up}" -ge "${CASSANDRA_NODES}" ] && [ "${ready}" -ge "${CASSANDRA_NODES}" ]; then
    echo "${up} nodes are UN and ${ready} pods are Ready."
    exit 0
  fi
  [ "$(date +%s)" -lt "${deadline}" ] || break
  sleep "${poll}"
done

echo "ERROR: ${up} of ${CASSANDRA_NODES} nodes are UN and ${ready} of ${CASSANDRA_NODES} pods are Ready after ${timeout}s. Recent events:" >&2
if [ -s "${nt_err}" ]; then
  echo "Last nodetool status error:" >&2
  cat "${nt_err}" >&2
fi
kubectl get events --namespace default --sort-by=.lastTimestamp 2>&1 | tail -n 20 >&2 || true
exit 1
