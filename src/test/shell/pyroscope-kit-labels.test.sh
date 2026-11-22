#!/usr/bin/env bash
#
# Unit tests for the Pyroscope agent the presto and trino kits' bin/start.sh.template attach: the
# labels on their profiles, and that the jar comes from an init container, never a host path. Also
# that the coordinator is published on the kit's NodePort Service, never a hostPort, and that no
# script deletes the coordinator pod.
#
# Every profile must carry cluster=<name>-<id>, the value every other signal carries, or the
# profiling dashboards' cluster filter hides it. The kit's CLUSTER_NAME is the bare name, so the
# script reads cluster_name from the cluster-config ConfigMap instead.
#
# kubectl is stubbed: it answers the ConfigMap read and records every patch. update-catalogs.sh and
# helm are stubbed too. No cluster, no network.
#
# Run directly:  bash src/test/shell/pyroscope-kit-labels.test.sh
# Or via gradle: ./gradlew testPyroscopeKitLabels

set -uo pipefail

# The cluster namespace, as easy-db-lab sets it for every kit process (TemplateVariables).
export NAMESPACE=lab-a

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KITS="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits"
KIT_LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
LABEL="lab1-0f3c2a1e-1111-2222-3333-444455556666"
AGENT_IMAGE="ghcr.io/rustyrazorblade/pyroscope-java-agent:2.3.0"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

# setup <kit> <configmap cluster_name>
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/kit/bin" "${WORK}/patches"
  cp "${KITS}/$1/bin/start.sh.template" "${WORK}/kit/bin/start.sh"
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/kit/bin/update-catalogs.sh"
  cat > "${WORK}/stubs/helm" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "${WORK}/helm.calls"
exit 0
EOF
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "${WORK}/kubectl.calls"
case " \$* " in
  *" apply -f "*) cat >> "${WORK}/applied.yaml" ;;
  *" get configmap cluster-config "*) printf '%s' "$2" ;;
  *" get deployment "*) printf '[]' ;;
  *" patch deployment "*)
    for arg in "\$@"; do
      case "\$arg" in -p=*) printf '%s\n' "\${arg#-p=}" >> "${WORK}/patches/all.json" ;; esac
    done ;;
esac
exit 0
EOF
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/helm" "${WORK}/kit/bin/update-catalogs.sh"
}

run_script() {
  (
    cd "${WORK}" &&
      PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${KIT_LIB}" PYROSCOPE_AGENT_IMAGE="${AGENT_IMAGE}" CLUSTER_NAME=lab1 PYROSCOPE_URL=http://10.0.0.5:4040 TENANT=default \
        bash "${WORK}/kit/bin/start.sh"
  ) > "${WORK}/out.txt" 2>&1
}

# Every -Dpyroscope.labels value in the patches, one per line.
labels() {
  jq -r '.. | objects | select(.name? == "JAVA_TOOL_OPTIONS") | .value' "${WORK}/patches/all.json" \
    | grep -o -- '-Dpyroscope.labels=[^ ]*' | sed 's/^-Dpyroscope.labels=//'
}

for kit in presto trino; do
  tests_run=$((tests_run + 1))
  setup "${kit}" "${LABEL}"
  run_script
  got="$(labels)"
  want="cluster=${LABEL},component=coordinator
cluster=${LABEL},component=worker"
  if [ "${got}" != "${want}" ]; then
    fail "${kit}: labels are not the cluster label from cluster-config: '${got}'"
    cat "${WORK}/out.txt"
  fi

  tests_run=$((tests_run + 1))
  setup "${kit}" ""
  if run_script; then
    fail "${kit}: start succeeded without a cluster_name in cluster-config"
  elif [ -s "${WORK}/patches/all.json" ]; then
    fail "${kit}: patched a deployment without a cluster label"
  fi
done

# The agent reaches each JVM from an init container into an emptyDir, never a host path, and no
# patch declares a hostPort: the agent path in JAVA_TOOL_OPTIONS is a file an init container of the
# same patch copies into the volume the JVM container mounts read-only.
for kit in presto trino; do
  tests_run=$((tests_run + 1))
  setup "${kit}" "${LABEL}"
  run_script
  if grep -q -e hostPath -e /usr/local/pyroscope "${WORK}/patches/all.json"; then
    fail "${kit}: a patch carries a host dependency: $(cat "${WORK}/patches/all.json")"
  fi
  got=$(jq -r --arg img "${AGENT_IMAGE}" '
    .spec.template.spec as $s
    | ($s.containers[0]) as $c
    | ([$c.env[]? | select(.name == "JAVA_TOOL_OPTIONS") | .value | capture("-javaagent:(?<p>[^ ]+)").p][0] // "") as $path
    | ([$c.volumeMounts[]? | select(.readOnly == true) | . as $m | select($path | startswith($m.mountPath + "/"))][0]) as $m
    | ([$s.volumes[]? | select(.name == $m.name and .emptyDir != null)][0]) as $v
    | ([$s.initContainers[]? | select(.image == $img) | . as $i | $i.volumeMounts[]?
        | select(.name == $v.name and $i.command[-1] == .mountPath + ($path | ltrimstr($m.mountPath)))] | length) as $n
    | if ($path | startswith("/edl/agents/")) and $n == 1 then "ok" else "bad \($c.name)" end
  ' "${WORK}/patches/all.json" 2>&1)
  if [ "${got}" != "ok
ok" ]; then
    fail "${kit}: agent not delivered by an init container into a read-only emptyDir: ${got}"
    cat "${WORK}/patches/all.json"
  fi
done

# The coordinator is reached through a NodePort Service, never a hostPort: no patch declares a
# hostPort, start applies the kit's Service, and nothing deletes the coordinator pod to free a
# host port, neither start nor update-catalogs.sh (which every catalog change runs).
for kit in presto trino; do
  tests_run=$((tests_run + 1))
  setup "${kit}" "${LABEL}"
  cp "${KITS}/${kit}/nodeport-service.yaml.template" "${WORK}/kit/nodeport-service.yaml" 2>/dev/null
  run_script || fail "${kit}: start failed: $(cat "${WORK}/out.txt")"
  if grep -q hostPort "${WORK}/patches/all.json"; then
    fail "${kit}: a patch declares a hostPort: $(cat "${WORK}/patches/all.json")"
  fi
  grep -q "kind: Service" "${WORK}/applied.yaml" 2>/dev/null \
    || fail "${kit}: start did not apply the NodePort Service: $(cat "${WORK}/kubectl.calls")"
  if grep -q "^delete pod" "${WORK}/kubectl.calls"; then
    fail "${kit}: start deleted the coordinator pod: $(grep "^delete pod" "${WORK}/kubectl.calls")"
  fi

  tests_run=$((tests_run + 1))
  setup "${kit}" "${LABEL}"
  cp "${KITS}/${kit}/bin/update-catalogs.sh.template" "${WORK}/kit/bin/update-catalogs.sh"
  (cd "${WORK}" && PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${KIT_LIB}" bash "${WORK}/kit/bin/update-catalogs.sh") > "${WORK}/out.txt" 2>&1 \
    || fail "${kit}: update-catalogs failed: $(cat "${WORK}/out.txt")"
  if grep -q "^delete pod" "${WORK}/kubectl.calls" 2>/dev/null; then
    fail "${kit}: update-catalogs deleted the coordinator pod: $(grep "^delete pod" "${WORK}/kubectl.calls")"
  fi
  # The coordinator reads its catalogs only at startup, so the change must restart it, and the
  # script must wait for the new coordinator: the shared helm helper does not wait for a rollout.
  grep -qx "rollout restart deployment/${kit}-coordinator --namespace lab-a" "${WORK}/kubectl.calls" 2>/dev/null \
    || fail "${kit}: update-catalogs did not restart the coordinator: $(cat "${WORK}/kubectl.calls" 2>/dev/null)"
  grep -q "^rollout status deployment/${kit}-coordinator --namespace lab-a" "${WORK}/kubectl.calls" 2>/dev/null \
    || fail "${kit}: update-catalogs did not wait for the coordinator: $(cat "${WORK}/kubectl.calls" 2>/dev/null)"

  tests_run=$((tests_run + 1))
  setup "${kit}" "${LABEL}"
  cp "${KITS}/${kit}/bin/stop.sh.template" "${WORK}/kit/bin/stop.sh"
  (cd "${WORK}" && PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${KIT_LIB}" bash "${WORK}/kit/bin/stop.sh") > "${WORK}/out.txt" 2>&1 \
    || fail "${kit}: stop failed: $(cat "${WORK}/out.txt")"
  grep -q "^delete service ${kit}-nodeport" "${WORK}/kubectl.calls" 2>/dev/null \
    || fail "${kit}: stop did not delete the NodePort Service: $(cat "${WORK}/kubectl.calls" 2>/dev/null)"

  # start applies the Service with kubectl, outside the helm release, so uninstall must delete it
  # too (uninstall can run without a stop first), and must not fail when it is already gone.
  tests_run=$((tests_run + 1))
  setup "${kit}" "${LABEL}"
  cp "${KITS}/${kit}/bin/uninstall.sh.template" "${WORK}/kit/bin/uninstall.sh"
  (cd "${WORK}" && PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${KIT_LIB}" bash "${WORK}/kit/bin/uninstall.sh") > "${WORK}/out.txt" 2>&1 \
    || fail "${kit}: uninstall failed: $(cat "${WORK}/out.txt")"
  grep -qx "delete service ${kit}-nodeport --namespace lab-a --ignore-not-found" "${WORK}/kubectl.calls" 2>/dev/null \
    || fail "${kit}: uninstall did not delete the NodePort Service idempotently: $(cat "${WORK}/kubectl.calls" 2>/dev/null)"
done

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
