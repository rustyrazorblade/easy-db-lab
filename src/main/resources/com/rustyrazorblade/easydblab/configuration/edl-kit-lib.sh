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
# The functions read KIT_NAME, NAMESPACE and KUBECONFIG (through kubectl) from the environment. Every
# function that calls the cluster acts in NAMESPACE, the cluster namespace easy-db-lab sets for every
# kit process, and exits the calling script, before any cluster call, when it is not set. A function
# that fails prints why to stderr and exits the calling script.

# How long a pod may stay Pending, with no image pull error, before the wait gives up.
EDL_POD_START_TIMEOUT_SECONDS="${EDL_POD_START_TIMEOUT_SECONDS:-600}"
# Seconds between two polls of a pod.
EDL_POLL_SECONDS="${EDL_POLL_SECONDS:-2}"
# The most bytes edl_create_configmap puts in one ConfigMap; the API server rejects more than 1 MiB.
EDL_CONFIGMAP_MAX_BYTES=1000000
# Checks, 0.1s apart, for the gauge push's `kubectl proxy` to start serving.
EDL_PROXY_START_TRIES="${EDL_PROXY_START_TRIES:-50}"
# The longest the gauge push waits on one API server lookup, so a hung API server cannot stall a kit.
EDL_GAUGE_PUSH_TIMEOUT="${EDL_GAUGE_PUSH_TIMEOUT:-5s}"

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

# Fails, naming kit $1, when no node matches the app role: APP_NODE_LABELS, a label selector, or any
# schedulable node when it is empty (an unconstrained app role).
edl_require_app_node() {
  local nodes labels="${APP_NODE_LABELS:-}"
  if [ -n "${labels}" ]; then
    nodes="$(kubectl get nodes -l "${labels}" -o name)" || edl_fail "Could not list the cluster's app nodes"
  else
    nodes="$(kubectl get nodes -o name)" || edl_fail "Could not list the cluster's nodes"
    labels="any node"
  fi
  [ -n "${nodes}" ] || edl_fail "$1 needs an app node (${labels}); this cluster has none"
}

# Prints the JSON for `kubectl run --overrides=<json> --override-type=strategic`: the pod runs on a
# node of the app role (APP_NODE_SELECTOR, a JSON map; {} when unconstrained) and, when the three
# arguments are given, mounts ConfigMap $1 at path $2 in container $3. kubectl run names the
# container after the pod, so $3 is the pod name.
edl_pod_overrides() {
  local selector="${APP_NODE_SELECTOR:-}"
  [ -n "${selector}" ] || selector='{}'
  local placement="\"nodeSelector\":${selector}"
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
# in EDL_CONFIGMAP_MAX_BYTES. A leading `--flag <flag>` names the kit flag whose file the user
# chose, so the over-limit error tells the user what to shrink. It ends with `create`, not `apply`:
# apply's last-applied annotation would hold a second copy of the data and lower the limit.
edl_create_configmap() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  local flag="" hint=""
  if [ "${1:-}" = --flag ]; then
    [ "$#" -ge 2 ] && [ -n "$2" ] || edl_fail "edl_create_configmap --flag needs a flag name"
    flag="$2"
    shift 2
  fi
  [ "$#" -ge 2 ] || edl_fail "edl_create_configmap needs a name and a role"
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
  [ -z "${flag}" ] || hint=": the file given by ${flag} is too large; use a smaller one"
  [ "${total}" -le "${EDL_CONFIGMAP_MAX_BYTES}" ] ||
    edl_fail "ConfigMap ${name} would hold ${total} bytes, more than the limit of ${EDL_CONFIGMAP_MAX_BYTES} bytes (Kubernetes caps a ConfigMap at 1 MiB)${hint}"
  (
    set -o pipefail
    kubectl create configmap "${name}" "${from[@]}" --dry-run=client -o yaml |
      kubectl label --local -f - "easydblab/kit=${KIT_NAME}" "easydblab/role=${role}" -o yaml |
      kubectl create -f - -n "${NAMESPACE}"
  ) > /dev/null || edl_fail "Could not create ConfigMap ${name}"
}

# Prints, one per line, the pods of this instance with role $1 that have not ended.
edl_active_pods() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  # Commas in a field selector are AND, so this keeps every phase but the two that end a pod.
  kubectl get pods -n "${NAMESPACE}" -l "$(edl_labels "$1")" \
    --field-selector=status.phase!=Succeeded,status.phase!=Failed \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' ||
    edl_fail "Could not list the $1 pods of ${KIT_NAME}"
}

# Prints the fields of pod $1 that the jsonpath $2 selects. Returns 3 when the pod does not exist
# and 1 when kubectl failed, which callers treat as a reason to ask again.
edl_pod_get() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  local out
  out="$(kubectl get pod "$1" -n "${NAMESPACE}" --ignore-not-found -o "jsonpath={.metadata.name}|$2")" || return 1
  [ -n "${out}" ] || return 3
  printf '%s\n' "${out#*|}"
}

# Deletes pod $1 that never started. Left Pending, it would keep its role label, so the start guard
# and edl_active_pods would refuse the next run until `stop`. Its events were printed already.
edl_delete_unstarted_pod() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  kubectl delete pod "$1" -n "${NAMESPACE}" --ignore-not-found > /dev/null 2>&1 ||
    printf 'warning: could not delete pod %s; run stop to remove it\n' "$1" >&2
}

# Returns once pod $1 has left Pending: Running, or already Succeeded or Failed. Fails at once on an
# image pull error, and after EDL_POD_START_TIMEOUT_SECONDS otherwise, and deletes the pod first.
edl_wait_for_pod_running() {
  : "${NAMESPACE:?NAMESPACE is not set}"
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
            kubectl describe pod "${pod}" -n "${NAMESPACE}" >&2 || true
            edl_delete_unstarted_pod "${pod}"
            edl_fail "pod ${pod} cannot pull its image (${reason})"
            ;;
        esac
        ;;
    esac
    if [ $(($(date +%s) - started)) -ge "${EDL_POD_START_TIMEOUT_SECONDS}" ]; then
      kubectl describe pod "${pod}" -n "${NAMESPACE}" >&2 || true
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
  : "${NAMESPACE:?NAMESPACE is not set}"
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
    done < <(kubectl logs -n "${NAMESPACE}" -f --timestamps ${since:+"--since-time=${since}"} "${pod}" || true)

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
  : "${NAMESPACE:?NAMESPACE is not set}"
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

# Pushes gauges labelled kit=${KIT_NAME} to the core OTel collector as OTLP JSON, under service name
# $1. Usage: edl_push_gauges <service-name> <name> <value> [<name> <value> ...]. A value that is
# missing or not a number is sent as 0. A failed push never fails the calling script; the first one
# prints a warning, and EDL_GAUGE_PUSH_WARNED keeps the rest quiet.
#
# Every push goes to the core collector pod on the control node, through the API server's pod
# proxy, on a `kubectl proxy` that lives only for this push: no node listens for OTLP unless the host
# add-on runs there, and `kubectl create --raw` cannot send the Content-Type the collector requires.
# It is one pod, not the Service: the API server's service proxy picks any core pod, and each stamps
# host.name with its own node, so one gauge would split into a series per node. The control node's
# pod stamps host.name=control0, as the control node's collector did before the core moved to the
# pod network.
#
# The pod is the first Running core pod on the control node with no deletionTimestamp: during a
# rollout the pod being replaced is still Running until it stops.
#
# A TERM or an INT during the push is noted, not acted on in the trap: the push skips what is left
# (the running curl finishes, within its 2-second limit), stops the proxy, removes its banner file,
# restores the caller's INT and TERM traps and then sends the signal to this shell again, so the
# caller's trap runs or, with none, the script ends by the signal.
edl_push_gauges() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  local service="$1" now points="" sep="" value failure="" port="" tries=0
  local control pods pod="" name deleting
  shift
  now="$(date +%s)000000000"
  while [ "$#" -ge 1 ]; do
    value="${2:-}"
    [[ "${value}" =~ ^-?[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$ ]] || value=0
    points="${points}${sep}{\"name\":\"$1\",\"gauge\":{\"dataPoints\":[{\"asDouble\":${value},\"timeUnixNano\":\"${now}\",\"attributes\":[{\"key\":\"kit\",\"value\":{\"stringValue\":\"${KIT_NAME}\"}}]}]}}"
    sep=","
    if [ "$#" -ge 2 ]; then shift 2; else shift; fi
  done
  # The control role's node, by CONTROL_NODE_LABELS. With no control selector, or no node matching
  # it, there is no control node to pin to: the push takes the first Running core pod by name,
  # which stays the same between pushes, so one gauge stays one series.
  control=""
  if [ -n "${CONTROL_NODE_LABELS:-}" ] &&
    ! control="$(kubectl get nodes "--request-timeout=${EDL_GAUGE_PUSH_TIMEOUT}" -l "${CONTROL_NODE_LABELS}" -o 'jsonpath={.items[0].metadata.name}' 2> /dev/null)"; then
    failure=": could not find the control node"
  elif [ -n "${control}" ]; then
    pods="$(kubectl get pods "--request-timeout=${EDL_GAUGE_PUSH_TIMEOUT}" -n "${NAMESPACE}" -l app.kubernetes.io/name=otel-collector \
      "--field-selector=status.phase=Running,spec.nodeName=${control}" \
      -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.metadata.deletionTimestamp}{"\n"}{end}' 2> /dev/null)" ||
      failure=": could not list the otel-collector pods on control node ${control}"
  else
    pods="$(kubectl get pods "--request-timeout=${EDL_GAUGE_PUSH_TIMEOUT}" -n "${NAMESPACE}" -l app.kubernetes.io/name=otel-collector \
      "--field-selector=status.phase=Running" \
      -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.metadata.deletionTimestamp}{"\n"}{end}' 2> /dev/null)" ||
      failure=": could not list the otel-collector pods"
    pods="$(printf '%s\n' "${pods}" | sort)"
  fi
  # The core collector tolerates every taint, so the control node always runs a core pod, and that
  # pod stamps host.name=control0. While it is Pending (a rollout, a slow image pull) the pod list
  # above has nothing Running there: the push warns and skips, and the kit runs on.
  if [ -z "${failure}" ]; then
    # During a rolling update a node can run two core pods; both stamp the same node. A pod being
    # deleted is still Running until it stops, so the first one with no deletionTimestamp.
    while read -r name deleting; do
      if [ -n "${name}" ] && [ -z "${deleting}" ]; then
        pod="${name}"
        break
      fi
    done <<< "${pods}"
    if [ -z "${pod}" ] && [ -n "${control}" ]; then
      failure=": no running otel-collector pod on control node ${control}"
    elif [ -z "${pod}" ]; then
      failure=": no running otel-collector pod"
    fi
  fi
  if [ -z "${failure}" ]; then
    EDL_PUSH_SAVED_TRAPS="$(trap -p INT TERM)"
    EDL_PUSH_SIGNAL=""
    trap 'EDL_PUSH_SIGNAL=INT' INT
    trap 'EDL_PUSH_SIGNAL=TERM' TERM
    EDL_PUSH_BANNER="$(mktemp)"
    kubectl proxy --port=0 --address=127.0.0.1 > "${EDL_PUSH_BANNER}" 2>&1 &
    EDL_PUSH_PROXY_PID=$!
    while [ -z "${port}" ] && [ -z "${EDL_PUSH_SIGNAL}" ] && [ "${tries}" -lt "${EDL_PROXY_START_TRIES}" ]; do
      port="$(sed -n 's/^Starting to serve on 127\.0\.0\.1:\([0-9][0-9]*\)$/\1/p' "${EDL_PUSH_BANNER}")"
      [ -n "${port}" ] || { tries=$((tries + 1)); sleep 0.1; }
    done
    if [ -n "${EDL_PUSH_SIGNAL}" ]; then
      :
    elif [ -n "${port}" ]; then
      local code=0
      curl -sf --max-time 2 -X POST \
        "http://127.0.0.1:${port}/api/v1/namespaces/${NAMESPACE}/pods/${pod}:4318/proxy/v1/metrics" \
        -H 'Content-Type: application/json' \
        --data-binary "{\"resourceMetrics\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"${service}\"}}]},\"scopeMetrics\":[{\"metrics\":[${points}]}]}]}" \
        > /dev/null 2>&1 || code=$?
      [ "${code}" -eq 0 ] || failure=" to collector pod ${pod} (curl exit ${code})"
    else
      failure=": kubectl proxy did not start: $(cat "${EDL_PUSH_BANNER}" 2> /dev/null)"
    fi
    local signal="${EDL_PUSH_SIGNAL}"
    edl_push_end
    # Sent again only now, outside the trap: bash 3.2 loses an INT a trap sends to its own shell.
    # BASHPID is this shell even in a subshell; bash 3.2 has none, and there $$ is the script,
    # where every kit pushes from.
    if [ -n "${signal}" ]; then
      kill -s "${signal}" "${BASHPID:-$$}" || true
      return 0
    fi
  fi
  if [ -n "${failure}" ] && [ -z "${EDL_GAUGE_PUSH_WARNED:-}" ]; then
    EDL_GAUGE_PUSH_WARNED=1
    printf 'warning: could not push %s gauges%s; the dashboard will not show this run\n' "${service}" "${failure}" >&2
  fi
  return 0
}

# Ends edl_push_gauges' proxy: stops the kubectl proxy, removes its banner file, puts the caller's
# INT and TERM traps back and clears the push's state. The state lives in the EDL_PUSH_* globals,
# set only while a push runs and named for it, so no caller variable is read or changed; a second
# call does nothing.
edl_push_end() {
  [ -n "${EDL_PUSH_SAVED_TRAPS+set}" ] || return 0
  if [ -n "${EDL_PUSH_PROXY_PID:-}" ]; then
    kill "${EDL_PUSH_PROXY_PID}" 2> /dev/null || true
    wait "${EDL_PUSH_PROXY_PID}" 2> /dev/null || true
  fi
  if [ -n "${EDL_PUSH_BANNER:-}" ]; then
    rm -f "${EDL_PUSH_BANNER}"
  fi
  trap - INT TERM
  eval "${EDL_PUSH_SAVED_TRAPS:-}"
  unset EDL_PUSH_PROXY_PID EDL_PUSH_BANNER EDL_PUSH_SAVED_TRAPS EDL_PUSH_SIGNAL
}

# ---------------------------------------------------------------------------------------------
# Helm-chart kits with a coordinator and workers (presto, trino).
#
# These functions need `helm` and `kubectl`, and work in the cluster namespace, NAMESPACE.
# ---------------------------------------------------------------------------------------------

# The catalogs the next edl_helm_upgrade_with_catalogs adds, as YAML map entries. Set by
# edl_collect_catalogs.
EDL_CATALOG_CONTENT=""

# Appends the catalog entry $1 to EDL_CATALOG_CONTENT from the properties file $2.
edl_add_catalog() {
  local line
  EDL_CATALOG_CONTENT="${EDL_CATALOG_CONTENT}  $1: |"$'\n'
  while IFS= read -r line; do
    EDL_CATALOG_CONTENT="${EDL_CATALOG_CONTENT}    ${line}"$'\n'
  done < "$2"
}

# Collects the catalogs of the product $1 into EDL_CATALOG_CONTENT. Usage:
#   edl_collect_catalogs <product> <catalogs-dir> <workload-root> [<infrastructure-db>...]
# In order, it adds:
# - each <infrastructure-db> whose <catalogs-dir>/<db>.properties exists and names its endpoint
#   (a `<db>.host=` line with a value); an infrastructure database is not a kit;
# - the kits in the comma-separated RUNNING_KITS that have a <catalogs-dir>/<kit>.properties;
# - each sibling directory of <workload-root> that has a <product>-catalog.properties (a custom
#   --from workload).
edl_collect_catalogs() {
  local product="$1" catalogs_dir="$2" workload_root="$3" props workload workloads
  shift 3
  EDL_CATALOG_CONTENT=""
  while [ "$#" -ge 1 ]; do
    props="${catalogs_dir}/$1.properties"
    if [ -f "${props}" ] && grep -qE "$1.host=.+" "${props}"; then
      echo "Adding catalog for infrastructure db: $1"
      edl_add_catalog "$1" "${props}"
    fi
    shift
  done
  if [ -n "${RUNNING_KITS:-}" ]; then
    IFS=',' read -ra workloads <<< "${RUNNING_KITS}"
    for workload in "${workloads[@]}"; do
      props="${catalogs_dir}/${workload}.properties"
      if [ -f "${props}" ]; then
        echo "Adding catalog for running workload: ${workload}"
        edl_add_catalog "${workload}" "${props}"
      fi
    done
  fi
  for props in "${workload_root}"/*/"${product}-catalog.properties"; do
    [ -f "${props}" ] || continue
    workload="$(basename "$(dirname "${props}")")"
    echo "Adding catalog from custom workload: ${workload}"
    edl_add_catalog "${workload}" "${props}"
  done
}

# Installs or upgrades the helm release $1 of chart $2 at version $3, with the values file $4 and,
# when edl_collect_catalogs found any catalog, a second values file <dir of $4>/.catalogs-override.yaml
# that sets the chart value $5 to them. It does not wait for the rollout.
edl_helm_upgrade_with_catalogs() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  local release="$1" chart="$2" version="$3" values="$4" key="$5"
  local override extra=()
  override="$(dirname "${values}")/.catalogs-override.yaml"
  if [ -n "${EDL_CATALOG_CONTENT}" ]; then
    printf '%s:\n%s' "${key}" "${EDL_CATALOG_CONTENT}" > "${override}"
    extra=(--values "${override}")
  fi
  helm upgrade --install "${release}" "${chart}" \
    --version "${version}" \
    --namespace "${NAMESPACE}" \
    --create-namespace \
    --values "${values}" \
    ${extra[@]+"${extra[@]}"}
}

# Prints the cluster label (<name>-<id>) from the cluster-config ConfigMap, which every other
# signal carries, so the profiling dashboards' cluster filter finds a kit's profiles. CLUSTER_NAME
# is the bare name. Fails when `grafana update-config` has not run.
edl_cluster_label() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  local label
  label="$(kubectl get configmap cluster-config -n "${NAMESPACE}" -o jsonpath='{.data.cluster_name}')"
  [ -n "${label}" ] || edl_fail "ERROR: cluster-config has no cluster_name; run 'easy-db-lab grafana update-config' first."
  printf '%s\n' "${label}"
}

# Prints the JAVA_TOOL_OPTIONS that load the Pyroscope agent for application $1. Needs
# PYROSCOPE_URL and TENANT, which the kit runner sets. The agent is the jar edl_patch_pyroscope's
# init container copies to /edl/agents. The format and profiler options are
# Constants.PyroscopeJavaAgent.PROFILER_FLAGS. The last two options make the agent retry without
# limit and hold an hour of snapshots through a Pyroscope outage; they are the values of
# Constants.PyroscopeJavaAgent.OUTAGE_FLAGS.
edl_pyroscope_opts() {
  printf '%s' "-javaagent:/edl/agents/pyroscope.jar -Dpyroscope.application.name=$1 -Dpyroscope.server.address=${PYROSCOPE_URL} -Dpyroscope.tenant.id=${TENANT} -Dpyroscope.format=jfr -Dpyroscope.profiler.event=cpu -Dpyroscope.profiler.alloc=512k -Dpyroscope.profiler.lock=10ms -Dpyroscope.ingest.max.tries=-1 -Dpyroscope.push.queue.capacity=360"
}

# Patches the container $2 of deployment $1 to load the Pyroscope agent for application $3, with
# the profile labels cluster=$4 and component=$5. The agent comes from an init
# container that copies it from PYROSCOPE_AGENT_IMAGE (set by the kit runner) into an emptyDir the
# JVM container mounts read-only, never from a host path, so the pods run on any node. The pod
# declares no hostPort: clients reach a coordinator through its kit's NodePort Service.
edl_patch_pyroscope() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  local deployment="$1" container="$2" app="$3" cluster_label="$4" component="$5" opts patch
  local image="${PYROSCOPE_AGENT_IMAGE:?run this through easy-db-lab, which sets PYROSCOPE_AGENT_IMAGE}"
  [ "$#" -eq 5 ] || edl_fail "edl_patch_pyroscope: expected 5 arguments, got $#"
  opts="$(edl_pyroscope_opts "${app}") -Dpyroscope.labels=cluster=${cluster_label},component=${component}"
  opts="${opts//\\/\\\\}"
  opts="${opts//\"/\\\"}"
  patch="{\"spec\":{\"template\":{\"spec\":{\"volumes\":[{\"name\":\"java-agents\",\"emptyDir\":{}}],\"initContainers\":[{\"name\":\"pyroscope-agent\",\"image\":\"${image}\",\"imagePullPolicy\":\"IfNotPresent\",\"command\":[\"cp\",\"/pyroscope.jar\",\"/agents/pyroscope.jar\"],\"securityContext\":{\"runAsNonRoot\":true,\"runAsUser\":65532,\"allowPrivilegeEscalation\":false,\"capabilities\":{\"drop\":[\"ALL\"]},\"seccompProfile\":{\"type\":\"RuntimeDefault\"}},\"volumeMounts\":[{\"name\":\"java-agents\",\"mountPath\":\"/agents\"}]}],\"containers\":[{\"name\":\"${container}\",\"env\":[{\"name\":\"JAVA_TOOL_OPTIONS\",\"value\":\"${opts}\"}],\"volumeMounts\":[{\"name\":\"java-agents\",\"mountPath\":\"/edl/agents\",\"readOnly\":true}]}]}}}}"
  kubectl patch deployment "${deployment}" \
    --namespace "${NAMESPACE}" \
    --type=strategic \
    -p="${patch}"
}

# Scales the deployments $@ to zero replicas.
edl_scale_to_zero() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  kubectl scale deployment "$@" --replicas=0 --namespace "${NAMESPACE}"
}

# Uninstalls the helm release $1; a release that is already gone is not an error.
edl_helm_uninstall() {
  : "${NAMESPACE:?NAMESPACE is not set}"
  helm uninstall "$1" --namespace "${NAMESPACE}" --ignore-not-found
}
