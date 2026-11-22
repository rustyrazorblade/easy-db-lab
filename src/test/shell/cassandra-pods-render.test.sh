#!/usr/bin/env bash
# The check conditions are single-quoted on purpose: check() evals them.
# shellcheck disable=SC2016
#
# Unit tests for the cassandra-pods kit's config/render-config.sh, run by each pod's
# cassandra-render init container under /bin/sh (dash on the runtime image). It is run here
# through a real POSIX sh, with the real yq and packer's edl-cassandra-config.sh and cassandra.in.sh,
# the files processResources packages into the ConfigMap.
#
# Run directly:  bash src/test/shell/cassandra-pods-render.test.sh
# Or via gradle: ./gradlew testCassandraPodsRender

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cassandra-pods"
SCRIPT="${KIT}/config/render-config.sh"
POSIX_SH="$(command -v dash || command -v sh)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

tests_run=0
tests_failed=0

check() {
  local desc="$1" cond="$2"
  tests_run=$((tests_run + 1))
  if eval "$cond"; then
    echo "PASS: ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "FAIL: ${desc}"
    echo "  status=${status}"
    echo "  output: ${output}"
  fi
}

# The ConfigMap as mounted in the pod: the scripts (the packer copies) and the three config inputs.
mkdir -p "${WORK}/config" "${WORK}/tree/conf" "${WORK}/tree/bin" "${WORK}/conf"
cp "${ROOT}/packer/cassandra/lib/edl-cassandra-config.sh" "${ROOT}/packer/cassandra/cassandra.in.sh" "${WORK}/config/"
cat > "${WORK}/config/cassandra.patch.yaml" << 'EOF'
cluster_name: "lab"
num_tokens: 4
concurrent_reads: 128
endpoint_snitch: "Ec2Snitch"
data_file_directories:
  - "/mnt/db1/cassandra/data"
seed_provider:
  class_name: "org.apache.cassandra.locator.SimpleSeedProvider"
  parameters:
    seeds: "10.0.0.1"
EOF
cat > "${WORK}/config/placement.patch.yaml" << 'EOF'
endpoint_snitch: "GossipingPropertyFileSnitch"
data_file_directories:
  - "/var/lib/cassandra/data"
native_transport_port: 30942
seed_provider:
  class_name: "org.apache.cassandra.locator.SimpleSeedProvider"
  parameters:
    seeds: "cassandra-pods-0.cassandra-pods-gossip.default.svc.cluster.local"
EOF
printf 'dc=east\nrack=rack1\n' > "${WORK}/config/cassandra-rackdc.properties"

# A cached tarball tree: its own cassandra.yaml, cassandra.in.sh, and the other conf files.
cat > "${WORK}/tree/conf/cassandra.yaml" << 'EOF'
cluster_name: 'Test Cluster'
num_tokens: 16
concurrent_reads: 32
concurrent_writes: 32
endpoint_snitch: SimpleSnitch
listen_address: localhost
rpc_address: localhost
native_transport_port: 9042
EOF
printf 'CASSANDRA_HOME_FROM_TARBALL=1\ncassandra_storagedir="$CASSANDRA_HOME/data"\n' > "${WORK}/tree/bin/cassandra.in.sh"
echo '<configuration/>' > "${WORK}/tree/conf/logback.xml"
echo '-Xss256k' > "${WORK}/tree/conf/jvm-server.options"
cp "${WORK}/tree/conf/cassandra.yaml" "${WORK}/tarball-cassandra.yaml"

run_render() {
  output="$(env EDL_CONFIG_DIR="${WORK}/config" EDL_CASSANDRA_HOME="${WORK}/tree" CASSANDRA_CONF="${WORK}/conf" \
    POD_IP=10.42.0.7 HOST_IP=192.168.1.5 "$@" "${POSIX_SH}" "${SCRIPT}" 2>&1)"
  status=$?
}

value() { yq -r "$1" "${WORK}/conf/cassandra.yaml"; }

run_render
check "the render succeeds" '[ "$status" -eq 0 ]'
check "the tarball's keys the patches leave alone are kept" '[ "$(value .concurrent_writes)" = 32 ]'
check "the common patch replaces the tarball's values" '[ "$(value .cluster_name)" = lab ] && [ "$(value .num_tokens)" = 4 ]'
check "the user's tuning reaches the pod" '[ "$(value .concurrent_reads)" = 128 ]'
check "placement wins over the VM keys in the workspace patch" \
  '[ "$(value .endpoint_snitch)" = GossipingPropertyFileSnitch ] && [ "$(value ".data_file_directories[0]")" = /var/lib/cassandra/data ] && [ "$(value ".seed_provider.parameters.seeds")" = cassandra-pods-0.cassandra-pods-gossip.default.svc.cluster.local ]'
check "the native port is the NodePort value" '[ "$(value .native_transport_port)" = 30942 ]'
check "the pod listens on its own IP" '[ "$(value .listen_address)" = 10.42.0.7 ] && [ "$(value .rpc_address)" = 10.42.0.7 ]'
check "clients are told the node IP" '[ "$(value .broadcast_rpc_address)" = 192.168.1.5 ]'
check "the cached tree's cassandra.yaml is never changed" 'cmp -s "${WORK}/tree/conf/cassandra.yaml" "${WORK}/tarball-cassandra.yaml"'
check "the rest of the tarball's conf is copied" '[ -f "${WORK}/conf/logback.xml" ] && [ -f "${WORK}/conf/jvm-server.options" ]'
check "the rackdc file names the DC and rack" '[ "$(cat "${WORK}/conf/cassandra-rackdc.properties")" = "$(printf "dc=east\nrack=rack1")" ]'
check "cassandra.in.sh is the tarball's own file, then the packer snippet" \
  '[ "$(head -n 1 "${WORK}/conf/cassandra.in.sh")" = CASSANDRA_HOME_FROM_TARBALL=1 ] && grep -q "Begin easy-db-lab customizations" "${WORK}/conf/cassandra.in.sh"'

# bin/cassandra passes -Dcassandra.storagedir=$cassandra_storagedir, the parent of every directory
# cassandra.yaml leaves unset (accord_journal on trunk, cdc_raw). The tarball's cassandra.in.sh sets it
# to $CASSANDRA_HOME/data, the per-sha256 cached tree, which an upgrade replaces. Sourced as
# bin/cassandra sources it, with a stub java so the packer snippet's JDK probe runs anywhere.
mkdir -p "${WORK}/java-stub"
printf '#!/bin/sh\necho "openjdk version \\"17.0.2\\" 2022-01-18" >&2\n' > "${WORK}/java-stub/java"
chmod +x "${WORK}/java-stub/java"
storagedir="$(env -i PATH="${WORK}/java-stub:/usr/bin:/bin" CASSANDRA_HOME="${WORK}/tree" EDL_CASSANDRA_BUILD=5.0.6 \
  EDL_CASSANDRA_LOG_DIR="${WORK}/logs" ECL_AGENTS_LIB=/nonexistent "${POSIX_SH}" -c \
  '. "$1" > /dev/null 2>&1; printf "%s" "${cassandra_storagedir:-}"' sh "${WORK}/conf/cassandra.in.sh")"
check "the storage dir every unset directory defaults under is the claim, never the cached tree" \
  "[ '${storagedir}' = /var/lib/cassandra ]"

# A 3.x tarball's conf: cassandra-env.sh and one jvm.options, no jvm-server.options. bin/cassandra
# sources $CASSANDRA_CONF/cassandra-env.sh, which reads $CASSANDRA_CONF/jvm.options.
rm -rf "${WORK}/conf"/* "${WORK}/tree/conf/jvm-server.options"
echo 'JVM_OPTS_FILE=$CASSANDRA_CONF/jvm.options' > "${WORK}/tree/conf/cassandra-env.sh"
echo '-ea' > "${WORK}/tree/conf/jvm.options"
run_render
check "a 3.x tree renders with its cassandra-env.sh and jvm.options in the rendered conf" \
  '[ "$status" -eq 0 ] && cmp -s "${WORK}/conf/cassandra-env.sh" "${WORK}/tree/conf/cassandra-env.sh" && cmp -s "${WORK}/conf/jvm.options" "${WORK}/tree/conf/jvm.options"'

rm -rf "${WORK}/conf"/*
run_render POD_IP=""
check "no pod IP fails, naming it" '[ "$status" -ne 0 ] && [[ "$output" == *POD_IP* ]]'
check "a failed render writes no cassandra.yaml" '[ ! -f "${WORK}/conf/cassandra.yaml" ]'

mv "${WORK}/tree/conf/cassandra.yaml" "${WORK}/tree/conf/gone.yaml"
run_render
check "a tree with no cassandra.yaml fails, naming the file" '[ "$status" -ne 0 ] && [[ "$output" == *"conf/cassandra.yaml"* ]]'
mv "${WORK}/tree/conf/gone.yaml" "${WORK}/tree/conf/cassandra.yaml"

echo 'cluster_name: [unclosed' > "${WORK}/config/cassandra.patch.yaml"
rm -rf "${WORK}/conf"/*
run_render
check "a workspace patch that is not YAML fails, naming it" '[ "$status" -ne 0 ] && [[ "$output" == *"cassandra.patch.yaml"* ]] && [ ! -f "${WORK}/conf/cassandra.yaml" ]'

echo
echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
