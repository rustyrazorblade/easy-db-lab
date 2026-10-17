#!/usr/bin/env bash
#
# Unit tests for the SingleStore kit's SQL step, bin/lib/singlestore-sql.sh.
#
# After the cluster is ready, start creates the --database database through `kubectl exec -c node`
# into the master pod. The root password reaches mysql through MYSQL_PWD, read from stdin, so it is
# on no command line. Without --unlimited-storage the step runs CREATE DATABASE IF NOT EXISTS and
# nothing else. With it, the step sets maximum_blob_cache_size_mb from --blob-cache, creates the
# database ON S3 at a new timestamped path under the data bucket and prints the path. When
# SingleStore refuses, the step fails with SingleStore's error and creates no local database.
#
# kubectl is stubbed: it records each call's arguments one per line and the stdin of each exec,
# names the master pod, and answers an exec with STUB_EXEC_EXIT and STUB_EXEC_ERROR. Every case
# also runs under /bin/bash, the shell a kit step uses on macOS, when it exists.
#
# Run directly:  bash src/test/shell/singlestore-sql.test.sh
# Or via gradle: ./gradlew testSingleStoreSql

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/singlestore/bin/lib/singlestore-sql.sh"
PASSWORD="p w*'d"

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
  mkdir -p "${WORK}/stubs" "${WORK}/calls"
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
n=\$(ls "${WORK}/calls" | grep -c args)
call="${WORK}/calls/\$(printf '%03d' "\${n}")"
printf '%s\n' "\$@" > "\${call}.args"
case " \$* " in
  *" get pod "*) echo "node-singlestore-master-0" ;;
  *" exec "*)
    cat > "\${call}.stdin"
    [ -n "\${STUB_EXEC_ERROR:-}" ] && echo "\${STUB_EXEC_ERROR}" >&2
    exit "\${STUB_EXEC_EXIT:-0}"
    ;;
esac
exit 0
EOF
  chmod +x "${WORK}/stubs/kubectl"
}

SHELLS=(bash)
[ -x /bin/bash ] && SHELLS+=(/bin/bash)

# Runs `$@` in shell $SH after sourcing the library, with the kit variables and any in STUB_ENV.
in_lib() {
  env -i PATH="${WORK}/stubs:/usr/bin:/bin" HOME="${WORK}" \
    SINGLESTORE_PASSWORD="${PASSWORD}" SINGLESTORE_DATABASE=bench BUCKET_NAME=data-bkt REGION=us-west-2 \
    ${STUB_ENV[@]+"${STUB_ENV[@]}"} \
    "${SH}" -c 'set -euo pipefail; source "$1"; shift; "$@"' _ "${LIB}" "$@" > "${WORK}/out.txt" 2>&1
}

exec_stdin() { cat "${WORK}"/calls/*.stdin 2> /dev/null; }
exec_count() { ls "${WORK}"/calls/*.stdin 2> /dev/null | wc -l | tr -d ' '; }

for SH in "${SHELLS[@]}"; do
  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=()
  in_lib ss_sql_string "it's a \\ test"
  [ "$(cat "${WORK}/out.txt")" = "it\\'s a \\\\ test" ] || fail "[${SH}] a string literal is not escaped: $(cat "${WORK}/out.txt")"
  in_lib ss_sql_double_quoted 'say "hi" \ now'
  [ "$(cat "${WORK}/out.txt")" = 'say \"hi\" \\ now' ] || fail "[${SH}] a double-quoted literal is not escaped: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=(SINGLESTORE_UNLIMITED_STORAGE=false)
  in_lib singlestore_create_database || fail "[${SH}] the local database step failed: $(cat "${WORK}/out.txt")"
  [ "$(exec_stdin | sed -n 2,\$p)" = 'CREATE DATABASE IF NOT EXISTS `bench`;' ] ||
    fail "[${SH}] without unlimited storage the step ran: $(exec_stdin | sed -n 2,\$p)"
  grep -Fxq -- "-c" "${WORK}"/calls/*.args && grep -Fxq -- "node" "${WORK}"/calls/*.args ||
    fail "[${SH}] the SQL did not run in the master pod's node container: $(cat "${WORK}"/calls/*.args)"
  grep -Fxq "node-singlestore-master-0" "${WORK}"/calls/*.args || fail "[${SH}] the SQL did not run in the master pod"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=(SINGLESTORE_UNLIMITED_STORAGE=true SINGLESTORE_BLOB_CACHE=4096)
  in_lib singlestore_create_database || fail "[${SH}] the unlimited storage step failed: $(cat "${WORK}/out.txt")"
  sql="$(exec_stdin | sed -n 2,\$p)"
  grep -Fxq "SET GLOBAL maximum_blob_cache_size_mb = 4096;" <<< "${sql}" || fail "[${SH}] no blob cache setting: ${sql}"
  grep -Eq "^CREATE DATABASE \`bench\` ON S3 \"data-bkt/singlestore/bench-[0-9]{8}T[0-9]{6}Z\" CONFIG '\{\"region\":\"us-west-2\"\}' CREDENTIALS '\{\}';\$" <<< "${sql}" ||
    fail "[${SH}] no ON S3 statement with a timestamped path: ${sql}"
  grep -q "IF NOT EXISTS" <<< "${sql}" && fail "[${SH}] unlimited storage also created a local database: ${sql}"
  path="$(grep -Eo 'data-bkt/singlestore/bench-[0-9]{8}T[0-9]{6}Z' <<< "${sql}")"
  grep -Fq "s3://${path}" "${WORK}/out.txt" || fail "[${SH}] the step did not print the path s3://${path}: $(cat "${WORK}/out.txt")"

  tests_run=$((tests_run + 1))
  for args in "${WORK}"/calls/*.args; do
    grep -Fq -- "p w*" "${args}" && fail "[${SH}] the password is on a command line: $(cat "${args}")"
  done
  [ "$(exec_stdin | head -1)" = "${PASSWORD}" ] || fail "[${SH}] the password did not reach mysql on stdin"
  grep -Fq "MYSQL_PWD" "${WORK}"/calls/*.args || fail "[${SH}] mysql does not read the password from MYSQL_PWD"
  grep -Fq -- "p w*" "${WORK}/out.txt" && fail "[${SH}] the step printed the password"

  tests_run=$((tests_run + 1))
  setup
  STUB_ENV=(SINGLESTORE_UNLIMITED_STORAGE=true SINGLESTORE_BLOB_CACHE=4096
    "STUB_EXEC_EXIT=1" "STUB_EXEC_ERROR=ERROR 2290 (HY000): This license does not allow unlimited storage")
  in_lib singlestore_create_database && fail "[${SH}] a refused ON S3 database did not fail the step"
  grep -Fq "This license does not allow unlimited storage" "${WORK}/out.txt" ||
    fail "[${SH}] the failure does not show SingleStore's error: $(cat "${WORK}/out.txt")"
  [ "$(exec_count)" = 1 ] || fail "[${SH}] the step tried again after the refusal ($(exec_count) execs)"
  exec_stdin | grep -q "IF NOT EXISTS" && fail "[${SH}] the step fell back to a local database"

  tests_run=$((tests_run + 1))
  for cache in "" "4GB"; do
    setup
    STUB_ENV=(SINGLESTORE_UNLIMITED_STORAGE=true "SINGLESTORE_BLOB_CACHE=${cache}")
    in_lib ss_check_unlimited_storage_args && fail "[${SH}] unlimited storage with blob cache '${cache}' was accepted"
    grep -q -- "--blob-cache" "${WORK}/out.txt" || fail "[${SH}] the message does not name --blob-cache: $(cat "${WORK}/out.txt")"
  done
  setup
  STUB_ENV=(SINGLESTORE_UNLIMITED_STORAGE=false)
  in_lib ss_check_unlimited_storage_args || fail "[${SH}] local storage needs no --blob-cache"
done

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
