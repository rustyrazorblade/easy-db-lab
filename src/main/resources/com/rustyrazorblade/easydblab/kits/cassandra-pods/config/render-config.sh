#!/bin/sh
# render-config.sh - the cassandra-render init container: builds this pod's Cassandra config into
# the emptyDir $CASSANDRA_CONF, which the cassandra container uses, from the cached tarball tree
# $EDL_CASSANDRA_HOME (never changed) and the kit's ConfigMap $EDL_CONFIG_DIR.
#
# cassandra.yaml is built with edl_merge_cassandra_yaml, the merge the VM path's patch-config uses,
# from these layers in order (a later one wins a key):
#   1. the tarball's conf/cassandra.yaml;
#   2. cassandra.patch.yaml, the workspace patch with the common keys and the user's edits;
#   3. placement.patch.yaml: seeds, directories, the snitch and the native port;
#   4. this pod's addresses: listen and rpc on POD_IP, broadcast_rpc on HOST_IP (clients dial the node).
# cassandra.in.sh is the tarball's own file followed by the packer snippet; the cassandra container
# points CASSANDRA_INCLUDE at it. cassandra-rackdc.properties is copied from the ConfigMap.
#
# POSIX sh: the runtime image runs it under /bin/sh. Tested by
# src/test/shell/cassandra-pods-render.test.sh (./gradlew testCassandraPodsRender).
set -eu

config="${EDL_CONFIG_DIR:-/edl/config}"
home="${EDL_CASSANDRA_HOME:?EDL_CASSANDRA_HOME must name the cached Cassandra tree}"
conf="${CASSANDRA_CONF:?CASSANDRA_CONF must name the directory to render into}"
pod_ip="${POD_IP:-}"
host_ip="${HOST_IP:-}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[ -n "${pod_ip}" ] || fail "POD_IP is not set; the pod spec passes it from status.podIP."
[ -n "${host_ip}" ] || fail "HOST_IP is not set; the pod spec passes it from status.hostIP."
[ -f "${home}/conf/cassandra.yaml" ] || fail "${home}/conf/cassandra.yaml is missing; the cassandra-fetch step did not cache a complete tree."
for input in cassandra.patch.yaml placement.patch.yaml cassandra-rackdc.properties edl-cassandra-config.sh cassandra.in.sh; do
  [ -f "${config}/${input}" ] || fail "${config}/${input} is missing from the cassandra-pods-config ConfigMap."
done

# shellcheck disable=SC1091
. "${config}/edl-cassandra-config.sh"

mkdir -p "${conf}"
# Every conf file of the tarball (logback.xml, jvm*.options, ...), then the files rendered over them.
cp -R "${home}/conf/." "${conf}/"
rm -f "${conf}/cassandra.yaml"

addresses="${conf}/.pod-addresses.yaml"
printf 'listen_address: "%s"\nrpc_address: "%s"\nbroadcast_rpc_address: "%s"\n' \
  "${pod_ip}" "${pod_ip}" "${host_ip}" > "${addresses}"
edl_merge_cassandra_yaml "${home}/conf/cassandra.yaml" "${conf}/cassandra.yaml" \
  "${config}/cassandra.patch.yaml" "${config}/placement.patch.yaml" "${addresses}" ||
  fail "could not build cassandra.yaml from ${config}/cassandra.patch.yaml and ${config}/placement.patch.yaml."
rm -f "${addresses}"

cp "${config}/cassandra-rackdc.properties" "${conf}/cassandra-rackdc.properties"
{
  [ ! -f "${home}/bin/cassandra.in.sh" ] || cat "${home}/bin/cassandra.in.sh"
  cat "${config}/cassandra.in.sh"
} > "${conf}/cassandra.in.sh"
echo "Rendered the Cassandra config into ${conf}."
