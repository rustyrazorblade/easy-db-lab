#!/bin/sh
#
# The cqlite-flight pod's detect-multidisk init container (pmcfadin/cqlite#2114). cqlite-flight
# serves exactly one --data-dir. When Cassandra spreads data_file_directories over several disks,
# the SSTables on the other disks are missing from every read, with no error. This script makes
# that visible: it warns, in `kubectl logs <pod> -c detect-multidisk`, unless the served dir is the
# only data dir under the data root. The layout check only warns and never blocks the rollout.
#
# It then checks that the pod can read the served dir. The init container runs as the flight
# process does (uid 10001, with --data-gid as a supplementary group) and mounts the dir the same
# way, so when it cannot enter the data dir, a keyspace dir or a table dir, no read would work. It
# then exits 1, naming --data-gid, and the pod does not start. It runs under the image's /bin/sh
# (POSIX sh).
#
# Environment:
#   DETECT_SERVED     host path of the served --data-dir
#   DETECT_ROOT_HOST  host path of the --data-root
#   DETECT_ROOT       where the pod mounts the data root (read-only)
#   DETECT_DATA       where the pod mounts the served --data-dir (read-only)
#   DETECT_DATA_GID   the --data-gid the pod reads it with
#
# Each disk mounts at <root>/<disk>/cassandra/data. A data dir counts whether or not it holds
# files: an empty one receives SSTables later. Paths are compared after squeezing repeated
# slashes and resolving symlinks (cd -P / pwd -P), so a link to a disk is the same disk, both
# when it is the served --data-dir and when it is one more entry under the root.

set -u

# Prints $1 with every run of slashes squeezed to one and no trailing slash ("/" stays "/").
squeeze() {
  p="$1"
  while :; do
    case "${p}" in
      *//*) p="${p%%//*}/${p#*//}" ;;
      *) break ;;
    esac
  done
  [ "${p}" = "/" ] || p="${p%/}"
  printf '%s\n' "${p:-/}"
}

root_host="$(squeeze "${DETECT_ROOT_HOST}")"
root="$(squeeze "${DETECT_ROOT}")"
root_phys="$(cd -P "${root}" 2>/dev/null && pwd -P)" || root_phys="${root}"

# Prints the host path of pod path $1 with its symlinks resolved, or $1's host path unresolved
# when it is not a directory the pod can enter (a link to a path outside the mount).
host_path() {
  phys="$(cd -P "$1" 2>/dev/null && pwd -P)" || phys=""
  case "${phys}" in
    "${root_phys}") printf '%s\n' "${root_host}" ;;
    "${root_phys}"/*) printf '%s\n' "${root_host}${phys#"${root_phys}"}" ;;
    *) printf '%s\n' "${root_host}${1#"${root}"}" ;;
  esac
}

served="$(squeeze "${DETECT_SERVED}")"
case "${served}" in
  "${root_host}"/*) served="$(host_path "${root}${served#"${root_host}"}")" ;;
esac

found=""
count=0
for d in "${root}"/*/cassandra/data; do
  [ -d "${d}" ] || continue
  h="$(host_path "${d}")"
  case " ${found} " in
    *" ${h} "*) continue ;;
  esac
  found="${found} ${h}"
  count=$((count + 1))
done

# Prints the layout result: OK on stdout, or a warning on stderr. Never fails.
report_layout() {
  if [ "${count}" -eq 1 ] && [ "${found# }" = "${served}" ]; then
    echo "cqlite-flight multi-disk check: OK, ${served} is the only data dir under ${root_host}."
    return 0
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
    return 0
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
}

# Prints the first dir of the served data dir (itself, a keyspace dir or a table dir, at pod path
# $1) that this process cannot list and enter, and fails; succeeds when it can read them all.
unreadable_dir() {
  for dir in "$1" "$1"/*/ "$1"/*/*/; do
    [ -d "${dir}" ] || continue
    if ! [ -r "${dir}" ] || ! [ -x "${dir}" ]; then
      dir="${dir%/}"
      printf '%s\n' "${dir}"
      return 0
    fi
  done
  return 1
}

report_layout

data="$(squeeze "${DETECT_DATA}")"
if bad="$(unreadable_dir "${data}")"; then
  bad_host="$(squeeze "$(squeeze "${DETECT_SERVED}")${bad#"${data}"}")"
  {
    echo "cqlite-flight cannot read ${bad_host} (served --data-dir ${served})"
    echo "as uid $(id -u 2>/dev/null || echo '?') with groups $(id -G 2>/dev/null || echo '?'). It reads the data dir with --data-gid (${DETECT_DATA_GID}) as a"
    echo "supplementary group, so --data-gid must be the GID that owns the data dir on every db node"
    echo "(stat -c %g ${served}), with group read and execute on its keyspace and table dirs."
    echo "Reinstall the kit with the right --data-gid."
  } >&2
  exit 1
fi
echo "cqlite-flight access check: passed, ${served} is readable with --data-gid (${DETECT_DATA_GID})."
exit 0
