#!/usr/bin/env bash
#
# Unit tests for the sysbench kit's database driver args, bin/lib/target-db.sh, and their use in
# bin/prepare.sh, bin/start.sh and bin/stop.sh.
#
# The driver args are built once, as a shell array, from the TARGET_* variables the framework
# injects. A MySQL target that declares ignore-errors (TARGET_MYSQL_IGNORE_ERRORS) adds
# --mysql-ignore-errors; one that declares none adds nothing.
#
# A target with a password (TARGET_MYSQL_PASSWORD or TARGET_PG_PASSWORD) never puts it in a pod
# spec or on a kubectl command line. Each phase applies it, from stdin, to the Secret
# <kit>-credentials labelled role sysbench-credentials, and every pod that needs it reads it through
# env valueFrom.secretKeyRef: sysbench pods as TARGET_PASSWORD, which `sh -c` expands into
# --mysql-password or --pgsql-password as one argument, however many spaces, `*` or quotes it holds;
# the create-database pod as MYSQL_PWD. stop deletes the Secret by label. No phase prints the
# password. A target without one creates no Secret and runs exactly the pods it ran before.
#
# kubectl and curl are stubbed: kubectl records each call's arguments one per line, keeps what
# `kubectl apply` reads, and answers the calls each script makes. No cluster, no network.
#
# Run directly:  bash src/test/shell/sysbench-target-db.test.sh
# Or via gradle: ./gradlew testSysbenchTargetDb

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
KIT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/sysbench"
EDL_LIB="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/configuration/edl-kit-lib.sh"
PASSWORD="p w*'d \"x"
SECRET=sb1-credentials
SECRET_LABELS="easydblab/kit=sb1,easydblab/role=sysbench-credentials"

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
  mkdir -p "${WORK}/stubs" "${WORK}/kit/bin/lib" "${WORK}/calls" "${WORK}/applied" "${WORK}/fakebin"
  cp "${KIT}/bin/lib/target-db.sh" "${WORK}/kit/bin/lib/target-db.sh"
  for phase in prepare start stop; do
    cp "${KIT}/bin/${phase}.sh.template" "${WORK}/kit/bin/${phase}.sh"
  done
  cat > "${WORK}/stubs/kubectl" <<'EOF'
#!/usr/bin/env bash
W="${STUB_WORK:?}"
n=$(ls "${W}/calls" | wc -l | tr -d ' ')
printf '%s\n' "$@" > "${W}/calls/$(printf '%03d' "${n}").args"
case " $* " in
  *" get nodes "*) echo "node/app0" ;;
  # edl-kit-lib asks with --ignore-not-found for "<name>|" and the fields of its jsonpath; stop.sh
  # asks for the phase alone.
  *" get pod "*" --ignore-not-found "*exitCode*) printf '%s|%s|0' "$3" "${STUB_PHASE:-Succeeded}" ;;
  *" get pod "*" --ignore-not-found "*waiting.reason*) printf '%s|%s||' "$3" "${STUB_PHASE:-Succeeded}" ;;
  *" get pod "*" --ignore-not-found "*) printf '%s|%s' "$3" "${STUB_PHASE:-Succeeded}" ;;
  *" get pod "*) printf '%s' "${STUB_PHASE:-Succeeded}" ;;
  *" get events "*) echo "Warning  Failed  pod/stub  ErrImagePull" ;;
  *" logs "*) echo "[ 10s ] thds: 4 tps: 1.00 qps: 2.00 (r/w/o: 1/1/0) lat (ms,99%): 3.00 err/s: 0.00 reconn/s: 0.00" ;;
  # A stub render of the Secret: its name and each --from-file key with the content of its file.
  *" create secret generic "*)
    echo "name: $4"
    for a in "$@"; do
      case "${a}" in
        --from-file=*) spec="${a#--from-file=}"; echo "data: ${spec%%=*}=$(cat "${spec#*=}")" ;;
      esac
    done
    ;;
  *" label --local "*)
    cat
    for a in "$@"; do case "${a}" in easydblab/*) echo "label: ${a}" ;; esac; done
    ;;
  *" apply "*) cat > "${W}/applied/$(printf '%03d' "${n}").yaml" ;;
esac
exit 0
EOF
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/curl"
  printf '#!/usr/bin/env bash\nexit 0\n' > "${WORK}/stubs/sleep"
  # The container's sysbench: prints the arguments it received, one per line.
  printf '#!/bin/sh\nprintf "%%s\\n" "$@"\n' > "${WORK}/fakebin/sysbench"
  chmod +x "${WORK}/stubs/kubectl" "${WORK}/stubs/curl" "${WORK}/stubs/sleep" "${WORK}/fakebin/sysbench"
}

MYSQL_TARGET=(TARGET_MYSQL_HOST=10.0.0.9 TARGET_MYSQL_PORT=30306 TARGET_MYSQL_USER=root TARGET_MYSQL_DATABASE=sbtest)
PG_TARGET=(TARGET_PG_HOST=10.0.0.9 TARGET_PG_PORT=5432 TARGET_PG_USER=u TARGET_PG_DATABASE=d)

# Runs bin/$1.sh with the variables that follow, writing its output to out.txt.
run_phase() {
  local phase="$1"
  shift
  (
    cd "${WORK}" &&
      env PATH="${WORK}/stubs:${PATH}" STUB_WORK="${WORK}" EDL_KIT_LIB="${EDL_LIB}" EDL_POLL_SECONDS=0 KIT_NAME=sb1 KUBECONFIG="${WORK}/kubeconfig" CONTROL_HOST_PRIVATE=10.0.0.5 \
        WORKLOAD=oltp_read_write TABLES=2 SCALE=1 THREADS=4 DURATION=10 RATE=0 SKIP_TRX=off RAND_TYPE=special \
        "$@" "${BASH}" "${WORK}/kit/bin/${phase}.sh"
  ) > "${WORK}/out.txt" 2>&1
}

# The DB_ARGS array target_db_args builds from the variables given, one element per line.
db_args_for() {
  env -i PATH="${PATH}" "$@" "${BASH}" -c '
    set -euo pipefail
    source "$1"
    target_db_args
    printf "%s\n" "${DB_ARGS[@]}"
  ' _ "${KIT}/bin/lib/target-db.sh"
}

# The recorded kubectl call that ran a pod with role $1.
calls_for_role() {
  grep -l -- "--labels=easydblab/kit=sb1,easydblab/role=$1" "${WORK}"/calls/*.args 2>/dev/null
}

# The --overrides JSON of the recorded `kubectl run` call $1.
overrides_of() {
  sed -n 's/^--overrides=//p' "$1"
}

# The pod name of the recorded `kubectl run` call $1.
pod_of() {
  awk 'prev == "run" { print; exit } { prev = $0 }' "$1"
}

# Runs the container command of the recorded `kubectl run` call $1, as the container would, with
# TARGET_PASSWORD set to $2 and the stub sysbench on PATH. Prints the args sysbench received.
run_container_of() {
  local call="$1" password="$2" line cmd=()
  while IFS= read -r line; do
    cmd+=("${line}")
  done < <(awk 'started { print } prev == "--command" && $0 == "--" { started = 1 } { prev = $0 }' "${call}")
  env -i PATH="${WORK}/fakebin:/usr/bin:/bin" TARGET_PASSWORD="${password}" "${cmd[@]}"
}

# The kubectl calls whose arguments hold the password, if any.
calls_with_password() {
  grep -lF -- "p w*" "${WORK}"/calls/*.args 2>/dev/null
}

# The call number (file name) of the first recorded call matching the fixed line $1.
first_call_with() {
  grep -lxF -- "$1" "${WORK}"/calls/*.args 2>/dev/null | head -1 | xargs -n1 basename 2>/dev/null
}

# Checks pod call $1 reads env var $2 from the credentials Secret, in the container kubectl names
# after the pod, on an app node.
assert_reads_the_secret() {
  local call="$1" var="$2" overrides pod
  overrides="$(overrides_of "${call}")"
  pod="$(pod_of "${call}")"
  [ "$(jq -r '.spec.nodeSelector.type' <<< "${overrides}")" = app ] || fail "${pod} is not pinned to an app node: ${overrides}"
  [ "$(jq -r '.spec.containers[0].name' <<< "${overrides}")" = "${pod}" ] || fail "${pod}'s env is not on its container: ${overrides}"
  [ "$(jq -c '.spec.containers[0].env' <<< "${overrides}")" = "[{\"name\":\"${var}\",\"valueFrom\":{\"secretKeyRef\":{\"name\":\"${SECRET}\",\"key\":\"password\"}}}]" ] ||
    fail "${pod} does not read ${var} from the Secret: ${overrides}"
}

# Checks sysbench pod call $1 gives sysbench the password flag $2 as exactly one argument, with the
# rest of its args as they were.
assert_sysbench_gets_the_password() {
  local call="$1" flag="$2" received
  received="$(run_container_of "${call}" "${PASSWORD}")"
  grep -Fxq -- "${flag}=${PASSWORD}" <<< "${received}" || fail "sysbench did not get ${flag} as one argument: ${received}"
  [ "$(grep -cF -- "p w*" <<< "${received}")" -eq 1 ] || fail "the password reached sysbench split or twice: ${received}"
  grep -Fxq -- "--db-driver=${flag:2:5}" <<< "${received}" || fail "sysbench lost its driver args: ${received}"
}

# Checks that the one Secret applied holds the password and the credentials labels.
assert_secret_applied() {
  local applied
  applied="$(cat "${WORK}"/applied/*.yaml 2>/dev/null)"
  [ "$(ls "${WORK}"/applied | wc -l | tr -d ' ')" -ge 1 ] || { fail "no Secret was applied"; return; }
  grep -Fxq "name: ${SECRET}" <<< "${applied}" || fail "the Secret is not ${SECRET}: ${applied}"
  grep -Fxq "data: password=${PASSWORD}" <<< "${applied}" || fail "the Secret does not hold the password: ${applied}"
  grep -Fxq "label: easydblab/kit=sb1" <<< "${applied}" || fail "the Secret has no kit label: ${applied}"
  grep -Fxq "label: easydblab/role=sysbench-credentials" <<< "${applied}" || fail "the Secret has no role label: ${applied}"
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

test_the_driver_args_never_hold_a_password() {
  tests_run=$((tests_run + 1))
  local actual
  actual="$(db_args_for "${MYSQL_TARGET[@]}" "TARGET_MYSQL_PASSWORD=${PASSWORD}")"
  grep -qF -- "p w*" <<< "${actual}" && fail "the mysql driver args hold the password: ${actual}"
  grep -q -- "--mysql-password" <<< "${actual}" && fail "the mysql driver args hold a password flag: ${actual}"
  actual="$(db_args_for "${PG_TARGET[@]}" "TARGET_PG_PASSWORD=${PASSWORD}")"
  grep -qF -- "p w*" <<< "${actual}" && fail "the pgsql driver args hold the password: ${actual}"
  grep -q -- "--pgsql-password" <<< "${actual}" && fail "the pgsql driver args hold a password flag: ${actual}"
  return 0
}

test_a_mysql_target_with_ignore_errors_passes_them_to_sysbench() {
  tests_run=$((tests_run + 1))
  local actual
  actual="$(db_args_for "${MYSQL_TARGET[@]}" TARGET_MYSQL_IGNORE_ERRORS=1062,1213)"
  grep -Fxq -- "--mysql-ignore-errors=1062,1213" <<< "${actual}" || fail "no --mysql-ignore-errors arg: ${actual}"
}

test_a_mysql_target_without_ignore_errors_passes_none() {
  tests_run=$((tests_run + 1))
  local actual
  actual="$(db_args_for "${MYSQL_TARGET[@]}" TARGET_MYSQL_IGNORE_ERRORS=)"
  grep -q -- "--mysql-ignore-errors" <<< "${actual}" && fail "an empty ignore list passed --mysql-ignore-errors: ${actual}"
  return 0
}

test_no_endpoint_is_reported() {
  tests_run=$((tests_run + 1))
  db_args_for > /dev/null 2>&1 && fail "target_db_args succeeded with no TARGET_* endpoint"
}

test_prepare_reads_the_password_from_a_secret_in_every_pod() {
  tests_run=$((tests_run + 1))
  setup
  run_phase prepare "${MYSQL_TARGET[@]}" "TARGET_MYSQL_PASSWORD=${PASSWORD}" ||
    { fail "prepare exited non-zero: $(cat "${WORK}/out.txt")"; return; }

  assert_secret_applied
  local leaked
  leaked="$(calls_with_password)"
  [ -z "${leaked}" ] || fail "the password is in a kubectl call: $(cat ${leaked})"

  local createdb
  createdb="$(calls_for_role sysbench-createdb)"
  [ -n "${createdb}" ] || { fail "no create-database pod was run"; return; }
  assert_reads_the_secret "${createdb}" MYSQL_PWD
  grep -q -- '^-p' "${createdb}" && fail "the create-database pod passes a password flag: $(cat "${createdb}")"
  local role call
  for role in sysbench-cleanup sysbench-prepare; do
    call="$(calls_for_role "${role}")"
    [ -n "${call}" ] || { fail "no ${role} pod was run"; continue; }
    assert_reads_the_secret "${call}" TARGET_PASSWORD
    assert_sysbench_gets_the_password "${call}" --mysql-password
  done
  [ "$(first_call_with apply)" \< "$(basename "${createdb}")" ] || fail "the Secret was not applied before the first pod"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "prepare printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_prepare_of_a_passwordless_target_creates_no_secret_and_runs_plain_pods() {
  tests_run=$((tests_run + 1))
  setup
  run_phase prepare "${MYSQL_TARGET[@]}" TARGET_MYSQL_PASSWORD= ||
    { fail "prepare exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -lq -- "MYSQL_PWD\|password\|secret" "${WORK}"/calls/*.args && fail "a passwordless target got a password: $(cat "${WORK}"/calls/*.args)"
  [ -z "$(ls "${WORK}/applied")" ] || fail "a passwordless target applied a Secret"
  local role call
  for role in sysbench-cleanup sysbench-prepare; do
    call="$(calls_for_role "${role}")"
    [ "$(overrides_of "${call}")" = '{"spec":{"nodeSelector":{"type":"app"}}}' ] || fail "the ${role} overrides changed: $(overrides_of "${call}")"
    [ "$(awk 'started { print; exit } prev == "--command" && $0 == "--" { started = 1 } { prev = $0 }' "${call}")" = sysbench ] ||
      fail "the ${role} pod does not run sysbench directly: $(cat "${call}")"
  done
}

test_start_reads_the_password_from_the_secret() {
  tests_run=$((tests_run + 1))
  setup
  run_phase start "${MYSQL_TARGET[@]}" "TARGET_MYSQL_PASSWORD=${PASSWORD}" ||
    { fail "start exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  assert_secret_applied
  local leaked run received
  leaked="$(calls_with_password)"
  [ -z "${leaked}" ] || fail "the password is in a kubectl call: $(cat ${leaked})"
  run="$(calls_for_role sysbench-run)"
  [ -n "${run}" ] || { fail "no run pod was started"; return; }
  assert_reads_the_secret "${run}" TARGET_PASSWORD
  assert_sysbench_gets_the_password "${run}" --mysql-password
  received="$(run_container_of "${run}" "${PASSWORD}")"
  grep -Fxq -- "--threads=4" <<< "${received}" || fail "the run lost its own args: ${received}"
  [ "$(first_call_with apply)" \< "$(basename "${run}")" ] || fail "the Secret was not applied before the run pod"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "start printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_stop_reads_the_password_from_the_secret_and_deletes_it() {
  tests_run=$((tests_run + 1))
  setup
  run_phase stop "${PG_TARGET[@]}" "TARGET_PG_PASSWORD=${PASSWORD}" ||
    { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  assert_secret_applied
  local leaked cleanup deleted
  leaked="$(calls_with_password)"
  [ -z "${leaked}" ] || fail "the password is in a kubectl call: $(cat ${leaked})"
  cleanup="$(calls_for_role sysbench-cleanup)"
  [ -n "${cleanup}" ] || { fail "no cleanup pod was run"; return; }
  assert_reads_the_secret "${cleanup}" TARGET_PASSWORD
  assert_sysbench_gets_the_password "${cleanup}" --pgsql-password
  deleted="$(grep -lxF -- "secret" "${WORK}"/calls/*.args | xargs grep -lxF -- delete | xargs grep -lxF -- "${SECRET_LABELS}" 2>/dev/null | head -1)"
  [ -n "${deleted}" ] || { fail "stop did not delete the Secret by label: $(cat "${WORK}"/calls/*.args)"; return; }
  [ "$(basename "${cleanup}")" \< "$(basename "${deleted}")" ] || fail "the Secret was deleted before the cleanup pod ran"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "stop printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_stop_deletes_the_secret_when_the_target_is_gone() {
  tests_run=$((tests_run + 1))
  setup
  run_phase stop || { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -lxF -- "secret" "${WORK}"/calls/*.args | xargs grep -lxF -- "${SECRET_LABELS}" > /dev/null 2>&1 ||
    fail "stop with no endpoint left the Secret: $(cat "${WORK}"/calls/*.args)"
}

test_stop_of_a_passwordless_target_applies_no_secret() {
  tests_run=$((tests_run + 1))
  setup
  run_phase stop "${PG_TARGET[@]}" || { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  [ -z "$(ls "${WORK}/applied")" ] || fail "a passwordless target applied a Secret"
  local cleanup
  cleanup="$(calls_for_role sysbench-cleanup)"
  [ "$(overrides_of "${cleanup}")" = '{"spec":{"nodeSelector":{"type":"app"}}}' ] || fail "the cleanup overrides changed: $(overrides_of "${cleanup}")"
}

test_a_cleanup_pod_that_never_starts_lists_its_events_not_its_description() {
  tests_run=$((tests_run + 1))
  setup
  STUB_PHASE=Pending run_phase stop "${PG_TARGET[@]}" "TARGET_PG_PASSWORD=${PASSWORD}" ||
    { fail "stop exited non-zero: $(cat "${WORK}/out.txt")"; return; }
  grep -lqx -- "describe" "${WORK}"/calls/*.args && fail "stop ran kubectl describe"
  grep -q "ErrImagePull" "${WORK}/out.txt" || fail "stop did not show the pending pod's events: $(cat "${WORK}/out.txt")"
  grep -qF -- "p w*" "${WORK}/out.txt" && fail "stop printed the password: $(cat "${WORK}/out.txt")"
  return 0
}

test_a_passwordless_mysql_target_gets_the_args_it_always_got
test_a_passwordless_pgsql_target_gets_the_args_it_always_got
test_the_driver_args_never_hold_a_password
test_a_mysql_target_with_ignore_errors_passes_them_to_sysbench
test_a_mysql_target_without_ignore_errors_passes_none
test_no_endpoint_is_reported
test_prepare_reads_the_password_from_a_secret_in_every_pod
test_prepare_of_a_passwordless_target_creates_no_secret_and_runs_plain_pods
test_start_reads_the_password_from_the_secret
test_stop_reads_the_password_from_the_secret_and_deletes_it
test_stop_deletes_the_secret_when_the_target_is_gone
test_stop_of_a_passwordless_target_applies_no_secret
test_a_cleanup_pod_that_never_starts_lists_its_events_not_its_description

echo "sysbench target-db (${BASH_VERSION}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
