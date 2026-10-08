#!/usr/bin/env bash
#
# Unit tests for the data-disk handling in setup_instance.sh (instance-storage-validation: "Data
# disk mounted at up time"). Setup finds the node's unused non-root disk under any device name,
# formats it when it has no file system, mounts it at /mnt/db1, and exits non-zero naming the
# reason when there is no data disk or the mount does not take. It never falls back to a plain
# directory on the root volume.
#
# The script is sourced, which defines its functions without running setup. sudo, lsblk, findmnt,
# mountpoint, blkid, mkfs.xfs, mount, mkdir, sed, tee, systemctl and blockdev are stubbed on PATH
# and keep the mount state in a file; yq is the real one. No root, no disks, no Docker.
#
# Run directly:  bash src/test/shell/setup-instance-data-disk.test.sh
# Or via gradle: ./gradlew testSetupInstanceDataDisk

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/commands/setup_instance.sh"

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

pass() {
  tests_run=$((tests_run + 1))
  echo "ok   - $1"
}

fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  echo "FAIL - $1"
}

# A Nitro node: nvme0n1 is the partitioned root disk, the rest are whatever the case adds.
lsblk_json() {
  local extra="$1"
  cat <<EOF
{"blockdevices": [
  {"name": "loop0", "type": "loop", "mountpoints": ["/snap/core/1"]},
  {"name": "nvme0n1", "type": "disk", "mountpoints": [null],
   "children": [{"name": "nvme0n1p1", "type": "part", "mountpoints": ["/"]},
                {"name": "nvme0n1p15", "type": "part", "mountpoints": ["/boot/efi"]}]}${extra}
]}
EOF
}

# Writes the stubs. MOUNT_FAILS=1 makes mount exit non-zero; MOUNT_ON_ROOT=1 makes the mount
# "succeed" while /mnt/db1 still resolves to the root partition.
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/bin"
  echo "$1" > "${WORK}/lsblk.json"
  : > "${WORK}/calls.log"
  : > "${WORK}/mounted"

  cat > "${WORK}/bin/sudo" <<'EOF'
#!/bin/bash
exec "$@"
EOF
  cat > "${WORK}/bin/lsblk" <<EOF
#!/bin/bash
case " \$* " in
  *" -J "*) cat "${WORK}/lsblk.json" ;;
  *" PKNAME "*)
    case "\$*" in
      *nvme0n1p1*) echo nvme0n1 ;;
    esac ;;
  *) echo "nvme0n1 disk" ;;
esac
EOF
  cat > "${WORK}/bin/findmnt" <<EOF
#!/bin/bash
target="\${!#}"
if [ "\$target" = "/" ]; then echo /dev/nvme0n1p1; exit 0; fi
dev=\$(cat "${WORK}/mounted")
[ -n "\$dev" ] && echo "\$dev"
EOF
  cat > "${WORK}/bin/mountpoint" <<EOF
#!/bin/bash
[ -s "${WORK}/mounted" ]
EOF
  cat > "${WORK}/bin/mount" <<EOF
#!/bin/bash
echo "mount \$*" >> "${WORK}/calls.log"
[ "\${MOUNT_FAILS:-0}" = 1 ] && exit 32
if [ "\${MOUNT_ON_ROOT:-0}" = 1 ]; then echo /dev/nvme0n1p1 > "${WORK}/mounted"; else echo "\$1" > "${WORK}/mounted"; fi
EOF
  cat > "${WORK}/bin/blkid" <<EOF
#!/bin/bash
case " \$* " in
  *" UUID "*) echo 1111-2222 ;;
  *) echo "\${FS_TYPE:-}" ;;
esac
EOF
  for tool in mkfs.xfs mkdir sed tee systemctl blockdev; do
    cat > "${WORK}/bin/${tool}" <<EOF
#!/bin/bash
echo "${tool} \$*" >> "${WORK}/calls.log"
cat > /dev/null 2>&1 || true
EOF
  done
  chmod +x "${WORK}"/bin/*
}

# Sources the script and runs mount_data_disk in a subshell; prints its output, returns its status.
run_mount() {
  (
    export PATH="${WORK}/bin:${PATH}"
    # shellcheck disable=SC1090
    source "${SCRIPT}"
    mount_data_disk
  ) 2>&1 < /dev/null
}

DATA_DISK=',
  {"name": "nvme1n1", "type": "disk", "mountpoints": [null]}'

# --- a data disk is found, formatted and mounted ---
setup "$(lsblk_json "${DATA_DISK}")"
OUTPUT="$(run_mount)"
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && grep -q '^mount /dev/nvme1n1 /mnt/db1$' "${WORK}/calls.log"; then
  pass "an unused disk is mounted at /mnt/db1"
else
  fail "expected nvme1n1 mounted at /mnt/db1, got ${STATUS}: ${OUTPUT} / $(cat "${WORK}/calls.log")"
fi
if grep -q '^mkfs.xfs /dev/nvme1n1$' "${WORK}/calls.log"; then
  pass "a disk with no file system is formatted with XFS"
else
  fail "expected mkfs.xfs /dev/nvme1n1, got: $(cat "${WORK}/calls.log")"
fi
if grep -q '^tee -a /etc/fstab$' "${WORK}/calls.log"; then
  pass "the mount is persisted in /etc/fstab"
else
  fail "expected an fstab entry, got: $(cat "${WORK}/calls.log")"
fi

# --- a disk that already has a file system is not formatted ---
setup "$(lsblk_json "${DATA_DISK}")"
OUTPUT="$(FS_TYPE=xfs run_mount)"
if ! grep -q '^mkfs.xfs' "${WORK}/calls.log"; then
  pass "a disk that has a file system is not formatted"
else
  fail "expected no mkfs, got: $(cat "${WORK}/calls.log")"
fi

# --- a data disk named outside the first three device names ---
setup "$(lsblk_json ',
  {"name": "nvme1n1", "type": "disk", "mountpoints": ["/var/lib/other"]},
  {"name": "nvme2n1", "type": "disk", "mountpoints": [null]}')"
OUTPUT="$(run_mount)"
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && grep -q '^mount /dev/nvme2n1 /mnt/db1$' "${WORK}/calls.log"; then
  pass "a data disk named nvme2n1 is found and a disk in use is skipped"
else
  fail "expected nvme2n1 mounted, got ${STATUS}: ${OUTPUT} / $(cat "${WORK}/calls.log")"
fi

# --- no data disk: fail, and do not create /mnt/db1 on root ---
setup "$(lsblk_json '')"
OUTPUT="$(run_mount)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: no data disk found"* ]]; then
  pass "no data disk fails, naming the reason"
else
  fail "no data disk should fail with 'ERROR: no data disk found', got ${STATUS}: ${OUTPUT}"
fi
if ! grep -q -e '^mkdir' -e '^mount' "${WORK}/calls.log"; then
  pass "no data disk creates and mounts nothing"
else
  fail "no data disk should create nothing, got: $(cat "${WORK}/calls.log")"
fi

# --- the mount fails ---
setup "$(lsblk_json "${DATA_DISK}")"
OUTPUT="$(MOUNT_FAILS=1 run_mount)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: mounting /dev/nvme1n1 at /mnt/db1 failed"* ]]; then
  pass "a failed mount fails, naming the disk"
else
  fail "a failed mount should fail naming the disk, got ${STATUS}: ${OUTPUT}"
fi
if ! grep -q '^tee -a /etc/fstab$' "${WORK}/calls.log"; then
  pass "a failed mount writes no fstab entry"
else
  fail "a failed mount should write no fstab entry"
fi

# --- /mnt/db1 resolves to the root volume after the mount ---
setup "$(lsblk_json "${DATA_DISK}")"
OUTPUT="$(MOUNT_ON_ROOT=1 run_mount)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: /mnt/db1 is mounted from /dev/nvme0n1p1, which is on the root volume"* ]]; then
  pass "/mnt/db1 on the root volume fails"
else
  fail "/mnt/db1 on the root volume should fail, got ${STATUS}: ${OUTPUT}"
fi

# --- a re-run on a node whose data disk is already mounted ---
setup "$(lsblk_json ',
  {"name": "nvme1n1", "type": "disk", "mountpoints": ["/mnt/db1"]}')"
echo /dev/nvme1n1 > "${WORK}/mounted"
OUTPUT="$(run_mount)"
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && ! grep -q -e '^mount' -e '^mkfs' "${WORK}/calls.log"; then
  pass "a re-run keeps the mounted data disk"
else
  fail "a re-run should keep the mount, got ${STATUS}: ${OUTPUT} / $(cat "${WORK}/calls.log")"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
