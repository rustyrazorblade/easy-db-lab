#!/bin/sh
#
# Unit tests for edl-primary-eni-network: the boot script that writes the primary ENI's networkd
# file from the MAC in cloud-init's instance data.
#
# Every case gets its own temp dir. The script reads its input file, its /run network dir and its
# /etc network dir from EDL_INSTANCE_DATA, EDL_RUN_NETWORK_DIR and EDL_ETC_NETWORK_DIR, so this
# needs no root, no cloud-init and no Docker. It needs jq on PATH.
#
# Run directly:  sh ./edl-primary-eni-network.test.sh
# Or via gradle: ./gradlew testBasePrimaryEniNetwork

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${SCRIPT_DIR}/edl-primary-eni-network"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

tests_run=0
tests_failed=0
case_number=0

pass() {
    tests_run=$((tests_run + 1))
    echo "ok   - $1"
}

fail() {
    tests_run=$((tests_run + 1))
    tests_failed=$((tests_failed + 1))
    echo "FAIL - $1"
}

check() {
    # check <description> <command...>: passes when the command succeeds.
    description="$1"
    shift
    if "$@"; then pass "$description"; else fail "$description"; fi
}

# Makes a fresh case dir and sets CASE, DATA, RUN_DIR and ETC_DIR.
new_case() {
    case_number=$((case_number + 1))
    CASE="${SANDBOX}/case${case_number}"
    DATA="${CASE}/instance-data.json"
    RUN_DIR="${CASE}/run/systemd/network"
    ETC_DIR="${CASE}/etc/systemd/network"
    mkdir -p "$CASE" "$ETC_DIR"
}

# Writes instance data whose .ds.meta_data.mac is the given raw JSON value.
write_data() {
    printf '{"ds": {"meta_data": {"mac": %s, "instance-id": "i-0123456789abcdef0"}}}\n' "$1" >"$DATA"
}

# Runs the script against the current case; sets OUTPUT and STATUS.
run_script() {
    OUTPUT="$(EDL_INSTANCE_DATA="$DATA" EDL_RUN_NETWORK_DIR="$RUN_DIR" EDL_ETC_NETWORK_DIR="$ETC_DIR" \
        sh "$SCRIPT" 2>&1)"
    STATUS=$?
}

primary_file() {
    echo "${RUN_DIR}/05-cilium-eni-primary.network"
}

line_count() {
    printf '%s\n' "$OUTPUT" | grep -c .
}

no_primary_file() {
    [ ! -e "$(primary_file)" ]
}

no_temp_files() {
    [ ! -d "$RUN_DIR" ] || [ -z "$(find "$RUN_DIR" -name '.05-*' -print)" ]
}

# Asserts the failure contract: exit 1, one output line that contains $1, no 05 file.
expect_failure() {
    name="$1"
    expected="$2"
    check "${name}: exits 1" [ "$STATUS" -eq 1 ]
    check "${name}: prints one line" [ "$(line_count)" -eq 1 ]
    check "${name}: names the cause (${expected})" sh -c 'printf "%s" "$1" | grep -qF -- "$2"' _ "$OUTPUT" "$expected"
    check "${name}: writes no 05 file" no_primary_file
}

# --- A valid MAC writes the match and DHCP lines ---
new_case
write_data '"0a:1b:2c:3d:4e:5f"'
run_script
check "valid MAC: exits 0" [ "$STATUS" -eq 0 ]
check "valid MAC: writes the 05 file" [ -f "$(primary_file)" ]
check "valid MAC: matches the permanent MAC" grep -qx 'PermanentMACAddress=0a:1b:2c:3d:4e:5f' "$(primary_file)"
check "valid MAC: has a [Match] section" grep -qx '\[Match\]' "$(primary_file)"
check "valid MAC: has a [Network] section" grep -qx '\[Network\]' "$(primary_file)"
check "valid MAC: enables DHCP" grep -qx 'DHCP=ipv4' "$(primary_file)"
check "valid MAC: does not match by name" sh -c '! grep -q "^Name=" "$1"' _ "$(primary_file)"
check "valid MAC: leaves no temp file" no_temp_files

# --- An uppercase MAC is lowercased ---
new_case
write_data '"0A:1B:2C:3D:4E:5F"'
run_script
check "uppercase MAC: exits 0" [ "$STATUS" -eq 0 ]
check "uppercase MAC: is lowercased" grep -qx 'PermanentMACAddress=0a:1b:2c:3d:4e:5f' "$(primary_file)"

# --- A missing instance-data.json fails loudly ---
new_case
run_script
expect_failure "missing instance data" "$DATA"

# --- An unreadable (not JSON) instance-data.json fails loudly ---
new_case
echo 'not json {' >"$DATA"
run_script
expect_failure "malformed instance data" "$DATA"

# --- A null MAC fails loudly ---
new_case
write_data 'null'
run_script
expect_failure "null MAC" ".ds.meta_data.mac"

# --- A missing MAC key fails loudly ---
new_case
echo '{"ds": {"meta_data": {"instance-id": "i-0123456789abcdef0"}}}' >"$DATA"
run_script
expect_failure "missing MAC key" ".ds.meta_data.mac"

# --- An empty MAC fails loudly ---
new_case
write_data '""'
run_script
expect_failure "empty MAC" ".ds.meta_data.mac"

# --- Malformed MACs fail loudly ---
for bad in '0a:1b:2c:3d:4e' '0a:1b:2c:3d:4e:5f:60' '0a-1b-2c-3d-4e-5f' '0a:1b:2c:3d:4e:zz' '0a1b2c3d4e5f' \
    '0a:1b:2c:3d:4e:5f ' 'a:1b:2c:3d:4e:5f'; do
    new_case
    write_data "\"${bad}\""
    run_script
    expect_failure "malformed MAC '${bad}'" "$bad"
done

# --- A MAC with a trailing newline (a second line) is malformed ---
new_case
write_data '"0a:1b:2c:3d:4e:5f\n0a:1b:2c:3d:4e:60"'
run_script
check "two-line MAC: exits 1" [ "$STATUS" -eq 1 ]
check "two-line MAC: writes no 05 file" no_primary_file

# --- A numeric MAC (not a string) is malformed ---
new_case
write_data '42'
run_script
expect_failure "numeric MAC" "42"

# --- An /etc 05 file shadows the /run one, so the script refuses ---
new_case
write_data '"0a:1b:2c:3d:4e:5f"'
printf '[Match]\nName=ens5\n' >"${ETC_DIR}/05-cilium-eni-primary.network"
run_script
expect_failure "/etc 05 file present" "${ETC_DIR}/05-cilium-eni-primary.network"

# --- A stale /run 05 file with another MAC is replaced ---
new_case
write_data '"0a:1b:2c:3d:4e:5f"'
mkdir -p "$RUN_DIR"
printf '[Match]\nPermanentMACAddress=aa:bb:cc:dd:ee:ff\n\n[Network]\nDHCP=ipv4\n' >"$(primary_file)"
run_script
check "stale 05 file: exits 0" [ "$STATUS" -eq 0 ]
check "stale 05 file: holds the new MAC" grep -qx 'PermanentMACAddress=0a:1b:2c:3d:4e:5f' "$(primary_file)"
check "stale 05 file: drops the old MAC" sh -c '! grep -q "aa:bb:cc:dd:ee:ff" "$1"' _ "$(primary_file)"
check "stale 05 file: holds one match line" [ "$(grep -c '^PermanentMACAddress=' "$(primary_file)")" -eq 1 ]

# --- A failed run leaves no 05 file, not even a stale one from earlier in the same boot (design D4:
# the script never leaves a stale or wrong 05 file in place). ---
new_case
write_data 'null'
mkdir -p "$RUN_DIR"
printf '[Match]\nPermanentMACAddress=aa:bb:cc:dd:ee:ff\n' >"$(primary_file)"
run_script
check "failure with a stale 05 file: exits 1" [ "$STATUS" -eq 1 ]
check "failure with a stale 05 file: removes it" no_primary_file

echo
echo "${tests_run} tests, ${tests_failed} failed"
[ "$tests_failed" -eq 0 ]
