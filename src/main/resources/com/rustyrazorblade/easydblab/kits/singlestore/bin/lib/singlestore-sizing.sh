#!/usr/bin/env bash
#
# Sizing for the SingleStore kit's start step. Every function is pure apart from singlestore_size,
# which reads the db nodes' allocatable resources with kubectl, so src/test/shell/
# singlestore-sizing.test.sh can test them with kubectl stubbed.
#
# A kit shell step runs under /bin/bash, which is bash 3.2 on macOS: no associative arrays, no
# namerefs, no ${var,,}.

# Held back on every db node for the collectors (OTel, Fluent Bit, Alloy, Beyla, ebpf_exporter),
# which run with no requests.
SS_COLLECTOR_RESERVE_MILLICORES=1000
SS_COLLECTOR_RESERVE_MB=4096
# The requests of the operator's exporter sidecar in the master pod.
SS_EXPORTER_MILLICORES=100
SS_EXPORTER_MB=200
# The smallest leaf the kit starts.
SS_MIN_LEAF_CORES=4
SS_MIN_LEAF_MB=8192

ss_fail() {
  echo "Error: $*" >&2
  return 1
}

# Prints the whole GB in a Kubernetes quantity such as 10Ti, 500Gi or 1T (binary units count 1Gi
# as 1 GB), for the CR's integer storageGB. At least 1.
ss_storage_gb() {
  local size="$1" number unit gb
  number="${size%%[!0-9]*}"
  unit="${size#"${number}"}"
  [ -n "${number}" ] || ss_fail "--size ${size} is not a size such as 10Ti or 500Gi" || return 1
  case "${unit}" in
    Pi) gb=$((number * 1024 * 1024)) ;;
    Ti) gb=$((number * 1024)) ;;
    Gi | G) gb=${number} ;;
    Mi) gb=$((number / 1024)) ;;
    P) gb=$((number * 1000 * 1000)) ;;
    T) gb=$((number * 1000)) ;;
    M) gb=$((number / 1000)) ;;
    *) ss_fail "--size ${size} is not a size such as 10Ti or 500Gi" || return 1 ;;
  esac
  [ "${gb}" -ge 1 ] || gb=1
  echo "${gb}"
}

# Prints a Kubernetes CPU quantity (8, 7910m, 7.5) in millicores.
ss_cpu_millicores() {
  local cpu="$1" whole fraction
  case "${cpu}" in
    *m) echo "${cpu%m}" ;;
    *.*)
      whole="${cpu%%.*}"
      fraction="${cpu#*.}000"
      echo $((${whole:-0} * 1000 + 10#${fraction:0:3}))
      ;;
    *) echo $((cpu * 1000)) ;;
  esac
}

# Prints a Kubernetes memory quantity (65544428Ki, 63Gi, plain bytes) in whole MB (MiB).
ss_memory_mb() {
  local memory="$1" number unit
  number="${memory%%[!0-9]*}"
  unit="${memory#"${number}"}"
  case "${unit}" in
    Ki) echo $((number / 1024)) ;;
    Mi) echo "${number}" ;;
    Gi) echo $((number * 1024)) ;;
    Ti) echo $((number * 1024 * 1024)) ;;
    K | k) echo $((number * 1000 / 1024 / 1024)) ;;
    M) echo $((number * 1000 * 1000 / 1024 / 1024)) ;;
    G) echo $((number * 1000 * 1000 * 1000 / 1024 / 1024)) ;;
    "") echo $((number / 1024 / 1024)) ;;
    *) ss_fail "unknown memory quantity ${memory}" ;;
  esac
}

# Prints the redundancy for LEAVES leaves: REDUNDANCY when set, else 2 for an even leaf count and
# 1 for an odd one. Fails when the leaf count is not a multiple of it, because leafSpec.count is
# per availability group.
ss_redundancy() {
  local leaves="$1" redundancy="${2:-}"
  if [ -z "${redundancy}" ]; then
    if [ $((leaves % 2)) -eq 0 ]; then redundancy=2; else redundancy=1; fi
  fi
  if [ "${leaves}" -lt 1 ] || [ "${redundancy}" -lt 1 ] || [ $((leaves % redundancy)) -ne 0 ]; then
    ss_fail "--leaves ${leaves} is not a multiple of --redundancy ${redundancy}. Each availability group holds the same number of leaves, so pick a --leaves that --redundancy divides." ||
      return 1
  fi
  echo "${redundancy}"
}

# Reads "<cpu> <memory>" lines, one per db node, and prints the smallest CPU in millicores and the
# smallest memory in MB, which need not come from the same node.
ss_smallest_allocatable() {
  local cpu memory milli mb min_milli="" min_mb=""
  while read -r cpu memory; do
    [ -n "${cpu}" ] || continue
    milli="$(ss_cpu_millicores "${cpu}")"
    mb="$(ss_memory_mb "${memory}")"
    if [ -z "${min_milli}" ] || [ "${milli}" -lt "${min_milli}" ]; then min_milli="${milli}"; fi
    if [ -z "${min_mb}" ] || [ "${mb}" -lt "${min_mb}" ]; then min_mb="${mb}"; fi
  done
  [ -n "${min_milli}" ] || ss_fail "no db node reports allocatable resources" || return 1
  echo "${min_milli} ${min_mb}"
}

# Prints "<cores> <memoryMB>" for a leaf on a node with ALLOC_MILLI millicores and ALLOC_MB MB
# allocatable: less the collector reserve, ceil((1 + AGGREGATORS) / LEAVES) aggregators of
# AGG_CORES cores and AGG_MEM_GB GB (the master and the child aggregators share the leaves' nodes),
# and the exporter sidecar. Cores are whole; a negative remainder prints 0.
ss_leaf_size() {
  local alloc_milli="$1" alloc_mb="$2" leaves="$3" aggregators="$4" agg_cores="$5" agg_mem_gb="$6"
  local share milli mb
  share=$(((1 + aggregators + leaves - 1) / leaves))
  milli=$((alloc_milli - SS_COLLECTOR_RESERVE_MILLICORES - share * agg_cores * 1000 - SS_EXPORTER_MILLICORES))
  mb=$((alloc_mb - SS_COLLECTOR_RESERVE_MB - share * agg_mem_gb * 1024 - SS_EXPORTER_MB))
  [ "${milli}" -ge 0 ] || milli=0
  [ "${mb}" -ge 0 ] || mb=0
  echo "$((milli / 1000)) ${mb}"
}

# Fails when a leaf of CORES cores and MB MB is below the minimum, giving both.
ss_check_leaf_minimum() {
  local cores="$1" mb="$2"
  if [ "${cores}" -lt "${SS_MIN_LEAF_CORES}" ] || [ "${mb}" -lt "${SS_MIN_LEAF_MB}" ]; then
    ss_fail "the leaf size ${cores} cores, ${mb} MB is below the minimum ${SS_MIN_LEAF_CORES} cores, ${SS_MIN_LEAF_MB} MB. Use larger db nodes (i4i.2xlarge or bigger), fewer --aggregators, or a smaller --aggregator-cores and --aggregator-memory." ||
      return 1
  fi
}

# Sizes the cluster from the kit args and the db nodes. Sets REDUNDANCY, LEAF_COUNT (leaves per
# availability group), LEAF_CORES, LEAF_MEMORY_MB, STORAGE_GB and AGGREGATOR_MEMORY_MB, and prints
# them. Fails, before anything is created, on a bad --size, a leaf count the redundancy does not
# divide, or a leaf below the minimum.
singlestore_size() {
  local leaves="${SINGLESTORE_LEAVES}" aggregators="${SINGLESTORE_AGGREGATORS}"
  local agg_cores="${SINGLESTORE_AGGREGATOR_CORES}" agg_mem_gb="${SINGLESTORE_AGGREGATOR_MEMORY}"
  local allocatable computed

  STORAGE_GB="$(ss_storage_gb "${STORAGE_SIZE}")" || return 1
  REDUNDANCY="$(ss_redundancy "${leaves}" "${SINGLESTORE_REDUNDANCY:-}")" || return 1
  LEAF_COUNT=$((leaves / REDUNDANCY))
  AGGREGATOR_MEMORY_MB=$((agg_mem_gb * 1024))

  if [ -n "${SINGLESTORE_LEAF_CORES:-}" ] && [ -n "${SINGLESTORE_LEAF_MEMORY:-}" ]; then
    LEAF_CORES="${SINGLESTORE_LEAF_CORES}"
    LEAF_MEMORY_MB=$((SINGLESTORE_LEAF_MEMORY * 1024))
  else
    allocatable="$(kubectl get nodes -l type=db \
      -o jsonpath='{range .items[*]}{.status.allocatable.cpu}{" "}{.status.allocatable.memory}{"\n"}{end}' |
      ss_smallest_allocatable)" || return 1
    # shellcheck disable=SC2086 # two numbers, split on purpose
    computed="$(ss_leaf_size ${allocatable} "${leaves}" "${aggregators}" "${agg_cores}" "${agg_mem_gb}")"
    LEAF_CORES="${SINGLESTORE_LEAF_CORES:-${computed% *}}"
    if [ -n "${SINGLESTORE_LEAF_MEMORY:-}" ]; then
      LEAF_MEMORY_MB=$((SINGLESTORE_LEAF_MEMORY * 1024))
    else
      LEAF_MEMORY_MB="${computed#* }"
    fi
  fi
  ss_check_leaf_minimum "${LEAF_CORES}" "${LEAF_MEMORY_MB}" || return 1

  echo "SingleStore: ${leaves} leaves (${LEAF_COUNT} per availability group, redundancy ${REDUNDANCY}), ${LEAF_CORES} cores and ${LEAF_MEMORY_MB} MB each, ${STORAGE_GB} GB storage; $((1 + aggregators)) aggregators of ${agg_cores} cores and ${AGGREGATOR_MEMORY_MB} MB"
}
