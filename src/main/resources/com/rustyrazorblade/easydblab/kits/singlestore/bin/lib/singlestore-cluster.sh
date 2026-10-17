#!/usr/bin/env bash
#
# The SingleStore kit's start and wait steps. kit.yaml sources this file from the kit directory;
# it sources the sizing and SQL libraries from its own directory. src/test/shell/
# singlestore-cluster.test.sh tests it with kubectl stubbed.
#
# A kit shell step runs under /bin/bash, which is bash 3.2 on macOS.

SS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SS_KIT_DIR="$(cd "${SS_LIB_DIR}/../.." && pwd)"
# shellcheck source=singlestore-sizing.sh
source "${SS_LIB_DIR}/singlestore-sizing.sh"
# shellcheck source=singlestore-sql.sh
source "${SS_LIB_DIR}/singlestore-sql.sh"

SS_NAMESPACE=default
SS_CLUSTER=singlestore
SS_POD_SELECTOR="easydblab/kit=singlestore"
SS_PVC_SELECTOR="app.kubernetes.io/instance=singlestore"
# The values start computes and memsqlcluster.yaml leaves for it, as ${NAME}.
SS_RUNTIME_VALUES="REDUNDANCY LEAF_COUNT LEAF_CORES LEAF_MEMORY_MB STORAGE_GB AGGREGATOR_MEMORY_MB"
# The wait polls every SS_WAIT_INTERVAL seconds, SS_WAIT_ATTEMPTS times (20 minutes).
SS_WAIT_INTERVAL="${SS_WAIT_INTERVAL:-10}"
SS_WAIT_ATTEMPTS="${SS_WAIT_ATTEMPTS:-120}"
# A license refusal in the operator log or the MemsqlCluster status: an invalid key (1888), no key
# (2446), or a cluster larger than the license allows. A line must say the license was refused,
# not just mention its capacity, which the operator also logs for a key the server accepted.
SS_LICENSE_REFUSAL='error (1888|2446)|licen[cs]e[^"]*(invalid|exceed|expired|not allow|required|refus)|(invalid|exceed|expired|refus)[^"]*licen[cs]e'

ss_cluster_fail() {
  echo "Error: $*" >&2
  return 1
}

# Deletes what a failed start can leave: the MemsqlCluster, then (once its pods are gone) the
# cluster's PVCs, which the operator labels with the cluster's instance, and the kit's Secrets.
ss_clear_leftovers() {
  local attempt
  kubectl delete memsqlcluster "${SS_CLUSTER}" -n "${SS_NAMESPACE}" --ignore-not-found
  for attempt in $(seq 1 "${SS_WAIT_ATTEMPTS}"); do
    kubectl get pods -l "${SS_POD_SELECTOR}" -n "${SS_NAMESPACE}" --no-headers 2> /dev/null | grep -q . || break
    [ "${attempt}" -lt "${SS_WAIT_ATTEMPTS}" ] || ss_cluster_fail "the pods of the previous SingleStore cluster did not go away" || return 1
    sleep "${SS_WAIT_INTERVAL}"
  done
  kubectl delete pvc -l "${SS_PVC_SELECTOR}" -n "${SS_NAMESPACE}" --ignore-not-found
  kubectl delete secret -l "${SS_POD_SELECTOR}" -n "${SS_NAMESPACE}" --ignore-not-found
}

# Creates or updates Secret $1 with key $2 holding the contents of file $3, labelled with the kit,
# apply-style. The value goes through a file, never a command line.
ss_apply_secret() {
  kubectl create secret generic "$1" -n "${SS_NAMESPACE}" "--from-file=$2=$3" --dry-run=client -o yaml |
    kubectl apply -f -
  kubectl label secret "$1" -n "${SS_NAMESPACE}" "${SS_POD_SELECTOR}" --overwrite > /dev/null
}

# Writes the license, with every trailing newline stripped (SingleStore refuses a key that ends
# with one), and the root password to the Secrets the MemsqlCluster names.
ss_apply_secrets() {
  local license="${SINGLESTORE_LICENSE}" dir status=0
  while :; do
    case "${license}" in
      *$'\n' | *$'\r') license="${license%?}" ;;
      *) break ;;
    esac
  done
  [ -n "${license}" ] || ss_cluster_fail "--license is empty" || return 1
  dir="$(umask 077 && mktemp -d)"
  printf '%s' "${license}" > "${dir}/license"
  printf '%s' "${SINGLESTORE_PASSWORD}" > "${dir}/password"
  ss_apply_secret singlestore-license license "${dir}/license" &&
    ss_apply_secret singlestore-root-password ROOT_PASSWORD "${dir}/password" || status=$?
  rm -rf "${dir}"
  return "${status}"
}

# Prints memsqlcluster.yaml with each ${NAME} in SS_RUNTIME_VALUES replaced by that variable.
ss_render_cluster() {
  local content name value
  content="$(cat "${SS_KIT_DIR}/memsqlcluster.yaml")"
  for name in ${SS_RUNTIME_VALUES}; do
    value="${!name}"
    content="${content//"\${${name}}"/${value}}"
  done
  case "${content}" in
    *'${'*) ss_cluster_fail "memsqlcluster.yaml has a value start does not fill in" || return 1 ;;
  esac
  printf '%s\n' "${content}"
}

# The start step: size the cluster (failing before anything changes), clear leftovers, write the
# Secrets, then apply the MemsqlCluster.
singlestore_start_cluster() {
  local manifest
  ss_check_unlimited_storage_args || return 1
  singlestore_size || return 1
  manifest="$(ss_render_cluster)" || return 1
  ss_clear_leftovers || return 1
  ss_apply_secrets || return 1
  printf '%s\n' "${manifest}" | kubectl apply -f -
}

# Prints the latest license refusal in the MemsqlCluster status or in the operator's log since the
# cluster was created, with the license key masked, and succeeds; fails when there is none.
ss_license_refusal() {
  local created status log line key="${SINGLESTORE_LICENSE%%[[:space:]]*}"
  created="$(kubectl get memsqlcluster "${SS_CLUSTER}" -n "${SS_NAMESPACE}" \
    -o jsonpath='{.metadata.creationTimestamp}' 2> /dev/null || true)"
  status="$(kubectl get memsqlcluster "${SS_CLUSTER}" -n "${SS_NAMESPACE}" -o jsonpath='{.status}' 2> /dev/null || true)"
  log="$(kubectl logs deployment/singlestore-operator -n "${SS_NAMESPACE}" ${created:+"--since-time=${created}"} \
    --tail=2000 2> /dev/null || true)"
  line="$(printf '%s\n%s\n' "${status}" "${log}" | grep -iE "${SS_LICENSE_REFUSAL}" | tail -1)"
  [ -n "${line}" ] || return 1
  [ -z "${key}" ] || line="${line//"${key}"/****}"
  printf '%s\n' "${line}"
}

# Prints how many SingleStore pods are Ready. The query's error goes to file $1, which is empty
# when it succeeded.
ss_ready_pods() {
  kubectl get pods -l "${SS_POD_SELECTOR}" -n "${SS_NAMESPACE}" \
    -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2> "$1" |
    grep -c '^True$' || true
}

# Prints how many leaves the master aggregator reports online. SingleStore's or kubectl's error
# goes to file $1, which is empty when the query succeeded.
ss_online_leaves() {
  echo "SHOW LEAVES;" | ss_mysql 2> "$1" | grep -ci 'online' || true
}

# Prints, for the timeout message, the error the last pod query and the last SHOW LEAVES left in
# directory $1, with the root password masked, so a wrong password or a refused connection reads
# differently from a slow cluster. Prints nothing when neither failed.
ss_last_errors() {
  local dir="$1" name text
  for name in pods leaves; do
    [ -s "${dir}/${name}.err" ] || continue
    text="$(cat "${dir}/${name}.err")"
    [ -z "${SINGLESTORE_PASSWORD:-}" ] || text="${text//"${SINGLESTORE_PASSWORD}"/****}"
    case "${name}" in
      pods) printf '\nThe last pod query failed: %s' "${text}" ;;
      leaves) printf '\nThe last SHOW LEAVES failed: %s' "${text}" ;;
    esac
  done
}

# The wait step: succeeds once every pod (the master, --aggregators child aggregators and
# --leaves leaves) is Ready and the master reports every leaf online. Fails at once on a license
# refusal, with SingleStore's error and the fix, and at the end of the wait with the pods' state
# and the last error of each query.
singlestore_wait() {
  local errors status=0
  errors="$(mktemp -d)"
  ss_wait_for_cluster "${errors}" || status=$?
  rm -rf "${errors}"
  return "${status}"
}

# The wait loop of singlestore_wait, which keeps each query's last error in directory $1.
ss_wait_for_cluster() {
  local errors="$1" expected=$((1 + SINGLESTORE_AGGREGATORS + SINGLESTORE_LEAVES)) attempt refusal ready=0 online=0
  echo "Waiting for ${expected} SingleStore pods and ${SINGLESTORE_LEAVES} online leaves..."
  for attempt in $(seq 1 "${SS_WAIT_ATTEMPTS}"); do
    if refusal="$(ss_license_refusal)"; then
      ss_cluster_fail "SingleStore refused the license: ${refusal}
The license does not cover this cluster. Re-run 'kit install singlestore --force' with the same
args and smaller --leaf-cores and --leaf-memory, then 'singlestore start'."
      return 1
    fi
    ready="$(ss_ready_pods "${errors}/pods.err")"
    if [ "${ready}" -ge "${expected}" ]; then
      online="$(ss_online_leaves "${errors}/leaves.err")"
      if [ "${online}" -ge "${SINGLESTORE_LEAVES}" ]; then
        echo "SingleStore is ready: ${expected} pods, ${SINGLESTORE_LEAVES} leaves online"
        return 0
      fi
    fi
    [ "${attempt}" -lt "${SS_WAIT_ATTEMPTS}" ] && sleep "${SS_WAIT_INTERVAL}"
  done
  kubectl get pods -l "${SS_POD_SELECTOR}" -n "${SS_NAMESPACE}" -o wide >&2 || true
  ss_cluster_fail "timed out waiting for SingleStore: ${ready} of ${expected} pods Ready, ${online} of ${SINGLESTORE_LEAVES} leaves online$(ss_last_errors "${errors}")"
}
