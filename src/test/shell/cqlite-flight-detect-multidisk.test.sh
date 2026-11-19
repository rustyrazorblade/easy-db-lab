#!/usr/bin/env bash
#
# Unit tests for the cqlite-flight kit's pod/detect-multidisk.sh, the init container that warns
# when a db node's data may span disks cqlite-flight cannot serve (pmcfadin/cqlite#2114).
#
# The script scans a data root the pod mounts read-only (DETECT_ROOT) for <root>/*/cassandra/data
# dirs. Each test builds that root under a temporary directory. It runs under dash when dash is
# installed: the image runs it with /bin/sh, so a bashism must fail here.
#
# Run directly:  bash src/test/shell/cqlite-flight-detect-multidisk.test.sh
# Or via gradle: ./gradlew testCqliteFlightDetectMultidisk

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cqlite-flight/pod/detect-multidisk.sh"
TEST_SH="$(command -v dash || echo sh)"

tests_run=0
tests_failed=0
current_test=""
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: ${current_test}: $1"
  tests_failed=$((tests_failed + 1))
}

# Starts a case with an empty data root, which the node knows as /mnt.
start_test() {
  current_test="$1"
  tests_run=$((tests_run + 1))
  rm -rf "${WORK}/root"
  mkdir -p "${WORK}/root"
}

# Makes the data dir of disk $1 under the root, holding file $2 when given.
disk() {
  mkdir -p "${WORK}/root/$1/cassandra/data"
  [ -z "${2:-}" ] || touch "${WORK}/root/$1/cassandra/data/$2"
}

# Runs the detector serving host path $1 (default /mnt/db1/cassandra/data). Its stdout goes to
# out.txt and its stderr to err.txt; it fails the test unless the detector exits 0, since the
# detector must never block the rollout.
run_detector() {
  local status=0
  env DETECT_SERVED="${1:-/mnt/db1/cassandra/data}" DETECT_ROOT_HOST=/mnt DETECT_ROOT="${WORK}/root" \
    "${TEST_SH}" "${SCRIPT}" > "${WORK}/out.txt" 2> "${WORK}/err.txt" || status=$?
  [ "${status}" -eq 0 ] || fail "exited ${status}, want 0: $(cat "${WORK}/err.txt")"
}

test_one_data_dir_that_is_served_is_ok() {
  start_test "single served disk"
  disk db1 nb-1-big-Data.db
  run_detector
  grep -Fq "OK" "${WORK}/out.txt" || fail "no OK line: $(cat "${WORK}/out.txt")"
  [ ! -s "${WORK}/err.txt" ] || fail "warned on a verified single disk: $(cat "${WORK}/err.txt")"
}

test_a_trailing_slash_on_the_served_dir_still_matches() {
  start_test "trailing slash"
  disk db1
  run_detector /mnt/db1/cassandra/data/
  grep -Fq "OK" "${WORK}/out.txt" || fail "no OK line: $(cat "${WORK}/out.txt") $(cat "${WORK}/err.txt")"
}

test_two_data_dirs_warn_and_name_the_unserved_one() {
  start_test "multi-disk"
  disk db1 nb-1-big-Data.db
  disk db2
  run_detector
  grep -Fq "MULTIPLE" "${WORK}/err.txt" || fail "no multi-disk warning: $(cat "${WORK}/err.txt")"
  grep -Fq "/mnt/db1/cassandra/data   <-- SERVED" "${WORK}/err.txt" || fail "served dir not marked: $(cat "${WORK}/err.txt")"
  grep -Fq "/mnt/db2/cassandra/data   (NOT served" "${WORK}/err.txt" ||
    fail "the empty second disk was not listed as unserved: $(cat "${WORK}/err.txt")"
  if grep -Fq "OK" "${WORK}/out.txt"; then fail "reported OK on a multi-disk node"; fi
}

# Every layout the detector cannot check prints the same warning, never an OK.
test_layouts_it_cannot_check_warn_that_it_cannot_verify() {
  local layout
  for layout in empty-root other-layout lone-unserved-dir; do
    start_test "cannot verify: ${layout}"
    case "${layout}" in
      other-layout) mkdir -p "${WORK}/root/data/cassandra" ;;
      lone-unserved-dir) disk db7 nb-1-big-Data.db ;;
    esac
    run_detector
    grep -Fq "CANNOT VERIFY" "${WORK}/err.txt" || fail "no cannot-verify warning: $(cat "${WORK}/err.txt")"
    grep -Fq -- "--data-root (/mnt)" "${WORK}/err.txt" || fail "does not name the root: $(cat "${WORK}/err.txt")"
    if grep -Fq "OK" "${WORK}/out.txt"; then fail "reported OK: $(cat "${WORK}/out.txt")"; fi
  done
}

test_one_data_dir_that_is_served_is_ok
test_a_trailing_slash_on_the_served_dir_still_matches
test_two_data_dirs_warn_and_name_the_unserved_one
test_layouts_it_cannot_check_warn_that_it_cannot_verify

echo "cqlite-flight detect-multidisk (${TEST_SH}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
