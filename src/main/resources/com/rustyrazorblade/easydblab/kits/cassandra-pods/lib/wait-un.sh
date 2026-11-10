#!/usr/bin/env bash
# Waits until `nodetool status` on pod 0 lists CASSANDRA_NODES nodes as UN. On timeout it prints
# the pods' recent events and exits 1.
#
# CASSANDRA_UN_WAIT_SECONDS overrides the bound, for tests only.
set -euo pipefail

timeout="${CASSANDRA_UN_WAIT_SECONDS:-900}"
poll="${EDL_POLL_SECONDS:-5}"
pod="${KIT_NAME}-0"
deadline=$(($(date +%s) + timeout))

nt_err="$(mktemp)"
trap 'rm -f "${nt_err}"' EXIT

while :; do
  up="$(kubectl exec "${pod}" --namespace default -c cassandra -- nodetool status 2> "${nt_err}" | grep -c '^UN' || true)"
  if [ "${up}" -ge "${CASSANDRA_NODES}" ]; then
    echo "${up} nodes are UN."
    exit 0
  fi
  [ "$(date +%s)" -lt "${deadline}" ] || break
  sleep "${poll}"
done

echo "ERROR: ${up} of ${CASSANDRA_NODES} nodes are UN after ${timeout}s. Recent events:" >&2
if [ -s "${nt_err}" ]; then
  echo "Last nodetool status error:" >&2
  cat "${nt_err}" >&2
fi
kubectl get events --namespace default --sort-by=.lastTimestamp 2>&1 | tail -n 20 >&2 || true
exit 1
