#!/usr/bin/env bash
#
# Unit tests for the cassandra-pods kit's bin/upgrade.sh and lib/apply-config.sh. kubectl is
# stubbed and the lib scripts it runs are the kit's own. No cluster, no network.
#
# Run directly:  bash src/test/shell/cassandra-pods-upgrade.test.sh
# Or via gradle: ./gradlew testCassandraPodsUpgrade

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cassandra-pods"
tests_run=0
tests_failed=0
current_test=""
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/stubs" "${WORK}/kit/bin" "${WORK}/kit/config" "${WORK}/workspace"
cp -R "${KIT}/lib" "${WORK}/kit/"
cp "${KIT}/bin/upgrade.sh" "${WORK}/kit/bin/"
for file in fetch-tarball.sh render-config.sh edl-cassandra-config.sh edl-cassandra-agents.sh cassandra.in.sh; do
  echo "# ${file}" > "${WORK}/kit/config/${file}"
done
for file in cassandra.patch.yaml placement.patch.yaml cassandra-rackdc.properties; do
  echo "# ${file}" > "${WORK}/kit/${file}"
done
printf 'build: "@EDL_CASSANDRA_BUILD@"\nresources: {} # @EDL_CASSANDRA_RESOURCES@\n' > "${WORK}/kit/statefulset.yaml"

# kubectl: `get statefulset` prints $STUB_RUNNING (or fails with $STUB_GET_FAILS); every call is
# recorded; `apply` keeps what it was given; `rollout status` exits $STUB_ROLLOUT_RC; `exec` prints
# $STUB_UN lines of UN; create and label pass the manifest through.
cat > "${WORK}/stubs/kubectl" << 'STUB'
#!/bin/bash
echo "$*" >> "${STUB_DIR}/kubectl.calls"
case "$1 $2" in
  "get statefulset")
    [ -z "${STUB_GET_FAILS:-}" ] || { echo "Error from server (NotFound)" >&2; exit 1; }
    case "$*" in
      *readyReplicas*) printf '%s' "${STUB_UN:-3}" ;;
      *) printf '%s' "${STUB_RUNNING:-}" ;;
    esac
    ;;
  "apply -f") cat >> "${STUB_DIR}/applied.yaml" ;;
  "rollout status") exit "${STUB_ROLLOUT_RC:-0}" ;;
  "create configmap") printf 'kind: ConfigMap\n'; for a in "$@"; do case "$a" in --from-file=*) echo "# ${a}" ;; esac; done ;;
  "label --local") cat ;;
  exec*) i=0; while [ "$i" -lt "${STUB_UN:-3}" ]; do echo "UN  10.0.0.$i"; i=$((i + 1)); done ;;
esac
STUB
chmod +x "${WORK}/stubs/kubectl"
export STUB_DIR="${WORK}"

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

begin() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  rm -f "${WORK}/kubectl.calls" "${WORK}/applied.yaml"
}

# Runs bin/upgrade.sh from the workspace, as the kit runner does, with the contributor's variables
# for 5.0.6 on a kit running $STUB_RUNNING (default 5.0.4); sets rc and out.
run_upgrade() {
  (
    cd "${WORK}/workspace" &&
      env PATH="${WORK}/stubs:${PATH}" KIT_NAME=cassandra-pods CASSANDRA_NODES=3 CASSANDRA_IMAGE= \
        CASSANDRA_VERSION=5.0.6 CASSANDRA_BUILD= STUB_RUNNING=5.0.4 EDL_POLL_SECONDS=0 CASSANDRA_UN_WAIT_SECONDS=5 \
        EDL_TARBALL_URL=https://dlcdn/x.tgz EDL_TARBALL_FALLBACK_URL= EDL_TARBALL_SHA256=abc \
        EDL_CASSANDRA_BUILD=5.0.6 EDL_RUNTIME_IMAGE=ghcr.io/r/cassandra-runtime:1-jre17 EDL_CONFIG_HASH=0123 \
        EDL_CASSANDRA_RESOURCES='{"requests":{"cpu":"2"}}' EDL_INIT_RESOURCES='{}' \
        "$@" "${BASH}" "${WORK}/kit/bin/upgrade.sh"
  ) > "${WORK}/out" 2>&1
  rc=$?
  out="$(cat "${WORK}/out")"
}

changed_nothing() {
  [ ! -f "${WORK}/applied.yaml" ] || fail "something was applied: $(cat "${WORK}/applied.yaml")"
}

begin "an upgrade applies the ConfigMap and the StatefulSet, then waits for the rollout and UN"
run_upgrade
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
grep -q 'kind: ConfigMap' "${WORK}/applied.yaml" || fail "ConfigMap not applied: $(cat "${WORK}/applied.yaml" 2> /dev/null)"
grep -qxF 'build: "5.0.6"' "${WORK}/applied.yaml" || fail "StatefulSet not applied with the new build: $(cat "${WORK}/applied.yaml" 2> /dev/null)"
grep -qxF 'resources: {"requests":{"cpu":"2"}}' "${WORK}/applied.yaml" || fail "resources not applied"
order="$(grep -oE '^(apply|rollout status|exec)' "${WORK}/kubectl.calls" | tr '\n' ',')"
[ "${order}" = "apply,apply,rollout status,exec," ] || fail "order: ${order}"
grep -q 'rollout status statefulset/cassandra-pods --namespace default --timeout=5s' "${WORK}/kubectl.calls" || fail "rollout wait: $(cat "${WORK}/kubectl.calls")"
case "${out}" in *"5.0.4"*"5.0.6"*) ;; *) fail "output does not name both versions: ${out}" ;; esac

begin "a major-version downgrade is refused, naming both versions, and changes nothing"
run_upgrade STUB_RUNNING=5.0.6 CASSANDRA_VERSION=4.1 EDL_CASSANDRA_BUILD=4.1.10
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"5.0.6"*"4.1.10"*downgrade*) ;; *) fail "message: ${out}" ;; esac
changed_nothing

begin "a downgrade within the major and an upgrade across it are allowed"
for pair in "5.0.6:5.0.4" "4.1.10:5.0.6" "5.0.6:5.0-HEAD" "5.0.6:trunk" "trunk:5.0.6"; do
  run_upgrade STUB_RUNNING="${pair%%:*}" EDL_CASSANDRA_BUILD="${pair##*:}"
  [ "${rc}" -eq 0 ] || fail "${pair}: exit ${rc}: ${out}"
done

begin "a two-digit major compares as a number"
run_upgrade STUB_RUNNING=10.0 EDL_CASSANDRA_BUILD=5.0.6
[ "${rc}" -ne 0 ] || fail "10.0 to 5.0.6 was allowed"
changed_nothing

begin "--version and --build together are refused before anything is read"
run_upgrade CASSANDRA_BUILD=5.0-HEAD
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"--version 5.0.6"*"--build 5.0-HEAD"*) ;; *) fail "message: ${out}" ;; esac
[ ! -f "${WORK}/kubectl.calls" ] || fail "kubectl was called"

begin "neither --version nor --build is refused"
run_upgrade CASSANDRA_VERSION=
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"--version or --build"*) ;; *) fail "message: ${out}" ;; esac
changed_nothing

begin "a kit started with --image is refused"
run_upgrade CASSANDRA_IMAGE=ghcr.io/x/cassandra:y
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"ghcr.io/x/cassandra:y"*) ;; *) fail "message: ${out}" ;; esac
changed_nothing

begin "a kit that is not running is refused"
run_upgrade STUB_GET_FAILS=1
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"not running"*) ;; *) fail "message: ${out}" ;; esac
changed_nothing

begin "a StatefulSet with no build (an --image StatefulSet) is refused"
run_upgrade STUB_RUNNING=
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"EDL_CASSANDRA_BUILD"*) ;; *) fail "message: ${out}" ;; esac
changed_nothing

begin "a rollout that does not finish fails the upgrade, and does not wait for UN"
run_upgrade STUB_ROLLOUT_RC=1
[ "${rc}" -ne 0 ] || fail "exit 0"
case "${out}" in *"did not finish"*) ;; *) fail "message: ${out}" ;; esac
if grep -q '^exec' "${WORK}/kubectl.calls"; then fail "waited for UN after a failed rollout"; fi

# The kit's recorded args, as kit install wrote them, before each upgrade.
seed_args() {
  printf '# kit install\nCASSANDRA_VERSION=5.0.4\nCASSANDRA_BUILD=5.0-HEAD\nCASSANDRA_JAVA=11\nSTORAGE_SIZE=2Gi\n' > "${WORK}/kit/kit-resolved-args.env"
}
recorded() { grep "^$1=" "${WORK}/kit/kit-resolved-args.env"; }

begin "a rollout that times out after the apply still records what was applied"
seed_args
run_upgrade STUB_ROLLOUT_RC=1 CASSANDRA_JAVA=17
[ "${rc}" -ne 0 ] || fail "exit 0"
[ "$(recorded CASSANDRA_VERSION)" = "CASSANDRA_VERSION=5.0.6" ] || fail "version: $(cat "${WORK}/kit/kit-resolved-args.env")"
[ "$(recorded CASSANDRA_BUILD)" = "CASSANDRA_BUILD=" ] || fail "build not cleared: $(cat "${WORK}/kit/kit-resolved-args.env")"
[ "$(recorded CASSANDRA_JAVA)" = "CASSANDRA_JAVA=17" ] || fail "java: $(cat "${WORK}/kit/kit-resolved-args.env")"
[ "$(recorded STORAGE_SIZE)" = "STORAGE_SIZE=2Gi" ] || fail "other args not kept"
head -1 "${WORK}/kit/kit-resolved-args.env" | grep -qx '# kit install' || fail "comment not kept"
case "${out}" in *"recorded"*"5.0.6"*) ;; *) fail "failure does not say 5.0.6 is recorded: ${out}" ;; esac

begin "an upgrade refused before the apply records nothing"
seed_args
run_upgrade STUB_RUNNING=5.0.6 CASSANDRA_VERSION=4.1 EDL_CASSANDRA_BUILD=4.1.10
[ "$(recorded CASSANDRA_VERSION)" = "CASSANDRA_VERSION=5.0.4" ] || fail "recorded: $(cat "${WORK}/kit/kit-resolved-args.env")"

begin "a recorded arg the file lacks is appended"
printf 'STORAGE_SIZE=2Gi\n' > "${WORK}/kit/kit-resolved-args.env"
run_upgrade
[ "${rc}" -eq 0 ] || fail "exit ${rc}: ${out}"
[ "$(recorded CASSANDRA_VERSION)" = "CASSANDRA_VERSION=5.0.6" ] || fail "not appended: $(cat "${WORK}/kit/kit-resolved-args.env")"
rm -f "${WORK}/kit/kit-resolved-args.env"

begin "nodes that do not come back UN fail the upgrade"
run_upgrade STUB_UN=2
[ "${rc}" -ne 0 ] || fail "exit 0: ${out}"

begin "apply-config does nothing for an --image kit"
(cd "${WORK}/kit" && env PATH="${WORK}/stubs:${PATH}" KIT_NAME=cassandra-pods CASSANDRA_IMAGE=x "${BASH}" lib/apply-config.sh) > "${WORK}/out" 2>&1 || fail "exit $?"
[ ! -f "${WORK}/kubectl.calls" ] || fail "kubectl was called: $(cat "${WORK}/kubectl.calls")"

begin "apply-config puts every script and config input in the ConfigMap under its file name"
(cd "${WORK}/kit" && env PATH="${WORK}/stubs:${PATH}" KIT_NAME=cassandra-pods CASSANDRA_IMAGE= "${BASH}" lib/apply-config.sh) > "${WORK}/out" 2>&1 || fail "exit $?: $(cat "${WORK}/out")"
for key in fetch-tarball.sh=config/fetch-tarball.sh render-config.sh=config/render-config.sh \
  edl-cassandra-config.sh=config/edl-cassandra-config.sh edl-cassandra-agents.sh=config/edl-cassandra-agents.sh \
  cassandra.in.sh=config/cassandra.in.sh cassandra.patch.yaml=cassandra.patch.yaml \
  placement.patch.yaml=placement.patch.yaml cassandra-rackdc.properties=cassandra-rackdc.properties; do
  grep -qxF "# --from-file=${key}" "${WORK}/applied.yaml" || fail "missing ${key}: $(cat "${WORK}/applied.yaml")"
done
grep -q 'create configmap cassandra-pods-config --namespace default' "${WORK}/kubectl.calls" || fail "name: $(cat "${WORK}/kubectl.calls")"
grep -q 'label --local -f - easydblab/kit=cassandra-pods' "${WORK}/kubectl.calls" || fail "not labelled with the kit, so stop would leave it"

begin "apply-config with a config input missing fails naming it"
mv "${WORK}/kit/placement.patch.yaml" "${WORK}/placement.patch.yaml"
(cd "${WORK}/kit" && env PATH="${WORK}/stubs:${PATH}" KIT_NAME=cassandra-pods CASSANDRA_IMAGE= "${BASH}" lib/apply-config.sh) > "${WORK}/out" 2>&1
rc=$?
mv "${WORK}/placement.patch.yaml" "${WORK}/kit/placement.patch.yaml"
[ "${rc}" -ne 0 ] || fail "exit 0"
grep -q 'placement.patch.yaml' "${WORK}/out" || fail "message: $(cat "${WORK}/out")"
[ ! -f "${WORK}/applied.yaml" ] || fail "applied a partial ConfigMap"

echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
