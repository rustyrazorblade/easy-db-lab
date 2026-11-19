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
# out.txt and its stderr to err.txt, and its exit status to ${detector_status}. ROOT_HOST overrides
# the host path of the root (default /mnt) and DATA the pod path of the served dir (default the
# root's db1 data dir). Only an unreadable data dir may fail the pod, so the layout tests use
# run_detector, which fails the test unless the detector exits 0.
run_detector_status() {
  detector_status=0
  env DETECT_SERVED="${1:-/mnt/db1/cassandra/data}" DETECT_ROOT_HOST="${ROOT_HOST:-/mnt}" DETECT_ROOT="${WORK}/root" \
    DETECT_DATA="${DATA:-${WORK}/root/db1/cassandra/data}" DETECT_DATA_GID=999 \
    "${TEST_SH}" "${SCRIPT}" > "${WORK}/out.txt" 2> "${WORK}/err.txt" || detector_status=$?
}

run_detector() {
  run_detector_status "$@"
  [ "${detector_status}" -eq 0 ] || fail "exited ${detector_status}, want 0: $(cat "${WORK}/err.txt")"
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

test_doubled_slashes_in_the_served_dir_and_the_root_still_match() {
  start_test "doubled slashes"
  disk db1
  ROOT_HOST=//mnt// run_detector //mnt//db1/cassandra//data
  grep -Fq "OK" "${WORK}/out.txt" || fail "no OK line: $(cat "${WORK}/out.txt") $(cat "${WORK}/err.txt")"
}

# A symlink to a disk is the same disk: it must not count as a second data dir.
test_a_symlinked_alias_of_the_served_disk_is_one_disk() {
  start_test "symlinked alias of the served disk"
  disk db1 nb-1-big-Data.db
  ln -s db1 "${WORK}/root/data"
  run_detector
  grep -Fq "OK" "${WORK}/out.txt" || fail "no OK line: $(cat "${WORK}/out.txt") $(cat "${WORK}/err.txt")"
  [ ! -s "${WORK}/err.txt" ] || fail "warned about an alias of the served disk: $(cat "${WORK}/err.txt")"
}

# Serving a disk through a symlink matches the disk the link resolves to.
test_a_served_dir_reached_through_a_symlink_matches_its_disk() {
  start_test "served dir through a symlink"
  disk db1
  ln -s db1 "${WORK}/root/current"
  run_detector /mnt/current/cassandra/data
  grep -Fq "OK" "${WORK}/out.txt" || fail "no OK line: $(cat "${WORK}/out.txt") $(cat "${WORK}/err.txt")"
}

# With a symlink to the served disk and a real second disk, the second disk is still named.
test_a_second_real_disk_is_named_despite_an_alias_of_the_served_one() {
  start_test "alias plus a second disk"
  disk db1
  disk db2
  ln -s db1 "${WORK}/root/current"
  run_detector /mnt/current/cassandra/data
  grep -Fq "2 data dirs" "${WORK}/err.txt" || fail "the alias was counted as a disk: $(cat "${WORK}/err.txt")"
  grep -Fq "/mnt/db1/cassandra/data   <-- SERVED" "${WORK}/err.txt" || fail "served disk not marked: $(cat "${WORK}/err.txt")"
  grep -Fq "/mnt/db2/cassandra/data   (NOT served" "${WORK}/err.txt" || fail "second disk not named: $(cat "${WORK}/err.txt")"
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

# The flight process reads the served dir as uid 10001 with --data-gid as a supplementary group,
# and so does this init container. When it cannot list the dir or a table dir in it, every read
# would fail, so the pod must not start, and the message must name --data-gid.
test_an_unreadable_data_dir_fails_the_pod_naming_data_gid() {
  local layer
  for layer in data-dir table-dir; do
    start_test "unreadable ${layer}"
    disk db1
    mkdir -p "${WORK}/root/db1/cassandra/data/ks1/t1-abc"
    case "${layer}" in
      data-dir) chmod 000 "${WORK}/root/db1/cassandra/data" ;;
      table-dir) chmod 000 "${WORK}/root/db1/cassandra/data/ks1" ;;
    esac
    run_detector_status
    chmod -R u+rwx "${WORK}/root"
    [ "${detector_status}" -ne 0 ] || fail "exited 0 on an unreadable ${layer}"
    grep -Fq -- "--data-gid (999)" "${WORK}/err.txt" || fail "does not name --data-gid: $(cat "${WORK}/err.txt")"
    grep -Fq -- "/mnt/db1/cassandra/data" "${WORK}/err.txt" || fail "does not name the host dir: $(cat "${WORK}/err.txt")"
  done
}

test_a_readable_data_dir_with_table_dirs_passes_the_access_check() {
  start_test "readable table dirs"
  disk db1
  mkdir -p "${WORK}/root/db1/cassandra/data/ks1/t1-abc"
  run_detector
  grep -Fq "access check: passed" "${WORK}/out.txt" || fail "no access line: $(cat "${WORK}/out.txt") $(cat "${WORK}/err.txt")"
}

test_an_unreadable_data_dir_fails_the_pod_naming_data_gid
test_a_readable_data_dir_with_table_dirs_passes_the_access_check
test_one_data_dir_that_is_served_is_ok
test_a_trailing_slash_on_the_served_dir_still_matches
test_doubled_slashes_in_the_served_dir_and_the_root_still_match
test_a_symlinked_alias_of_the_served_disk_is_one_disk
test_a_served_dir_reached_through_a_symlink_matches_its_disk
test_a_second_real_disk_is_named_despite_an_alias_of_the_served_one
test_two_data_dirs_warn_and_name_the_unserved_one
test_layouts_it_cannot_check_warn_that_it_cannot_verify

echo "cqlite-flight detect-multidisk (${TEST_SH}): ${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
