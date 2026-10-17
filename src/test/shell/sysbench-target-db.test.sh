#!/usr/bin/env bash
#
# Unit tests for the sysbench kit's database driver args, bin/lib/target-db.sh, and their use in
# bin/prepare.sh, bin/start.sh and bin/stop.sh.
#
# The driver args are built once, as a shell array, from the TARGET_* variables the framework
# injects. A target with a password (TARGET_MYSQL_PASSWORD or TARGET_PG_PASSWORD) adds
# --mysql-password or --pgsql-password as one argument, however many spaces, `*` or quotes it holds.
# The create-database pod gets the password through MYSQL_PWD, never on its command line. No phase
# prints the password. A target without one gets exactly the args it got before.
#
# kubectl and curl are stubbed: kubectl records each call's arguments one per line and answers the
# calls each script makes. No cluster, no network.
#
# Run directly:  bash src/test/shell/sysbench-target-db.test.sh
# Or via gradle: ./gradlew testSysbenchTargetDb

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench"
PASSWORD="p w*'d \"x"

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
  mkdir -p "${WORK}/stubs" "${WORK}/kit/bin/lib" "${WORK}/calls"
  cp "${KIT}/bin/lib/target-db.sh" "${WORK}/kit/bin/lib/target-db.sh"
  for phase in prepare start stop; do
    cp "${KIT}/bin/${phase}.sh.template" "${WORK}/kit/bin/${phase}.sh"
  done
  cat > "${WORK}/stubs/kubectl" <<EOF
#!/usr/bin/env bash
n=\$(ls "${WORK}/calls" | wc -l | tr -d ' ')
printf '%s\n' "\$@" > "${WORK}/calls/\$(printf '%03d' "\${n}").args"
case " \$* " in
  *" get pod "*) printf '%s' "\${STUB_PHASE:-Succeeded}" ;;
  *" get events "*) echo "Warning  Failed  pod/stub  ErrImagePull" ;;
  *" logs "*) echo "[ 10s ] thds: 4 tps: 1.00 qps: 2.00 (r/w/o: 1/1/0) lat (ms,99%): 3.00 err/s: 0.00 reconn/s: 0.00" ;;
esac
exit 0
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/curl"
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/sleep"
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/curl" "${WORK}/stubs/sleep"
}

MYSQL_TARGET=(TARGET_MYSQL_HOST=10.0.0.9 TARGET_MYSQL_PORT=30306 TARGET_MYSQL_USER=root TARGET_MYSQL_DATABASE=sbtest)
PG_TARGET=(TARGET_PG_HOST=10.0.0.9 TARGET_PG_PORT=5432 TARGET_PG_USER=u TARGET_PG_DATABASE=d)

# Runs bin/$1.sh with the variables that follow, writing its output to out.txt.
run_phase() {
  local phase="$1"
  shift
  (
    cd "${WORK}" &&
      env PATH="${WORK}/stubs:${PATH}" KIT_NAME=sb1 KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        WORKLOAD=oltp_read_write TABLES=2 SCALE=1 THREADS=4 DURATION=10 RATE=0 SKIP_TRX=off RAND_TYPE=special \
        "$@" bash "${WORK}/kit/bin/${phase}.sh"
  ) > "${WORK}/out.txt" 2>&1
}

# The DB_ARGS array target_db_args builds from the variables given, one element per line.
db_args_for() {
  env -i PATH="${PATH}" "$@" bash -c '
    set -euo pipefail
    source "$1"
    target_db_args
    printf "%s\n" "${DB_ARGS[@]}"
  ' _ "${KIT}/bin/lib/target-db.sh"
}

# The recorded kubectl calls that ran a pod with role $1.
calls_for_role() {
  grep -l -- "--labels=easydblab/kit=sb1,easydblab/role=$1" "${WORK}"/calls/*.args 2>/dev/null
}

test_a_passwordless_mysql_target_gets_the_args_it_always_got() {
  tests_run=$((tests_run + 1))
  local expected actual
  expected="$(printf '%s\n' --db-driver=mysql --mysql-host=10.0.0.9 --mysql-port=30306 --mysql-user=root --mysql-db=sbtest)"
  actual="$(db_args_for "${MYSQL_TARGET[@]}" TARGET_MYSQL_PASSWORD=)"
  [ "${actual}" = "${expected}" ] || fail "passwordless mysql args changed: ${actual}"
}

test_a_passwordless_pgsql_target_gets_the_args_it_always_got() {
  tests_run=$((tests_run + 1))
  local expected actual
  expected="$(printf '%s\n' --db-driver=pgsql --pgsql-host=10.0.0.9 --pgsql-port=5432 --pgsql-user=u --pgsql-db=d)"
  actual="$(db_args_for "${PG_TARGET[@]}")"
  [ "${actual}" = "${expected}" ] || fail "passwordless pgsql args changed: ${actual}"
}

test_a_mysql_password_is_one_argument() {
  tests_run=$((tests_run + 1))
  local actual
  actual="$(db_args_for "${MYSQL_TARGET[@]}" "TARGET_MYSQL_PASSWORD=${PASSWORD}")"
  grep -Fxq -- "--mysql-password=${PASSWORD}" <<< "${actual}" || fail "no single --mysql-password arg: ${actual}"
  [ "$(wc -l <<< "${actual}" | tr -d ' ')" -eq 6 ] || fail "the password split into several args: ${actual}"
}

test_a_pgsql_password_is_one_argument() {
  tests_run=$((tests_run + 1))
  local actual
  actual="$(db_args_for "${PG_TARGET[@]}" "TARGET_PG_PASSWORD=${PASSWORD}")"
  grep -Fxq -- "--pgsql-password=${PASSWORD}" <<< "${actual}" || fail "no single --pgsql-password arg: ${actual}"
}

test_no_endpoint_is_reported() {
  tests_run=$((tests_run + 1))
  db_args_for > /dev/null 2>&1 && fail "target_db_args succeeded with no TARGET_* endpoint"
}

test_prepare_authenticates_every_pod_and_never_prints_the_password() {
  tests_run=$((tests_run + 1))
  setup
  run_phase prepare "${MYSQL_TARGET[@]}" "TARGET_MYSQL_PASSWORD=${PASSWORD}" ||
    { fail "prepare exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  local createdb
  createdb="$(calls_for_role sysbench-createdb)"
  [ -n "${createdb}" ] || { fail "no create-database pod was run"; return; }
  grep -Fxq -- "--env=MYSQL_PWD=${PASSWORD}" "${createdb}" || fail "the create-database pod has no MYSQL_PWD: $(cat "${createdb}")"
  [ "$(grep -cF -- "${PASSWORD}" "${createdb}")" -eq 1 ] || fail "the password is on the create-database command line: $(cat "${createdb}")"
  for role in sysbench-cleanup sysbench-prepare; do
    local call
    call="$(calls_for_role "${role}")"
    [ -n "${call}" ] || { fail "no ${role} pod was run"; continue; }
    grep -Fxq -- "--mysql-password=${PASSWORD}" "${call}" || fail "the ${role} pod does not authenticate: $(cat "${call}")"
  done
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "prepare printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_prepare_of_a_passwordless_target_has_no_password_anywhere() {
  tests_run=$((tests_run + 1))
  setup
  run_phase prepare "${MYSQL_TARGET[@]}" TARGET_MYSQL_PASSWORD= ||
    { fail "prepare exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -lq -- "MYSQL_PWD\|--mysql-password" "${WORK}"/calls/*.args && fail "a passwordless target got a password: $(cat "${WORK}"/calls/*.args)"
  return 0
}

test_start_authenticates_the_run_and_never_prints_the_password() {
  tests_run=$((tests_run + 1))
  setup
  run_phase start "${MYSQL_TARGET[@]}" "TARGET_MYSQL_PASSWORD=${PASSWORD}" ||
    { fail "start exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  local run
  run="$(calls_for_role sysbench-run)"
  [ -n "${run}" ] || { fail "no run pod was started"; return; }
  grep -Fxq -- "--mysql-password=${PASSWORD}" "${run}" || fail "the run pod does not authenticate: $(cat "${run}")"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "start printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_stop_authenticates_the_cleanup_and_never_prints_the_password() {
  tests_run=$((tests_run + 1))
  setup
  run_phase stop "${PG_TARGET[@]}" "TARGET_PG_PASSWORD=${PASSWORD}" ||
    { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  local cleanup
  cleanup="$(calls_for_role sysbench-cleanup)"
  [ -n "${cleanup}" ] || { fail "no cleanup pod was run"; return; }
  grep -Fxq -- "--pgsql-password=${PASSWORD}" "${cleanup}" || fail "the cleanup pod does not authenticate: $(cat "${cleanup}")"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "stop printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_a_cleanup_pod_that_never_starts_lists_its_events_not_its_description() {
  tests_run=$((tests_run + 1))
  setup
  STUB_PHASE=Pending run_phase stop "${PG_TARGET[@]}" "TARGET_PG_PASSWORD=${PASSWORD}" ||
    { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -lqx -- "describe" "${WORK}"/calls/*.args && fail "stop ran kubectl describe, which prints the pod's command line"
  grep -q "ErrImagePull" "${WORK}/out.txt" || fail "stop did not show the pending pod's events: $(cat "${WORK}/out.txt")"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "stop printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_a_passwordless_mysql_target_gets_the_args_it_always_got
test_a_passwordless_pgsql_target_gets_the_args_it_always_got
test_a_mysql_password_is_one_argument
test_a_pgsql_password_is_one_argument
test_no_endpoint_is_reported
test_prepare_authenticates_every_pod_and_never_prints_the_password
test_prepare_of_a_passwordless_target_has_no_password_anywhere
test_start_authenticates_the_run_and_never_prints_the_password
test_stop_authenticates_the_cleanup_and_never_prints_the_password
test_a_cleanup_pod_that_never_starts_lists_its_events_not_its_description

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
