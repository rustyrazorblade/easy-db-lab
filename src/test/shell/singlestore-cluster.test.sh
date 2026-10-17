#!/usr/bin/env bash
#
# Unit tests for the SingleStore kit's start and wait steps, bin/lib/singlestore-cluster.sh.
#
# The start step sizes the cluster before it touches anything, clears what a failed start left
# (the MemsqlCluster, its PVCs, the kit's Secrets), writes the license (trailing newline stripped)
# and the root password to Secrets, and only then applies the MemsqlCluster with the sizes filled
# in. The wait step waits for every pod and every leaf, and fails with SingleStore's license error,
# with the key masked, and the --force advice when the license refuses the cluster.
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
  *" apply "*) cat > "\${call}.stdin" ;;
  *" create secret "*) echo "kind: Secret" ;;
  *" get nodes "*) printf '%s\n' "8 65544428Ki" "8 65544428Ki" "8 65544428Ki" ;;
  *" get memsqlcluster "*"creationTimestamp"*) echo "2026-10-16T12:00:00Z" ;;
  *" get memsqlcluster "*) printf '%s' "\${STUB_CR_STATUS:-}" ;;
  *" logs "*) printf '%s\n' "\${STUB_OPERATOR_LOG:-}" ;;
  *" get pods "*) printf '%s\n' \${STUB_READY:-} ;;
  *" get pod "*) echo "node-singlestore-master-0" ;;
  *" exec "*) cat > /dev/null; printf '%s\n' "\${STUB_LEAVES:-}" ;;
esac
exit 0
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/sleep"
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/sleep"
}

SHELLS=(bash)
[ -x /bin/bash ] && SHELLS+=(/bin/bash)

# Runs `$@` in shell $SH from the kit directory after sourcing the library, as a kit step does.
in_kit() {
  (
    cd "${WORK}/kit" &&
      env -i PATH="${WORK}/stubs:/usr/bin:/bin" HOME="${WORK}" \
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
  grep -Fxq -- "--from-file=ROOT_PASSWORD=" "${password}" 2>/dev/null
  grep -Eq -- "^--from-file=ROOT_PASSWORD=" "${password}" || fail "[${SH}] the password Secret key is not ROOT_PASSWORD"
  for args in "${WORK}"/calls/*.args; do
    grep -Fq -- "${LICENSE_KEY}" "${args}" && fail "[${SH}] the license is on a command line"
    grep -Fq -- "p w*" "${args}" && fail "[${SH}] the password is on a command line"
  done
  grep -Fq -- "${LICENSE_KEY}" "${WORK}/out.txt" && fail "[${SH}] start printed the license"

  tests_run=$((tests_run + 1))
  cr_call="$(basename "${cr}" .stdin)"
  clear_cr="$(first_call delete memsqlcluster singlestore)"
  clear_pvc="$(first_call delete pvc -l app.kubernetes.io/instance=singlestore)"
  clear_secrets="$(first_call delete secret -l easydblab/kit=singlestore)"
  secret_apply="$(grep -l "kind: Secret" "${WORK}"/calls/*.stdin | tail -1 | xargs basename | sed 's/.stdin//')"
  [ "${clear_cr}" -lt "${clear_pvc}" ] && [ "${clear_pvc}" -lt "${clear_secrets}" ] && [ "${clear_secrets}" -lt "${secret_apply}" ] ||
    fail "[${SH}] leftovers are not cleared CR, PVCs, Secrets before the Secrets are applied (${clear_cr} ${clear_pvc} ${clear_secrets} ${secret_apply})"
  [ "${secret_apply}" -lt "${cr_call}" ] || fail "[${SH}] the Secrets are not applied before the MemsqlCluster"

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
  STUB_ENV=("STUB_READY=True True" "STUB_LEAVES=")
  in_kit singlestore_wait && fail "[${SH}] the wait passed with two of four pods ready"
  grep -qi "timed out" "${WORK}/out.txt" || fail "[${SH}] the wait did not say it timed out: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=" "STUB_OPERATOR_LOG=error setting license ${LICENSE_KEY}: Error 1888 (HY000): Unable to reload license file: The provided license is invalid.")
  in_kit singlestore_wait && fail "[${SH}] the wait passed a refused license"
  grep -Fq "The provided license is invalid" "${WORK}/out.txt" || fail "[${SH}] the failure does not show SingleStore's error: $(cat "${WORK}/out.txt")"
  grep -Fq "kit install singlestore --force" "${WORK}/out.txt" || fail "[${SH}] no --force advice: $(cat "${WORK}/out.txt")"
  grep -Fq -- "--leaf-cores" "${WORK}/out.txt" && grep -Fq -- "--leaf-memory" "${WORK}/out.txt" || fail "[${SH}] the advice does not name the leaf flags"
  grep -Fq -- "${LICENSE_KEY}" "${WORK}/out.txt" && fail "[${SH}] the failure printed the license key"
  [ "$(grep -c "get pods" "${WORK}"/calls/*.args | awk -F: '{s+=$2} END {print s}')" -le 1 ] || fail "[${SH}] the wait kept polling after the refusal"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=("STUB_READY=" "STUB_CR_STATUS={\"phase\":\"Failed\",\"message\":\"license capacity exceeded: 2 units\"}")
  in_kit singlestore_wait && fail "[${SH}] the wait passed a license refusal in the CR status"
  grep -Fq "license capacity exceeded" "${WORK}/out.txt" || fail "[${SH}] the CR status refusal is not shown: $(cat "${WORK}/out.txt")"
done

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
