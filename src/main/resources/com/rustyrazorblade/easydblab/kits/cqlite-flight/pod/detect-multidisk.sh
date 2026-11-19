#!/bin/sh
#
# The cqlite-flight pod's detect-multidisk init container (pmcfadin/cqlite#2114). cqlite-flight
# serves exactly one --data-dir. When Cassandra spreads data_file_directories over several disks,
# the SSTables on the other disks are missing from every read, with no error. This script makes
# that visible: it warns, in `kubectl logs <pod> -c detect-multidisk`, unless the served dir is the
# only data dir under the data root.
#
# It always exits 0, so it never blocks the rollout. It runs under the image's /bin/sh (POSIX sh).
#
# Environment:
#   DETECT_SERVED     host path of the served --data-dir
#   DETECT_ROOT_HOST  host path of the --data-root
#   DETECT_ROOT       where the pod mounts the data root (read-only)
#
# Each disk mounts at <root>/<disk>/cassandra/data. A data dir counts whether or not it holds
# files: an empty one receives SSTables later.

set -u

served="${DETECT_SERVED%/}"
root_host="${DETECT_ROOT_HOST%/}"
root="${DETECT_ROOT%/}"

found=""
count=0
for d in "${root}"/*/cassandra/data; do
  [ -d "${d}" ] || continue
  found="${found} ${root_host}${d#"${root}"}"
  count=$((count + 1))
done

if [ "${count}" -eq 1 ] && [ "${found# }" = "${served}" ]; then
  echo "cqlite-flight multi-disk check: OK, ${served} is the only data dir under ${root_host}."
  exit 0
fi

if [ "${count}" -le 1 ]; then
  {
    echo "################################################################"
    echo "## WARNING: cqlite-flight CANNOT VERIFY this node's disk layout ##"
    echo "################################################################"
    echo "--data-root (${root_host}) should hold the served --data-dir (${served}) as"
    echo "<root>/<disk>/cassandra/data. Data dirs found there:${found:- none}."
    echo "A multi-disk node would not be caught. Point --data-root at the directory"
    echo "holding the per-disk mounts, and --data-dir at one of them."
    echo "Serving ${served} regardless."
  } >&2
  exit 0
fi

{
  echo "############################################################"
  echo "## WARNING: MULTIPLE Cassandra data dirs on this node      ##"
  echo "############################################################"
  echo "cqlite-flight serves exactly one --data-dir and cannot span disks."
  echo "This node has ${count} data dirs under ${root_host}:"
  for f in ${found}; do
    if [ "${f}" = "${served}" ]; then
      echo "  - ${f}   <-- SERVED by this pod"
    else
      echo "  - ${f}   (NOT served: its SSTables are missing from query results)"
    fi
  done
  echo "Reads of data on an unserved disk return partial results with no error. Keep the"
  echo "queried tables on the served disk, or run one cqlite-flight per disk on distinct ports."
} >&2
exit 0
