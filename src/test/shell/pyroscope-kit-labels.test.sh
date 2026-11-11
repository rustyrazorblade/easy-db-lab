#!/usr/bin/env bash
#
# Unit tests for the Pyroscope labels the presto and trino kits' bin/start.sh.template put on their
# profiles.
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

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KITS="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits"
LABEL="lab1-0f3c2a1e-1111-2222-3333-444455556666"

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
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/helm"
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
case " \$* " in
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
      PATH="${WORK}/stubs:${PATH}" CLUSTER_NAME=lab1 PYROSCOPE_URL=http://10.0.0.5:4040 TENANT=default \
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

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
