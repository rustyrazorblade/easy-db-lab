#!/usr/bin/env bash
#
# Unit tests for the SingleStore kit's sizing, bin/lib/singlestore-sizing.sh.
#
# start sizes the cluster from the db nodes before it creates anything: --size becomes an integer
# storageGB, the redundancy is 2 for an even leaf count and 1 for an odd one unless --redundancy
# sets it, it must be 1 or 2, and the leaf count must be a multiple of it. There are no more leaves
# than db nodes, and no more aggregators (the master and the child aggregators) than db nodes. A leaf gets the smallest db node's
# allocatable CPU and memory, less a fixed collector reserve, its share of the aggregators and the
# exporter sidecar, and must have at least 4 cores and 8192 MB.
#
# kubectl is stubbed: `get nodes` prints the allocatable CPU and memory in STUB_NODES. Every case
# also runs under the shell a kit step uses on macOS, /bin/bash, when it exists.
#
# Run directly:  bash src/test/shell/singlestore-sizing.test.sh
# Or via gradle: ./gradlew testSingleStoreSizing

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/singlestore/bin/lib/singlestore-sizing.sh"

# i4i.2xlarge (8 vCPU, 64 GiB) and i4i.xlarge (4 vCPU, 32 GiB) db nodes, as K3s reports them.
NODE_8_VCPU="8 65544428Ki"
NODE_4_VCPU="4 32491628Ki"

tests_run=0
tests_failed=0
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

mkdir -p "${WORK}/stubs"
cat > "${WORK}/stubs/kubectl" <<'EOF'
#!/usr/bin/env bash
case " $* " in
  *" get nodes "*) printf '%s\n' "${STUB_NODES}" ;;
  *) echo "unexpected kubectl call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "${WORK}/stubs/kubectl"

# Prints node line $2, $1 times: that many identical db nodes.
nodes() {
  local i
  for i in $(seq 1 "$1"); do printf '%s\n' "$2"; done
}

SHELLS=(bash)
[ -x /bin/bash ] && SHELLS+=(/bin/bash)

# Runs `$@` after sourcing the library, under shell $SH, printing its output.
in_lib() {
  env -i PATH="${WORK}/stubs:/usr/bin:/bin" HOME="${WORK}" ${STUB_ENV[@]+"${STUB_ENV[@]}"} \
    "${SH}" -c 'set -euo pipefail; source "$1"; shift; "$@"' _ "${LIB}" "$@" 2>&1
}

# Runs singlestore_size with the args in STUB_ENV and prints the variables it sets.
size_cluster() {
  in_lib eval 'singlestore_size > /dev/null && echo "R=${REDUNDANCY} N=${LEAF_COUNT} C=${LEAF_CORES} M=${LEAF_MEMORY_MB} S=${STORAGE_GB} A=${AGGREGATOR_MEMORY_MB}"' ||
    true
}

DEFAULTS=(SINGLESTORE_AGGREGATORS=0 SINGLESTORE_AGGREGATOR_CORES=2 SINGLESTORE_AGGREGATOR_MEMORY=8 STORAGE_SIZE=10Ti)

for SH in "${SHELLS[@]}"; do
  STUB_ENV=()

  tests_run=$((tests_run + 1))
  for pair in "10Ti 10240" "500Gi 500" "1T 1000" "100G 100" "2048Mi 2"; do
    set -- ${pair}
    [ "$(in_lib ss_storage_gb "$1")" = "$2" ] || fail "[${SH}] --size $1 is not $2 GB: $(in_lib ss_storage_gb "$1")"
  done
  out="$(in_lib ss_storage_gb lots)" && fail "[${SH}] a size with no unit was accepted"
  [[ "${out}" == *"--size"* ]] || fail "[${SH}] a bad --size does not name the flag: ${out}"

  tests_run=$((tests_run + 1))
  [ "$(in_lib ss_cpu_millicores 8)" = 8000 ] || fail "[${SH}] cpu 8 is not 8000m"
  [ "$(in_lib ss_cpu_millicores 7910m)" = 7910 ] || fail "[${SH}] cpu 7910m is not 7910m"
  [ "$(in_lib ss_memory_mb 65544428Ki)" = 64008 ] || fail "[${SH}] 65544428Ki is not 64008 MB"
  [ "$(in_lib ss_memory_mb 63Gi)" = 64512 ] || fail "[${SH}] 63Gi is not 64512 MB"
  [ "$(in_lib ss_memory_mb 1048576000)" = 1000 ] || fail "[${SH}] 1048576000 bytes is not 1000 MB"

  tests_run=$((tests_run + 1))
  [ "$(in_lib ss_redundancy 4 "")" = 2 ] || fail "[${SH}] an even leaf count does not give redundancy 2"
  [ "$(in_lib ss_redundancy 3 "")" = 1 ] || fail "[${SH}] an odd leaf count does not give redundancy 1"
  [ "$(in_lib ss_redundancy 4 1)" = 1 ] || fail "[${SH}] an explicit redundancy that divides was not used"
  out="$(in_lib ss_redundancy 3 2)" && fail "[${SH}] 3 leaves with redundancy 2 were accepted"
  [[ "${out}" == *"--leaves"* && "${out}" == *"--redundancy"* ]] || fail "[${SH}] the message does not name both flags: ${out}"

  tests_run=$((tests_run + 1))
  # SingleStore supports redundancy 1 and 2 only, even when a larger level divides the leaves.
  for redundancy in 3 6 0 x; do
    out="$(in_lib ss_redundancy 6 "${redundancy}")" && fail "[${SH}] --redundancy ${redundancy} was accepted"
    [[ "${out}" == *"--redundancy ${redundancy}"* && "${out}" == *"1 or 2"* ]] ||
      fail "[${SH}] --redundancy ${redundancy} does not fail naming the flag and 1 or 2: ${out}"
  done

  tests_run=$((tests_run + 1))
  # 8 vCPU: 8000m - 1000m reserve - 2000m aggregator share - 100m exporter = 4900m, so 4 cores.
  # 64008 MB - 4096 reserve - 8192 aggregator share - 200 exporter = 51520 MB.
  [ "$(in_lib ss_leaf_size 8000 64008 3 0 2 8)" = "4 51520" ] || fail "[${SH}] computed leaf on 8 vCPU: $(in_lib ss_leaf_size 8000 64008 3 0 2 8)"
  # One leaf with two child aggregators carries all three: 16000m - 1000 - 3 * 2000 - 100 = 8900m.
  [ "$(in_lib ss_leaf_size 16000 128000 1 2 2 8)" = "8 99128" ] || fail "[${SH}] aggregator share: $(in_lib ss_leaf_size 16000 128000 1 2 2 8)"
  # Four leaves, three aggregators: ceil(4 / 4) = 1 aggregator per leaf.
  [ "$(in_lib ss_leaf_size 8000 64008 4 3 2 8)" = "4 51520" ] || fail "[${SH}] share of 4 aggregators over 4 leaves"

  tests_run=$((tests_run + 1))
  in_lib ss_check_leaf_minimum 4 8192 > /dev/null || fail "[${SH}] 4 cores and 8192 MB is the minimum, not below it"
  out="$(in_lib ss_check_leaf_minimum 3 51520)" && fail "[${SH}] 3 cores was accepted"
  [[ "${out}" == *"3 cores"* && "${out}" == *"4 cores"* && "${out}" == *"8192 MB"* ]] || fail "[${SH}] the message lacks the values and the minimum: ${out}"
  out="$(in_lib ss_check_leaf_minimum 4 8000)" && fail "[${SH}] 8000 MB was accepted"

  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(printf '%s\n%s' "16 131072000Ki" "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=2)
  out="$(size_cluster)"
  [ "${out}" = "R=2 N=1 C=4 M=51520 S=10240 A=8192" ] || fail "[${SH}] defaults on 2 leaves, smallest node 8 vCPU: ${out}"

  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=3)
  out="$(size_cluster)"
  [ "${out}" = "R=1 N=3 C=4 M=51520 S=10240 A=8192" ] || fail "[${SH}] defaults on 3 leaves: ${out}"

  # Each leaf repels every other leaf, so a leaf count above the db node count can never schedule.
  # The check holds with the leaf size given too, when nothing else needs the nodes.
  tests_run=$((tests_run + 1))
  for given in "" "SINGLESTORE_LEAF_CORES=4 SINGLESTORE_LEAF_MEMORY=16"; do
    # shellcheck disable=SC2206 # zero or two assignments, split on purpose
    STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=4 ${given})
    out="$(size_cluster)"
    [[ "${out}" == *"--leaves 4"* && "${out}" == *"3 db nodes"* && "${out}" != R=* ]] ||
      fail "[${SH}] 4 leaves on 3 db nodes (${given:-computed size}) did not fail naming --leaves and the node count: ${out}"
  done

  # The aggregators repel each other too, so each db node holds at most one of them, the share the
  # leaf size assumes. More aggregators than db nodes can never schedule.
  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=3 SINGLESTORE_AGGREGATORS=3
    SINGLESTORE_LEAF_CORES=4 SINGLESTORE_LEAF_MEMORY=16)
  out="$(size_cluster)"
  [[ "${out}" == *"--aggregators 3"* && "${out}" == *"3 db nodes"* && "${out}" != R=* ]] ||
    fail "[${SH}] a master and 3 child aggregators on 3 db nodes did not fail naming --aggregators and the node count: ${out}"
  STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=3 SINGLESTORE_AGGREGATORS=2
    SINGLESTORE_LEAF_CORES=4 SINGLESTORE_LEAF_MEMORY=16)
  out="$(size_cluster)"
  [ "${out}" = "R=1 N=3 C=4 M=16384 S=10240 A=8192" ] || fail "[${SH}] a master and 2 child aggregators on 3 db nodes: ${out}"

  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_4_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=3)
  out="$(size_cluster)"
  [[ "${out}" == *"below the minimum"* && "${out}" == *"0 cores"* ]] || fail "[${SH}] defaults on 4 vCPU nodes did not fail with the size: ${out}"

  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(nodes 4 "${NODE_4_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=4 SINGLESTORE_REDUNDANCY=1
    SINGLESTORE_LEAF_CORES=6 SINGLESTORE_LEAF_MEMORY=24)
  out="$(size_cluster)"
  [ "${out}" = "R=1 N=4 C=6 M=24576 S=10240 A=8192" ] || fail "[${SH}] given leaf size and redundancy: ${out}"

  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=3 SINGLESTORE_LEAF_CORES=2 SINGLESTORE_LEAF_MEMORY=16)
  out="$(size_cluster)"
  [[ "${out}" == *"below the minimum"* && "${out}" == *"2 cores"* ]] || fail "[${SH}] a given leaf below the minimum was accepted: ${out}"

  tests_run=$((tests_run + 1))
  STUB_ENV=("STUB_NODES=$(nodes 3 "${NODE_8_VCPU}")" "${DEFAULTS[@]}" SINGLESTORE_LEAVES=3 SINGLESTORE_REDUNDANCY=2)
  out="$(size_cluster)"
  [[ "${out}" == *"--leaves"* && "${out}" == *"--redundancy"* ]] || fail "[${SH}] a non-dividing redundancy did not fail naming both flags: ${out}"
done

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
