#!/usr/bin/env bash
#
# Unit tests for the presto and trino kits' update-catalogs, start, stop and uninstall scripts,
# which share their steps through edl-kit-lib.sh.
#
# Each case copies a kit's scripts and the shared library into a scratch workspace, runs them with
# helm and kubectl stubbed, and checks what they ran: the helm release, chart and pinned version,
# the catalogs handed to the chart, the coordinator pod replacement that keeps a catalog update from
# deadlocking on a single app node, and the hostPort patch. No cluster, no network.
#
# Run directly:  bash src/test/shell/presto-trino-scripts.test.sh
# Or via gradle: ./gradlew testPrestoTrinoScripts

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KITS="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
LABEL="lab1-0f3c2a1e-1111-2222-3333-444455556666"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# setup <kit>: a workspace holding the kit's scripts (with the placeholders filled in), a catalogs
# directory with a postgres catalog and an opensearch catalog that has an endpoint, and the stubs.
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/kit/bin" "${WORK}/kit/catalogs"
  local script
  for script in start stop uninstall update-catalogs; do
    sed -e 's#__WORKERS__#3#' "${KITS}/$1/bin/${script}.sh.template" > "${WORK}/kit/bin/${script}.sh"
    chmod +x "${WORK}/kit/bin/${script}.sh"
  done
  : > "${WORK}/kit/values.yaml"
  printf 'connector.name=postgresql\nconnection-url=jdbc:postgresql://db:5432/\n' > "${WORK}/kit/catalogs/postgres.properties"
  printf 'connector.name=opensearch\nopensearch.host=search.example.com\n' > "${WORK}/kit/catalogs/opensearch.properties"
  cat > "${WORK}/stubs/helm" <<EOF
#!/usr/bin/env bash
printf 'helm %s\n' "\$*" >> "${WORK}/calls"
exit 0
EOF
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
printf 'kubectl %s\n' "\$*" >> "${WORK}/calls"
case " \$* " in
  *" get configmap cluster-config "*) printf '%s' "${LABEL}" ;;
  *" patch deployment "*)
    for arg in "\$@"; do
      case "\$arg" in -p=*) printf '%s\n' "\${arg#-p=}" >> "${WORK}/patches" ;; esac
    done ;;
esac
exit 0
EOF
  chmod +x "${WORK}/stubs/helm" "${WORK}/stubs/kubectl"
  : > "${WORK}/calls"
  : > "${WORK}/patches"
}

# run <script> [VAR=value...]: runs a kit script with the stubs on PATH, under "$BASH".
run() {
  local script="$1"
  shift
  (
    cd "${WORK}" &&
      env "$@" PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" KUBECONFIG="${WORK}/kubeconfig" CLUSTER_NAME=lab1 \
        PYROSCOPE_URL=http://10.0.0.5:4040 TENANT=default \
        "${BASH}" "${WORK}/kit/bin/${script}.sh"
  ) > "${WORK}/out" 2>&1
  rc=$?
}

calls() { cat "${WORK}/calls"; }

# The line number of the first call matching the fixed string $1, or empty.
first_call() { grep -nF -- "$1" "${WORK}/calls" | head -n 1 | cut -d: -f1; }

# The catalog names handed to the chart, one per line.
catalog_names() { sed -n 's/^  \([A-Za-z0-9_-]*\): |$/\1/p' "${WORK}/kit/.catalogs-override.yaml" 2> /dev/null; }

check_update_catalogs() {
  # The pinned version is read from the kit script, so a bump is made in one place (the Kotlin
  # HelmChartVersionPinTest holds the verified value); this checks that helm is run with it.
  local kit="$1" release="$1" chart="$2" key="$3" version
  version="$(sed -n "s/^[A-Z]*_CHART_VERSION=\"\\(.*\\)\"\$/\\1/p" "${KITS}/${kit}/bin/update-catalogs.sh.template")"
  [ -n "${version}" ] || { fail "no pinned chart version in the ${kit} update-catalogs script"; return; }
  current_test="${kit} update-catalogs"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  run update-catalogs RUNNING_KITS=postgres
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: $(cat "${WORK}/out")"; return; }

  grep -qF "helm upgrade --install ${release} ${chart} --version ${version} --namespace default --create-namespace --values ${WORK}/kit/bin/../values.yaml" "${WORK}/calls" ||
    fail "helm was not run for ${chart} at ${version}: $(calls)"
  grep -qF -- "--values ${WORK}/kit/bin/../.catalogs-override.yaml" "${WORK}/calls" || fail "the catalogs were not passed to helm: $(calls)"
  grep -q '^kind:' "${WORK}/kit/.catalogs-override.yaml" 2> /dev/null && fail "override file is not a values file"
  head -n 1 "${WORK}/kit/.catalogs-override.yaml" | grep -qx "${key}:" || fail "the catalogs are not under ${key}: $(head -n 1 "${WORK}/kit/.catalogs-override.yaml")"
  grep -qF -- ' --wait' "${WORK}/calls" && fail "helm waits for a rollout that deadlocks on a hostPort: $(calls)"

  local helm_at delete_at rollout_at
  helm_at="$(first_call 'helm upgrade')"
  delete_at="$(first_call "kubectl delete pod -n default -l app.kubernetes.io/name=${kit},app.kubernetes.io/component=coordinator --ignore-not-found")"
  rollout_at="$(first_call "kubectl rollout status deployment/${kit}-coordinator --namespace default --timeout=180s")"
  [ -n "${helm_at}" ] && [ -n "${delete_at}" ] && [ -n "${rollout_at}" ] ||
    { fail "expected helm, then the coordinator pod replaced, then its rollout awaited: $(calls)"; return; }
  [ "${helm_at}" -lt "${delete_at}" ] && [ "${delete_at}" -lt "${rollout_at}" ] || fail "steps out of order: $(calls)"
}

check_catalog_sources() {
  local kit="$1"
  current_test="${kit} catalogs come from running kits and custom workloads"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  mkdir -p "${WORK}/custom"
  printf 'connector.name=custom\n' > "${WORK}/custom/${kit}-catalog.properties"
  printf 'connector.name=ignored\n' > "${WORK}/custom/other-catalog.properties"
  run update-catalogs RUNNING_KITS=postgres,absent
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: $(cat "${WORK}/out")"; return; }
  catalog_names | grep -qx postgres || fail "a running kit's catalog was not added: $(catalog_names)"
  catalog_names | grep -qx custom || fail "a custom workload's catalog was not added: $(catalog_names)"
  catalog_names | grep -qx absent && fail "a running kit with no catalog file was added"
  catalog_names | grep -qx other && fail "another product's custom catalog was added"
}

check_no_catalogs() {
  local kit="$1"
  current_test="${kit} with no catalogs installs the chart with its values only"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  rm "${WORK}/kit/catalogs/opensearch.properties"
  run update-catalogs
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: $(cat "${WORK}/out")"; return; }
  [ -e "${WORK}/kit/.catalogs-override.yaml" ] && fail "wrote an override file with no catalogs"
  grep -qF -- '.catalogs-override.yaml' "${WORK}/calls" && fail "passed an override file with no catalogs"
  [ -n "$(first_call "deployment/${kit}-coordinator")" ] || fail "did not replace the coordinator: $(calls)"
}

check_opensearch() {
  local kit="$1" expected="$2"
  current_test="${kit} opensearch infrastructure catalog (${expected})"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  run update-catalogs
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: $(cat "${WORK}/out")"; return; }
  if [ "${expected}" = added ]; then
    catalog_names | grep -qx opensearch || fail "opensearch catalog missing: $(catalog_names)"
    # an endpoint-less catalog is skipped
    setup "${kit}"
    printf 'connector.name=opensearch\nopensearch.host=\n' > "${WORK}/kit/catalogs/opensearch.properties"
    run update-catalogs
    catalog_names | grep -qx opensearch && fail "added an opensearch catalog that has no endpoint"
  else
    catalog_names | grep -qx opensearch && fail "added an opensearch catalog"
  fi
}

check_start() {
  local kit="$1" ports="$2"
  current_test="${kit} start patches hostPorts [${ports}] and the Pyroscope agent"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  cat > "${WORK}/kit/bin/update-catalogs.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "${WORK}/kit/bin/update-catalogs.sh"
  run start
  [ "${rc}" -eq 0 ] || { fail "exited ${rc}: $(cat "${WORK}/out")"; return; }
  local coordinator worker
  coordinator="$(sed -n 1p "${WORK}/patches")"
  worker="$(sed -n 2p "${WORK}/patches")"
  [ "$(jq -c '[.spec.template.spec.containers[0].ports[]? | select(.hostPort) | .hostPort]' <<< "${coordinator}")" = "${ports}" ] ||
    fail "coordinator hostPorts: $(jq -c '.spec.template.spec.containers[0].ports' <<< "${coordinator}")"
  [ "$(jq -r '.spec.template.spec.containers[0].ports // "none"' <<< "${worker}")" = none ] || fail "the worker patch carries ports"
  jq -r '.spec.template.spec.containers[0].env[0].value' <<< "${coordinator}" | grep -q "pyroscope.application.name=${kit} .*component=coordinator" ||
    fail "coordinator patch has no Pyroscope agent for ${kit}"
  # The profile cluster label is cluster-config's cluster_name (<name>-<id>), never the bare
  # CLUSTER_NAME the kit runner exports (issue 982).
  local component patch
  for component in coordinator worker; do
    if [ "${component}" = coordinator ]; then patch="${coordinator}"; else patch="${worker}"; fi
    jq -r '.spec.template.spec.containers[0].env[0].value' <<< "${patch}" | grep -q "pyroscope.labels=cluster=${LABEL},component=${component}" ||
      fail "${component} patch has no cluster=${LABEL} label: $(jq -r '.spec.template.spec.containers[0].env[0].value' <<< "${patch}")"
  done
  # Trino start replaces its coordinator pod after the patches (the new hostPort cannot be taken up
  # by a rolling update); Presto start leaves that to its rollout wait, so only update-catalogs deletes.
  if [ "${kit}" = trino ]; then
    grep -qF "kubectl delete pod -n default -l app.kubernetes.io/name=trino,app.kubernetes.io/component=coordinator" "${WORK}/calls" ||
      fail "trino start does not replace its coordinator pod"
  else
    grep -qF "kubectl delete" "${WORK}/calls" && fail "${kit} start deletes a pod: $(calls)"
  fi
  grep -qF "kubectl rollout status deployment/${kit}-worker" "${WORK}/calls" || fail "start does not await the worker rollout: $(calls)"
}

check_start_without_label() {
  local kit="$1"
  current_test="${kit} start fails without a cluster_name in cluster-config"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  sed -i.bak "s#printf '%s' \"${LABEL}\"#printf ''#" "${WORK}/stubs/kubectl"
  run start
  [ "${rc}" -ne 0 ] || fail "start succeeded"
  [ -s "${WORK}/patches" ] && fail "patched a deployment without a cluster label"
  grep -q "cluster-config has no cluster_name" "${WORK}/out" || fail "no explanation: $(cat "${WORK}/out")"
}

check_stop_and_uninstall() {
  local kit="$1"
  current_test="${kit} stop and uninstall"
  tests_run=$((tests_run + 1))
  setup "${kit}"
  run stop
  [ "${rc}" -eq 0 ] || { fail "stop exited ${rc}: $(cat "${WORK}/out")"; return; }
  grep -qx "kubectl scale deployment ${kit}-coordinator ${kit}-worker --replicas=0 --namespace default" "${WORK}/calls" ||
    fail "stop did not scale ${kit} to zero: $(calls)"
  run uninstall
  [ "${rc}" -eq 0 ] || { fail "uninstall exited ${rc}: $(cat "${WORK}/out")"; return; }
  grep -qx "helm uninstall ${kit} --namespace default --ignore-not-found" "${WORK}/calls" || fail "uninstall did not remove the release: $(calls)"
}

# A script run outside easy-db-lab has no KUBECONFIG; it must stop before any cluster call
# instead of falling back to a kubeconfig of its own choosing.
check_requires_kubeconfig() {
  local kit="$1" script
  for script in start stop uninstall update-catalogs; do
    current_test="${kit} ${script} refuses to run without KUBECONFIG"
    tests_run=$((tests_run + 1))
    setup "${kit}"
    (
      cd "${WORK}" &&
        env -u KUBECONFIG PATH="${WORK}/stubs:${PATH}" EDL_KIT_LIB="${LIB}" CLUSTER_NAME=lab1 \
          PYROSCOPE_URL=http://10.0.0.5:4040 TENANT=default \
          "${BASH}" "${WORK}/kit/bin/${script}.sh"
    ) > "${WORK}/out" 2>&1
    rc=$?
    [ "${rc}" -ne 0 ] || fail "${script} succeeded without KUBECONFIG"
    grep -qF "KUBECONFIG: run this through easy-db-lab, which sets KUBECONFIG" "${WORK}/out" ||
      fail "${script} did not explain the missing KUBECONFIG: $(cat "${WORK}/out")"
    [ -s "${WORK}/calls" ] && fail "${script} called a cluster tool without KUBECONFIG: $(calls)"
  done
}

check_update_catalogs presto prestodb/presto catalog
check_update_catalogs trino trinodb/trino additionalCatalogs
for kit in presto trino; do
  check_catalog_sources "${kit}"
  check_no_catalogs "${kit}"
  check_start_without_label "${kit}"
  check_stop_and_uninstall "${kit}"
  check_requires_kubeconfig "${kit}"
done
check_opensearch trino added
check_opensearch presto "not added"
check_start presto '[8080,9090]'
check_start trino '[8080]'

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
