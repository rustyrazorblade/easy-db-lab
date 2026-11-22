#!/usr/bin/env bash
# Creates or updates the cassandra-pods-config ConfigMap the tarball path's pods mount at
# /edl/config: the init containers' scripts, the packer scripts they run, and the config inputs the
# cassandra-artifact contributor wrote into the kit directory. With --image there is nothing to do.
#
# Applied, not created, so `upgrade` replaces it in place; the pods read it again as each restarts,
# and the contributor's config hash rolls them only when its content changed.
set -euo pipefail

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  exit 0
fi

from=()
for file in config/fetch-tarball.sh config/render-config.sh config/edl-cassandra-config.sh \
  config/edl-cassandra-agents.sh config/cassandra.in.sh \
  cassandra.patch.yaml placement.patch.yaml cassandra-rackdc.properties; do
  [ -s "${file}" ] || {
    echo "ERROR: ${file} is missing from the kit directory; run start through easy-db-lab, which writes it." >&2
    exit 1
  }
  from+=("--from-file=${file##*/}=${file}")
done

kubectl create configmap cassandra-pods-config --namespace default "${from[@]}" --dry-run=client -o yaml |
  kubectl label --local -f - "easydblab/kit=${KIT_NAME}" easydblab/role=config -o yaml |
  kubectl apply -f -
