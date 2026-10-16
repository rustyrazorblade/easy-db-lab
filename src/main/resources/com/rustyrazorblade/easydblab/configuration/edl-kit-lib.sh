# shellcheck shell=bash
#
# edl-kit-lib.sh: the shared kit shell library.
#
# easy-db-lab writes this file into <workspace>/bin/ once, never rewrites it, and sets EDL_KIT_LIB
# to its absolute path for every kit process. A kit script sources it with:
#
#   . "${EDL_KIT_LIB:?run this through easy-db-lab, which sets EDL_KIT_LIB}"
#
# It holds what every kit that runs pods needs: the app-node check, pod placement, labelled
# ConfigMaps, the waits for a pod to start and to exit, log following, and the gauge push. Report
# parsers, argument checks and SQL stay in each kit's own lib/.
#
# Rules for this file:
# - Sourcing it changes no `set` option, trap or working directory, and defines only edl_
#   functions and EDL_ variables.
# - It runs under bash 3.2, the macOS /bin/bash: no associative arrays, mapfile, ${v,,} or
#   wait -n, and no "${arr[@]}" on an array that can be empty (it fails under set -u before 4.4).
# - It needs no jq.
#
# The functions read KIT_NAME, KUBECONFIG (through kubectl) and CONTROL_HOST_PRIVATE from the
# environment. A function that fails prints why to stderr and exits the calling script.

# How long a pod may stay Pending, with no image pull error, before the wait gives up.
EDL_POD_START_TIMEOUT_SECONDS="${EDL_POD_START_TIMEOUT_SECONDS:-600}"
# Seconds between two polls of a pod.
EDL_POLL_SECONDS="${EDL_POLL_SECONDS:-2}"
# The most bytes edl_create_configmap puts in one ConfigMap; the API server rejects more than 1 MiB.
EDL_CONFIGMAP_MAX_BYTES=1000000

# Prints the message to stderr and exits the calling script with status 1.
edl_fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

# Prints the label selector for this kit instance and role $1.
edl_labels() {
  printf 'easydblab/kit=%s,easydblab/role=%s\n' "${KIT_NAME}" "$1"
}

# Prints a unique name for a resource of role $1: ${KIT_NAME}-<role>-<epoch seconds>.
edl_resource_name() {
  printf '%s-%s-%s\n' "${KIT_NAME}" "$1" "$(date +%s)"
}

# Fails, naming kit $1, when the cluster has no node labelled type=app.
edl_require_app_node() {
  local nodes
  nodes="$(kubectl get nodes -l type=app -o name)" || edl_fail "Could not list the cluster's app nodes"
  [ -n "${nodes}" ] || edl_fail "$1 needs an app node (type=app); this cluster has none"
}

# Prints the JSON for `kubectl run --overrides=<json> --override-type=strategic`: the pod runs on an
# app node and, when the three arguments are given, mounts ConfigMap $1 at path $2 in container $3.
# kubectl run names the container after the pod, so $3 is the pod name.
edl_pod_overrides() {
  local placement='"nodeSelector":{"type":"app"}'
  case "$#" in
    0)
      printf '{"spec":{%s}}\n' "${placement}"
      ;;
    3)
      edl_require_dns_name "ConfigMap" "$1" 253
      edl_require_dns_name "container" "$3" 63
      case "$2" in
        /*) ;;
        *) edl_fail "Mount path '$2' is not an absolute path" ;;
      esac
      case "$2" in
        *'"'* | *'\'*) edl_fail "Mount path '$2' holds a quote or a backslash" ;;
      esac
      printf '{"spec":{%s,"volumes":[{"name":"edl-files","configMap":{"name":"%s"}}],"containers":[{"name":"%s","volumeMounts":[{"name":"edl-files","mountPath":"%s"}]}]}}\n' \
        "${placement}" "$1" "$3" "$2"
      ;;
    *)
      edl_fail "edl_pod_overrides takes no arguments, or a ConfigMap, a mount path and a container"
      ;;
  esac
}

# Fails unless $2 is a lowercase DNS-1123 name of at most $3 characters; $1 says what it names.
edl_require_dns_name() {
  [ "${#2}" -le "$3" ] && [[ "$2" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] ||
    edl_fail "$1 name '$2' is not a DNS-1123 name (lowercase letters, digits and '-', at most $3 characters)"
}

# Creates ConfigMap $1 with role $2's labels from files given as <key>=<path>, in one call, so it
# never exists without its labels. Every file must exist and hold data, and together they must fit
# in EDL_CONFIGMAP_MAX_BYTES. It ends with `create`, not `apply`: apply's last-applied annotation
# would hold a second copy of the data and lower the limit.
edl_create_configmap() {
  local name="$1" role="$2" spec path total=0 size
  shift 2
  [ "$#" -gt 0 ] || edl_fail "edl_create_configmap needs at least one <key>=<path>"
  local from=()
  for spec in "$@"; do
    path="${spec#*=}"
    [ -f "${path}" ] || edl_fail "File ${path} does not exist"
    [ -s "${path}" ] || edl_fail "File ${path} is empty"
    size="$(wc -c < "${path}" | tr -d ' ')"
    total=$((total + size))
    from+=("--from-file=${spec}")
  done
  [ "${total}" -le "${EDL_CONFIGMAP_MAX_BYTES}" ] ||
    edl_fail "ConfigMap ${name} would hold ${total} bytes, more than the limit of ${EDL_CONFIGMAP_MAX_BYTES} bytes"
  (
    set -o pipefail
    kubectl create configmap "${name}" "${from[@]}" --dry-run=client -o yaml |
      kubectl label --local -f - "easydblab/kit=${KIT_NAME}" "easydblab/role=${role}" -o yaml |
      kubectl create -f -
  ) > /dev/null || edl_fail "Could not create ConfigMap ${name}"
}

# Prints, one per line, the pods of this instance with role $1 that have not ended.
edl_active_pods() {
  # Commas in a field selector are AND, so this keeps every phase but the two that end a pod.
  kubectl get pods -l "$(edl_labels "$1")" \
    --field-selector=status.phase!=Succeeded,status.phase!=Failed \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' ||
    edl_fail "Could not list the $1 pods of ${KIT_NAME}"
}

# Prints the fields of pod $1 that the jsonpath $2 selects. Returns 3 when the pod does not exist
# and 1 when kubectl failed, which callers treat as a reason to ask again.
edl_pod_get() {
  local out
  out="$(kubectl get pod "$1" --ignore-not-found -o "jsonpath={.metadata.name}|$2")" || return 1
  [ -n "${out}" ] || return 3
  printf '%s\n' "${out#*|}"
}

# Deletes pod $1 that never started. Left Pending, it would keep its role label, so the start guard
# and edl_active_pods would refuse the next run until `stop`. Its events were printed already.
edl_delete_unstarted_pod() {
  kubectl delete pod "$1" --ignore-not-found > /dev/null 2>&1 ||
    printf 'warning: could not delete pod %s; run stop to remove it\n' "$1" >&2
}

# Returns once pod $1 has left Pending: Running, or already Succeeded or Failed. Fails at once on an
# image pull error, and after EDL_POD_START_TIMEOUT_SECONDS otherwise, and deletes the pod first.
edl_wait_for_pod_running() {
  local pod="$1" started status rc phase rest reason message
  started="$(date +%s)"
  while :; do
    if status="$(edl_pod_get "${pod}" '{.status.phase}|{.status.containerStatuses[0].state.waiting.reason}|{.status.containerStatuses[0].state.waiting.message}')"; then
      rc=0
    else
      rc=$?
    fi
    case "${rc}" in
      3) edl_fail "pod ${pod} was deleted" ;;
      0)
        phase="${status%%|*}"
        rest="${status#*|}"
        reason="${rest%%|*}"
        message="${rest#*|}"
        case "${phase}" in
          Running | Succeeded | Failed) return 0 ;;
        esac
        case "${reason}" in
          ErrImagePull | ImagePullBackOff | InvalidImageName)
            printf 'pod %s cannot start: %s: %s\n' "${pod}" "${reason}" "${message}" >&2
            kubectl describe pod "${pod}" >&2 || true
            edl_delete_unstarted_pod "${pod}"
            edl_fail "pod ${pod} cannot pull its image (${reason})"
            ;;
        esac
        ;;
    esac
    if [ $(($(date +%s) - started)) -ge "${EDL_POD_START_TIMEOUT_SECONDS}" ]; then
      kubectl describe pod "${pod}" >&2 || true
      edl_delete_unstarted_pod "${pod}"
      edl_fail "pod ${pod} did not start within ${EDL_POD_START_TIMEOUT_SECONDS} seconds"
    fi
    sleep "${EDL_POLL_SECONDS}"
  done
}

# Streams pod $1's log, passing each line without its timestamp to the function named $2, until the
# pod ends. When the stream is cut while the pod runs, it resumes from the last timestamp seen;
# --since-time has one-second precision, so a resumed stream can repeat up to a second of lines.
# A failed `kubectl logs` never ends the calling script. Fails when the pod is deleted.
edl_follow_pod_logs() {
  local pod="$1" handler="$2" since="" line stamp phase rc
  while :; do
    while IFS= read -r line; do
      stamp="${line%% *}"
      case "${stamp}" in
        [0-9][0-9][0-9][0-9]-*)
          since="${stamp}"
          case "${line}" in
            *" "*) line="${line#* }" ;;
            *) line="" ;;
          esac
          ;;
      esac
      "${handler}" "${line}"
    done < <(kubectl logs -f --timestamps ${since:+"--since-time=${since}"} "${pod}" || true)

    # The phase is read after the pause, right before a resume, so a pod that ended during the
    # pause is not streamed again.
    while :; do
      sleep "${EDL_POLL_SECONDS}"
      if phase="$(edl_pod_get "${pod}" '{.status.phase}')"; then rc=0; else rc=$?; fi
      case "${rc}" in
        0) break ;;
        3) edl_fail "pod ${pod} was deleted" ;;
      esac
    done
    case "${phase}" in
      Succeeded | Failed) return 0 ;;
    esac
  done
}

# Waits, with no time limit, until pod $1 has ended, and prints its container's exit code, or
# `unknown` when a failed pod reports none. Fails when the pod is deleted.
edl_wait_for_pod_exit() {
  local pod="$1" status rc code
  while :; do
    if status="$(edl_pod_get "${pod}" '{.status.phase}|{.status.containerStatuses[0].state.terminated.exitCode}')"; then
      rc=0
    else
      rc=$?
    fi
    case "${rc}" in
      3) edl_fail "pod ${pod} was deleted" ;;
      0)
        code="${status#*|}"
        case "${status%%|*}" in
          Succeeded) printf '%s\n' "${code:-0}" && return 0 ;;
          Failed) printf '%s\n' "${code:-unknown}" && return 0 ;;
        esac
        ;;
    esac
    sleep "${EDL_POLL_SECONDS}"
  done
}

# Pushes gauges labelled kit=${KIT_NAME} to the collector on the control node as OTLP JSON, under
# service name $1. Usage: edl_push_gauges <service-name> <name> <value> [<name> <value> ...]. A value
# that is missing or not a number is sent as 0. A failed push never fails the calling script; the
# first one prints a warning, and EDL_GAUGE_PUSH_WARNED keeps the rest quiet.
edl_push_gauges() {
  local service="$1" now points="" sep="" value code=0
  local url="http://${CONTROL_HOST_PRIVATE}:4318/v1/metrics"
  shift
  now="$(date +%s)000000000"
  while [ "$#" -ge 1 ]; do
    value="${2:-}"
    [[ "${value}" =~ ^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$ ]] || value=0
    points="${points}${sep}{\"name\":\"$1\",\"gauge\":{\"dataPoints\":[{\"asDouble\":${value},\"timeUnixNano\":\"${now}\",\"attributes\":[{\"key\":\"kit\",\"value\":{\"stringValue\":\"${KIT_NAME}\"}}]}]}}"
    sep=","
    if [ "$#" -ge 2 ]; then shift 2; else shift; fi
  done
  curl -sf --max-time 2 -X POST "${url}" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"resourceMetrics\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"${service}\"}}]},\"scopeMetrics\":[{\"metrics\":[${points}]}]}]}" \
    > /dev/null 2>&1 || code=$?
  if [ "${code}" -ne 0 ] && [ -z "${EDL_GAUGE_PUSH_WARNED:-}" ]; then
    EDL_GAUGE_PUSH_WARNED=1
    printf 'warning: could not push %s gauges to %s (curl exit %s); the dashboard will not show this run\n' \
      "${service}" "${url}" "${code}" >&2
  fi
  return 0
}
