#!/usr/bin/env bash
#
# Unit tests for the SingleStore kit's start and wait steps, bin/lib/singlestore-cluster.sh.
#
# The start step sizes the cluster before it touches anything, clears what a failed start left
# (the MemsqlCluster, its PVCs, the kit's Secrets), writes the license (trailing newline stripped)
# and the root password to Secrets, and only then applies the MemsqlCluster with the sizes filled
# in. The wait step waits for every pod and every leaf, and fails with SingleStore's license error,
# with the key masked, and the --force advice when the license refuses the cluster. A wait that
# times out gives the last error of the pod query and of SHOW LEAVES, with the password masked. An
# operator validation error or a crash-looping pod fails the wait at the first poll, quoting the
# operator's error or the pod's last log lines with the password masked. A failed wait prints the
# pods and then deletes the MemsqlCluster, its PVCs and the kit's Secrets.
#
# kubectl is stubbed: it records each call's arguments one per line, keeps the stdin of each
# apply, copies every --from-file it is given, and answers the queries from STUB_* variables.
# sleep is stubbed. Every case also runs under /bin/bash, the shell a kit step uses on macOS.
#
# Run directly:  bash src/test/shell/singlestore-cluster.test.sh
# Or via gradle: ./gradlew testSingleStoreCluster

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/singlestore"
KIT_LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
LICENSE_KEY="FAKE-LICENSE-NOT-A-REAL-KEY"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/calls" "${WORK}/kit/bin/lib"
  cp "${KIT}"/bin/lib/*.sh "${WORK}/kit/bin/lib/"
  # The CR as kit install writes it: install args rendered, run-time values left for start.
  sed -e 's/__SINGLESTORE_VERSION__/alma-test/' -e 's/__SINGLESTORE_AGGREGATOR_CORES__/2/' \
    -e 's/__SINGLESTORE_AGGREGATORS__/0/' "${KIT}/memsqlcluster.yaml.template" > "${WORK}/kit/memsqlcluster.yaml"
  # The two kubectl calls of a pipeline run at once, so each takes its number under a mkdir lock.
  echo 0 > "${WORK}/counter"
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
set -C
until : 2> /dev/null > "${WORK}/counter.lock"; do [ -d "${WORK}" ] || exit 0; done
set +C
read -r n < "${WORK}/counter"
echo \$((n + 1)) > "${WORK}/counter"
rm -f "${WORK}/counter.lock"
call="${WORK}/calls/\$(printf '%03d' "\${n}")"
printf '%s\n' "\$@" > "\${call}.args"
for arg in "\$@"; do
  case "\${arg}" in
    --from-file=*) cp "\${arg#*=*=}" "\${call}.file" ;;
  esac
done
case " \$* " in
  *" delete memsqlcluster "*) : > "${WORK}/cr-deleted" ;;
  # STUB_FAIL_FIRST_SECRET_APPLY makes the first server-side apply (the license Secret) fail.
  *" apply --server-side "*)
    cat > "\${call}.stdin"
    if [ -n "\${STUB_FAIL_FIRST_SECRET_APPLY:-}" ] && [ ! -e "${WORK}/secret-apply-failed" ]; then
      : > "${WORK}/secret-apply-failed"
      exit 1
    fi ;;
  *" apply "*) cat > "\${call}.stdin" ;;
  *" create secret "*) echo "kind: Secret" ;;
  # A local label passes the object through with a line per label, as it would be on the object.
  *" label --local "*)
    cat
    for arg in "\$@"; do case "\${arg}" in easydblab/*) echo "# label \${arg}" ;; esac; done ;;
  *" get nodes "*) printf '%s\n' "8 65544428Ki" "8 65544428Ki" "8 65544428Ki" ;;
  *" get memsqlcluster "*"creationTimestamp"*) echo "2026-10-16T12:00:00Z" ;;
  *" get memsqlcluster "*) printf '%s' "\${STUB_CR_STATUS:-}" ;;
  *" logs "*" --previous "*) printf '%s\n' "\${STUB_POD_LOG:-}" ;;
  *" logs "*) printf '%s\n' "\${STUB_OPERATOR_LOG:-}" ;;
  *" get pods "*"waiting.reason"*) printf '%s\n' "\${STUB_WAITING:-}" ;;
  # Once the MemsqlCluster is deleted, its pods are gone.
  *" get pods "*"--no-headers"*) [ -e "${WORK}/cr-deleted" ] || printf '%s\n' \${STUB_READY:-} ;;
  *" get pods "*"Ready"*)
    if [ -n "\${STUB_PODS_ERROR:-}" ]; then echo "\${STUB_PODS_ERROR}" >&2; exit 1; fi
    printf '%s\n' \${STUB_READY:-} ;;
  *" get pods "*) printf '%s\n' \${STUB_READY:-} ;;
  *" get pod "*) echo "node-singlestore-master-0" ;;
  *" exec "*)
    # A failing mysql echoes the password line it read, so the test sees whether it is masked.
    IFS= read -r password; cat > /dev/null
    if [ -n "\${STUB_LEAVES_ERROR:-}" ]; then echo "\${STUB_LEAVES_ERROR} \${password}" >&2; exit 1; fi
    printf '%s\n' "\${STUB_LEAVES:-}" ;;
esac
exit 0
EOF
  printf '#!/usr/bin/env bash\necho "$*" >> "%s/sleeps"\nexit 0\n' "${WORK}" > "${WORK}/stubs/sleep"
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/sleep"
}

SHELLS=(bash)
[ -x /bin/bash ] && SHELLS+=(/bin/bash)

# Runs `$@` in shell $SH from the kit directory after sourcing the library, as a kit step does.
in_kit() {
  (
    cd "${WORK}/kit" &&
      env -i PATH="${WORK}/stubs:/usr/bin:/bin" HOME="${WORK}" KIT_NAME=singlestore EDL_KIT_LIB="${KIT_LIB}" \
        "SINGLESTORE_LICENSE=${LICENSE_KEY}"$'\n' SINGLESTORE_PASSWORD="p w*'d" SINGLESTORE_DATABASE=test \
        SINGLESTORE_LEAVES=3 SINGLESTORE_AGGREGATORS=0 SINGLESTORE_AGGREGATOR_CORES=2 SINGLESTORE_AGGREGATOR_MEMORY=8 \
        STORAGE_SIZE=10Ti SINGLESTORE_UNLIMITED_STORAGE=false SS_WAIT_ATTEMPTS=3 \
        ${STUB_ENV[@]+"${STUB_ENV[@]}"} \
        "${SH}" -c 'set -euo pipefail; source bin/lib/singlestore-cluster.sh; "$@"' _ "$@"
  ) > "${WORK}/out.txt" 2>&1
}

# The number of the first recorded kubectl call whose arguments hold every one of $@.
first_call() {
  local file
  for file in "${WORK}"/calls/*.args; do
    local all=1 word
    for word in "$@"; do grep -Fxq -- "${word}" "${file}" || all=0; done
    if [ "${all}" = 1 ]; then basename "${file}" .args; return; fi
  done
  echo 999
}

# How many times the step slept between polls.
sleeps() { [ -f "${WORK}/sleeps" ] && wc -l < "${WORK}/sleeps" | tr -d ' ' || echo 0; }

# Fails the case unless the failed wait printed the pod list and then deleted the MemsqlCluster,
# the cluster's PVCs and the kit's Secrets, in that order, so the next start is not refused.
removes_the_failed_cluster() {
  local pods cr pvc secrets
  pods="$(first_call get pods -l easydblab/kit=singlestore wide)"
  cr="$(first_call delete memsqlcluster singlestore)"
  pvc="$(first_call delete pvc -l app.kubernetes.io/instance=singlestore)"
  secrets="$(first_call delete secret -l easydblab/kit=singlestore)"
  [ "${pods}" -lt "${cr}" ] && [ "${cr}" -lt "${pvc}" ] && [ "${pvc}" -lt "${secrets}" ] && [ "${secrets}" != 999 ] ||
    fail "[${SH}] $1: the failed wait did not print the pods, then delete the CR, PVCs and Secrets (${pods} ${cr} ${pvc} ${secrets})"
}

for SH in "${SHELLS[@]}"; do
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=()
  in_kit singlestore_start_cluster || fail "[${SH}] start failed: $(cat "${WORK}/out.txt")"
  cr="$(grep -l "kind: MemsqlCluster" "${WORK}"/calls/*.stdin | head -1)"
  [ -n "${cr}" ] || { fail "[${SH}] no MemsqlCluster was applied"; continue; }
  grep -q '\${' "${cr}" && fail "[${SH}] the applied CR has an unfilled value: $(grep '\${' "${cr}")"
  for expected in "redundancyLevel: 1" "count: 3" "cores: 4" "memoryMB: 51520" "storageGB: 10240" "memoryMB: 8192"; do
    grep -Fq "${expected}" "${cr}" || fail "[${SH}] the applied CR lacks '${expected}'"
  done

  tests_run=$((tests_run + 1))
  license="$(grep -l -Fx -- "singlestore-license" "${WORK}"/calls/*.args | head -1)"
  [ -n "${license}" ] && [ -f "${license%.args}.file" ] || { fail "[${SH}] no license Secret was created from a file"; continue; }
  [ "$(od -An -c "${license%.args}.file" | tr -d ' \n')" = "$(printf '%s' "${LICENSE_KEY}" | od -An -c | tr -d ' \n')" ] ||
    fail "[${SH}] the license Secret is not the key with its newline stripped: $(od -c "${license%.args}.file")"
  password="$(grep -l -Fx -- "singlestore-root-password" "${WORK}"/calls/*.args | head -1)"
  [ "$(cat "${password%.args}.file" 2>/dev/null)" = "p w*'d" ] || fail "[${SH}] the root password Secret does not hold the password"
  grep -Eq -- "^--from-file=ROOT_PASSWORD=" "${password}" || fail "[${SH}] the password Secret key is not ROOT_PASSWORD"
  for args in "${WORK}"/calls/*.args; do
    grep -Fq -- "${LICENSE_KEY}" "${args}" && fail "[${SH}] the license is on a command line"
    grep -Fq -- "p w*" "${args}" && fail "[${SH}] the password is on a command line"
  done
  grep -Fq -- "${LICENSE_KEY}" "${WORK}/out.txt" && fail "[${SH}] start printed the license"

  # Both Secrets are applied server-side, so no last-applied annotation (which `kubectl describe
  # secret` prints) holds a copy of the license or the password, and they carry the kit's labels in
  # that same apply, never in a later `kubectl label` of the live object.
  tests_run=$((tests_run + 1))
  secret_applies=0
  for stdin in "${WORK}"/calls/*.stdin; do
    grep -Fqx "kind: Secret" "${stdin}" || continue
    secret_applies=$((secret_applies + 1))
    args="${stdin%.stdin}.args"
    grep -Fqx -- "--server-side" "${args}" || fail "[${SH}] a Secret was applied client-side: $(tr '\n' ' ' < "${args}")"
    grep -Fqx "# label easydblab/kit=singlestore" "${stdin}" || fail "[${SH}] an applied Secret has no kit label"
    grep -Fqx "# label easydblab/role=credentials" "${stdin}" || fail "[${SH}] an applied Secret has no role label"
  done
  [ "${secret_applies}" = 2 ] || fail "[${SH}] ${secret_applies} Secret applies, not 2"
  for args in "${WORK}"/calls/*.args; do
    [ "$(head -1 "${args}")" = label ] && ! grep -Fqx -- "--local" "${args}" &&
      fail "[${SH}] a live object was labelled after the apply: $(tr '\n' ' ' < "${args}")"
  done
  for name in singlestore-license singlestore-root-password; do
    args="$(grep -l -Fx -- "${name}" "${WORK}"/calls/*.args | head -1)"
    grep -Fqx -- "-n" "${args}" && grep -Fqx -- "default" "${args}" || fail "[${SH}] ${name} is not created in namespace default"
  done

  tests_run=$((tests_run + 1))
  cr_call="$(basename "${cr}" .stdin)"
  clear_cr="$(first_call delete memsqlcluster singlestore)"
  clear_pvc="$(first_call delete pvc -l app.kubernetes.io/instance=singlestore)"
  clear_secrets="$(first_call delete secret -l easydblab/kit=singlestore)"
  secret_apply="$(grep -l "kind: Secret" "${WORK}"/calls/*.stdin | tail -1 | xargs basename | sed 's/.stdin//')"
  [ "${clear_cr}" -lt "${clear_pvc}" ] && [ "${clear_pvc}" -lt "${clear_secrets}" ] && [ "${clear_secrets}" -lt "${secret_apply}" ] ||
    fail "[${SH}] leftovers are not cleared CR, PVCs, Secrets before the Secrets are applied (${clear_cr} ${clear_pvc} ${clear_secrets} ${secret_apply})"
  [ "${secret_apply}" -lt "${cr_call}" ] || fail "[${SH}] the Secrets are not applied before the MemsqlCluster"

  # A license Secret that cannot be applied fails the start before the password Secret or the
  # MemsqlCluster is applied.
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=(STUB_FAIL_FIRST_SECRET_APPLY=1)
  in_kit singlestore_start_cluster && fail "[${SH}] start passed with the license Secret not applied"
  grep -q "singlestore-license" "${WORK}/out.txt" || fail "[${SH}] the failure does not name the Secret: $(cat "${WORK}/out.txt")"
  [ "$(first_call singlestore-root-password)" = 999 ] || fail "[${SH}] the password Secret was written after the license failed"
  grep -lq "kind: MemsqlCluster" "${WORK}"/calls/*.stdin && fail "[${SH}] the MemsqlCluster was applied after the license failed"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=(SINGLESTORE_REDUNDANCY=2)
  in_kit singlestore_start_cluster && fail "[${SH}] a redundancy that does not divide 3 leaves started"
  grep -q -- "--redundancy" "${WORK}/out.txt" || fail "[${SH}] no redundancy message: $(cat "${WORK}/out.txt")"
  ls "${WORK}"/calls/*.args > /dev/null 2>&1 && grep -lq -Fx -e delete -e apply -e create "${WORK}"/calls/*.args &&
    fail "[${SH}] a failed sizing still changed the cluster: $(cat "${WORK}"/calls/*.args)"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=True True True True" "STUB_LEAVES=$(printf 'h1\t3306\tonline\nh2\t3306\tonline\nh3\t3306\tonline')")
  in_kit singlestore_wait || fail "[${SH}] the wait failed with every pod ready and every leaf online: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=True True True True" "STUB_LEAVES=$(printf 'h1\t3306\tonline\nh2\t3306\tonline\nh3\t3306\tonline')"
    "STUB_OPERATOR_LOG=set license: License_type Enterprise, License_capacity 2 units, expires in 30 days")
  in_kit singlestore_wait || fail "[${SH}] a license the server accepted was read as a refusal: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=True True" "STUB_LEAVES=")
  in_kit singlestore_wait && fail "[${SH}] the wait passed with two of four pods ready"
  grep -qi "timed out" "${WORK}/out.txt" || fail "[${SH}] the wait did not say it timed out: $(cat "${WORK}/out.txt")"
  removes_the_failed_cluster "timeout"

  # A wrong password or a refused connection is told apart from a slow cluster: the timeout gives
  # the last error of each query, with the password masked.
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=True True True True" "STUB_LEAVES_ERROR=ERROR 1045 (28000): Access denied for user 'root'@'localhost'")
  in_kit singlestore_wait && fail "[${SH}] the wait passed with SHOW LEAVES failing"
  grep -qi "timed out" "${WORK}/out.txt" || fail "[${SH}] the wait did not say it timed out: $(cat "${WORK}/out.txt")"
  grep -Fq "ERROR 1045 (28000): Access denied" "${WORK}/out.txt" || fail "[${SH}] the timeout does not give the SHOW LEAVES error: $(cat "${WORK}/out.txt")"
  grep -Fq -- "p w*'d" "${WORK}/out.txt" && fail "[${SH}] the timeout printed the password: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_PODS_ERROR=The connection to the server 10.0.0.1:6443 was refused")
  in_kit singlestore_wait && fail "[${SH}] the wait passed with the pod query failing"
  grep -Fq "The connection to the server 10.0.0.1:6443 was refused" "${WORK}/out.txt" ||
    fail "[${SH}] the timeout does not give the pod query error: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=" "STUB_OPERATOR_LOG=error setting license ${LICENSE_KEY}: Error 1888 (HY000): Unable to reload license file: The provided license is invalid.")
  in_kit singlestore_wait && fail "[${SH}] the wait passed a refused license"
  grep -Fq "The provided license is invalid" "${WORK}/out.txt" || fail "[${SH}] the failure does not show SingleStore's error: $(cat "${WORK}/out.txt")"
  grep -Fq "kit install singlestore --force" "${WORK}/out.txt" || fail "[${SH}] no --force advice: $(cat "${WORK}/out.txt")"
  grep -Fq -- "--leaf-cores" "${WORK}/out.txt" && grep -Fq -- "--leaf-memory" "${WORK}/out.txt" || fail "[${SH}] the advice does not name the leaf flags"
  grep -Fq -- "${LICENSE_KEY}" "${WORK}/out.txt" && fail "[${SH}] the failure printed the license key"
  [ "$(sleeps)" = 0 ] || fail "[${SH}] the wait kept polling after the refusal ($(sleeps) sleeps)"
  removes_the_failed_cluster "license refusal"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=" "STUB_CR_STATUS={\"phase\":\"Failed\",\"message\":\"license capacity exceeded: 2 units\"}")
  in_kit singlestore_wait && fail "[${SH}] the wait passed a license refusal in the CR status"
  grep -Fq "license capacity exceeded" "${WORK}/out.txt" || fail "[${SH}] the CR status refusal is not shown: $(cat "${WORK}/out.txt")"

  # The operator refusing the spec fails the wait at the first poll, not at its timeout.
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=" "STUB_OPERATOR_LOG=2026-10-19T20:40:00Z ERROR controller/controller.go:324 failed to validate MemSQL Cluster. {\"Request.Name\": \"singlestore\", \"error\": \"ERROR while validating spec: \`spec.aggregatorSpec.count\` can not be non 0 when \`spec.masterAggregatorSpec\` is specified\"}")
  in_kit singlestore_wait && fail "[${SH}] the wait passed an operator validation error"
  grep -Fq "ERROR while validating spec: \`spec.aggregatorSpec.count\` can not be non 0" "${WORK}/out.txt" ||
    fail "[${SH}] the failure does not quote the operator's error: $(cat "${WORK}/out.txt")"
  grep -qi "timed out" "${WORK}/out.txt" && fail "[${SH}] a validation error waited for the timeout: $(cat "${WORK}/out.txt")"
  grep -Fq "refused the license" "${WORK}/out.txt" && fail "[${SH}] a validation error was read as a license refusal"
  [ "$(sleeps)" = 0 ] || fail "[${SH}] the wait kept polling after the validation error ($(sleeps) sleeps)"
  removes_the_failed_cluster "validation error"

  # A crash-looping pod fails the wait at the first poll, quoting its last log lines with the
  # password masked. The node image's startup prints the SQL it could not run, password included.
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=False False"
    "STUB_WAITING=$(printf 'node-singlestore-master-0\texporter= node=CrashLoopBackOff \nnode-singlestore-leaf-ag1-0\tnode=CrashLoopBackOff ')"
    "STUB_POD_LOG=$(printf 'Ensuring the root password is setup\nerror setting root password: Error 1064 (42000): near %s at line 1' "'p w*'d'")")
  in_kit singlestore_wait && fail "[${SH}] the wait passed a crash-looping pod"
  grep -Fq "node-singlestore-master-0" "${WORK}/out.txt" && grep -Fq "CrashLoopBackOff" "${WORK}/out.txt" ||
    fail "[${SH}] the failure does not name the crash-looping pod: $(cat "${WORK}/out.txt")"
  grep -Fq "error setting root password: Error 1064 (42000)" "${WORK}/out.txt" ||
    fail "[${SH}] the failure does not quote the pod's last log lines: $(cat "${WORK}/out.txt")"
  grep -Fq -- "p w*" "${WORK}/out.txt" && fail "[${SH}] the crash loop failure printed the password: $(cat "${WORK}/out.txt")"
  grep -Fq -- "****" "${WORK}/out.txt" || fail "[${SH}] the password is not shown masked: $(cat "${WORK}/out.txt")"
  first_call logs node-singlestore-master-0 -c node --previous | grep -qv 999 ||
    fail "[${SH}] the log is not the crash-looping container's previous run: $(cat "${WORK}"/calls/*.args)"
  [ "$(sleeps)" = 0 ] || fail "[${SH}] the wait kept polling after the crash loop ($(sleeps) sleeps)"
  removes_the_failed_cluster "crash loop"

  # A pod that is only waiting (an image pull, a container creating) is not a crash loop.
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=True True True True" "STUB_LEAVES=$(printf 'h1\t3306\tonline\nh2\t3306\tonline\nh3\t3306\tonline')"
    "STUB_WAITING=$(printf 'node-singlestore-master-0\tnode=ContainerCreating ')")
  in_kit singlestore_wait || fail "[${SH}] a waiting, not crash-looping, pod failed the wait: $(cat "${WORK}/out.txt")"
done

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
