# shellcheck shell=bash
#
# Stub kubectl, curl, date and sleep for the kit shell tests, sourced by the *.test.sh files that
# run edl-kit-lib.sh or a kit script that sources it. No cluster, no network.
#
# write_kit_stubs <stub-dir> writes the stubs once. They read and record everything under
# ${STUB_STATE}, which reset_kit_stub_state empties for each test:
#
#   app-nodes         what `kubectl get nodes -l type=app -o name` prints (absent: no app node)
#   pods              "<name> <phase>" lines that `kubectl get pods -l ... --field-selector` filters
#   state.<key>       the states `kubectl get pod <name>` reports, one line per call, the last one
#                     repeating, for the first pod whose name holds <key> (state.default otherwise):
#                       <phase>|<waiting reason>|<waiting message>|<exit code>, NOTFOUND or ERROR
#   log.<n>           what the n-th `kubectl logs -f` prints; logs-exit.<n> its exit status
#   full-log          what `kubectl logs <pod>` (no -f) prints
#   run-exit          the exit status of `kubectl run` (default 0)
#
# and records:
#
#   kubectl.calls     every kubectl call, one per line
#   overrides.<n>     the --overrides JSON of the n-th `kubectl run`
#   configmap.<n>     the YAML the n-th `kubectl create -f -` received
#   curl.<n>          the arguments of the n-th curl call, one per line
#   since-times       the --since-time of each resumed `kubectl logs -f`

write_kit_stubs() {
  local dir="$1"
  mkdir -p "${dir}"
  cat > "${dir}/kubectl" <<'EOF'
#!/usr/bin/env bash
S="${STUB_STATE:?}"
printf '%s\n' "$*" >> "${S}/kubectl.calls"
next() { local n; n=$(( $(cat "${S}/$1.count" 2>/dev/null || echo 0) + 1 )); echo "${n}" > "${S}/$1.count"; echo "${n}"; }
pod_state() {
  local pod="$1" key file n line
  file="${S}/state.default"
  for candidate in "${S}"/state.*; do
    [ -e "${candidate}" ] || continue
    key="${candidate##*/state.}"
    [ "${key}" = default ] && continue
    case "${key}" in *.count) continue ;; esac
    case "${pod}" in *"${key}"*) file="${candidate}"; break ;; esac
  done
  [ -f "${file}" ] || { echo "Running|||"; return; }
  n=$(next "${file##*/}")
  line="$(sed -n "${n}p" "${file}")"
  [ -n "${line}" ] || line="$(tail -n 1 "${file}")"
  echo "${line}"
}
case "$1" in
  get)
    case "$2" in
      nodes) cat "${S}/app-nodes" 2>/dev/null; exit 0 ;;
      pods)
        filter=""
        for a in "$@"; do
          case "${a}" in --field-selector=status.phase!=Succeeded,status.phase!=Failed) filter=yes ;; esac
        done
        [ -f "${S}/pods" ] || exit 0
        while read -r name phase; do
          if [ -n "${filter}" ]; then
            case "${phase}" in Succeeded|Failed) continue ;; esac
          fi
          echo "${name}"
        done < "${S}/pods"
        exit 0
        ;;
      pod)
        pod="$3"
        jsonpath=""
        for a in "$@"; do case "${a}" in jsonpath=*) jsonpath="${a}" ;; esac; done
        state="$(pod_state "${pod}")"
        case "${state}" in
          NOTFOUND) exit 0 ;;
          ERROR) echo "error: the server is unavailable" >&2; exit 1 ;;
        esac
        IFS='|' read -r phase reason message code <<< "${state}"
        case "${jsonpath}" in
          *terminated.exitCode*) printf '%s|%s|%s' "${pod}" "${phase}" "${code}" ;;
          *waiting.reason*) printf '%s|%s|%s|%s' "${pod}" "${phase}" "${reason}" "${message}" ;;
          *) printf '%s|%s' "${pod}" "${phase}" ;;
        esac
        exit 0
        ;;
    esac
    ;;
  logs)
    case " $* " in
      *" -f "*)
        n=$(next logs)
        for a in "$@"; do case "${a}" in --since-time=*) echo "${a#--since-time=}" >> "${S}/since-times" ;; esac; done
        cat "${S}/log.${n}" 2>/dev/null
        exit "$(cat "${S}/logs-exit.${n}" 2>/dev/null || echo 0)"
        ;;
      *) cat "${S}/full-log" 2>/dev/null; exit 0 ;;
    esac
    ;;
  describe) echo "Events: stub describe events for $3"; exit 0 ;;
  run)
    n=$(next run)
    for a in "$@"; do case "${a}" in --overrides=*) printf '%s\n' "${a#--overrides=}" > "${S}/overrides.${n}" ;; esac; done
    exit "$(cat "${S}/run-exit" 2>/dev/null || echo 0)"
    ;;
  create)
    case "$2" in
      configmap)
        echo "kind: ConfigMap"
        echo "metadata:"
        echo "  name: $3"
        for a in "$@"; do case "${a}" in --from-file=*) echo "# from-file ${a#--from-file=}" ;; esac; done
        exit 0
        ;;
      -f)
        n=$(next configmap)
        cat > "${S}/configmap.${n}"
        exit 0
        ;;
    esac
    ;;
  label)
    cat
    for a in "$@"; do case "${a}" in easydblab/*) echo "# label ${a}" ;; esac; done
    exit 0
    ;;
esac
exit 0
EOF
  cat > "${dir}/curl" <<'EOF'
#!/usr/bin/env bash
S="${STUB_STATE:?}"
n=$(( $(cat "${S}/curl.count" 2>/dev/null || echo 0) + 1 ))
echo "${n}" > "${S}/curl.count"
printf '%s\n' "$@" > "${S}/curl.${n}"
[ -f "${S}/curl-out" ] && cat "${S}/curl-out"
exit "$(cat "${S}/curl-exit" 2>/dev/null || echo 0)"
EOF
  cat > "${dir}/date" <<'EOF'
#!/usr/bin/env bash
S="${STUB_STATE:?}"
if [ "$*" = "+%s" ]; then
  n=$(cat "${S}/date.count" 2>/dev/null || echo 0)
  echo $((n + 1)) > "${S}/date.count"
  echo $((1700000000 + n))
  exit 0
fi
exec /bin/date "$@"
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "${dir}/sleep"
  chmod +x "${dir}/kubectl" "${dir}/curl" "${dir}/date" "${dir}/sleep"
}

# Empties ${STUB_STATE} for the next test.
reset_kit_stub_state() {
  rm -rf "${STUB_STATE:?}"
  mkdir -p "${STUB_STATE}"
}

# Prints every gauge in every pushed OTLP body as "<name> <kit> <value>", one per line.
pushed_gauges() {
  local call
  for call in "${STUB_STATE}"/curl.[0-9]*; do
    [ -e "${call}" ] || continue
    grep -q '/v1/metrics$' "${call}" || continue
    awk 'prev == "--data-binary" { print; exit } { prev = $0 }' "${call}" | jq -r '
      .resourceMetrics[].scopeMetrics[].metrics[]
      | .name as $name
      | .gauge.dataPoints[]
      | "\($name) \(.attributes[] | select(.key == "kit") | .value.stringValue) \(.asDouble * 1)"'
  done
}
