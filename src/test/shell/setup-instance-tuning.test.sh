#!/usr/bin/env bash
#
# Unit tests for the OS tuning step of setup_instance.sh (node-os-tuning): it installs every
# packaged tuning file where the system reads it, removes the files earlier setups wrote, keeps
# swap off across a reboot, and fails naming the reason when the files are missing or a setting
# did not take.
#
# The script is sourced, which defines its functions without running setup. sudo, install, rm,
# tee, sysctl, swapoff, udevadm, journalctl and systemctl are stubbed on PATH and log their
# calls; edl-node-tuning verify is the stub's exit status, and the fstab is a temporary file the
# real swap edit runs on. SYSCTL_FAILS, RESTART_FAILS and VERIFY_FAILS make that step fail. No
# root, no Docker.
#
# Run directly:  bash src/test/shell/setup-instance-tuning.test.sh
# Or via gradle: ./gradlew testSetupInstanceTuning

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMMANDS="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/commands"

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

# A setup directory as node setup uploads it: the script, and node-tuning/ beside it unless
# [$1] is "no-tuning".
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/bin" "${WORK}/home"
  cp "${COMMANDS}/setup_instance.sh" "${WORK}/home/"
  [ "${1:-}" = no-tuning ] || cp -R "${COMMANDS}/node-tuning" "${WORK}/home/"
  : > "${WORK}/calls.log"

  cat > "${WORK}/bin/sudo" <<EOF
#!/bin/bash
if [ "\$1" = /usr/local/sbin/edl-node-tuning ]; then
  echo "edl-node-tuning \$2" >> "${WORK}/calls.log"
  [ "\${VERIFY_FAILS:-0}" = 1 ] && { echo "ERROR: vm.swappiness: wanted 1, got 60"; exit 1; }
  exit 0
fi
exec "\$@"
EOF
  for tool in rm swapoff udevadm; do
    cat > "${WORK}/bin/${tool}" <<EOF
#!/bin/bash
echo "${tool} \$*" >> "${WORK}/calls.log"
EOF
  done
  # sysctl -p names a key the kernel rejects on stderr, and exits non-zero, as the real one does.
  cat > "${WORK}/bin/sysctl" <<EOF
#!/bin/bash
echo "sysctl \$*" >> "${WORK}/calls.log"
echo "net.ipv4.tcp_rmem = 4096 131072 16777216"
if [ "\${SYSCTL_FAILS:-0}" = 1 ]; then
  echo "sysctl: cannot stat /proc/sys/net/core/rps_sock_flow_entries: No such file or directory" >&2
  exit 255
fi
EOF
  cat > "${WORK}/bin/systemctl" <<EOF
#!/bin/bash
echo "systemctl \$*" >> "${WORK}/calls.log"
case "\$*" in
  "restart edl-node-tuning.service") [ "\${RESTART_FAILS:-0}" = 1 ] && exit 1 ;;
  "status edl-node-tuning.service --no-pager") echo "edl-node-tuning.service: Main process exited, status=1/FAILURE" ;;
  "show -p InvocationID --value edl-node-tuning.service") echo 0123abcd ;;
esac
exit 0
EOF
  cat > "${WORK}/bin/journalctl" <<EOF
#!/bin/bash
echo "journalctl \$*" >> "${WORK}/calls.log"
[ "\$1" = --sync ] && exit 0
echo "edl-node-tuning: skipped CPU governor on db0: i4i.xlarge exposes no frequency scaling"
EOF
  # tee logs what it was given and where, and writes only the test's fstab.
  cat > "${WORK}/bin/tee" <<EOF
#!/bin/bash
input=\$(cat)
echo "tee \$* <- \$input" >> "${WORK}/calls.log"
[ "\$1" = "${WORK}/fstab" ] && printf '%s\n' "\$input" > "\$1"
exit 0
EOF
  printf '%s\n' 'UUID=1 / ext4 defaults 0 1' '/swap.img none swap sw 0 0' '# /old none swap sw 0 0' > "${WORK}/fstab"
  # install logs its call and, like the real one, fails when its source file does not exist.
  cat > "${WORK}/bin/install" <<EOF
#!/bin/bash
echo "install \$*" >> "${WORK}/calls.log"
source="\${@: -2:1}"
[ -e "\$source" ] || { echo "install: cannot stat \$source" >&2; exit 1; }
EOF
  chmod +x "${WORK}"/bin/*
}

run_tune() {
  (
    PATH="${WORK}/bin:${PATH}"
    # shellcheck source=/dev/null
    source "${WORK}/home/setup_instance.sh"
    FSTAB="${WORK}/fstab"
    tune_node
  ) 2>&1 < /dev/null
}

installed() {
  grep -q "^install .*/node-tuning/$1 $2\$" "${WORK}/calls.log"
}

# --- every packaged file is installed where the system reads it ---
setup
OUTPUT="$(run_tune)"
STATUS=$?
missing=""
while read -r file target; do
  installed "$file" "$target" || missing="${missing} ${file}->${target}"
done <<'EOF'
60-easydblab.conf /etc/sysctl.d/60-easydblab.conf
limits.conf /etc/security/limits.d/50-easydblab.conf
system.conf /etc/systemd/system.conf.d/50-easydblab-limits.conf
easydblab-limits.conf /etc/systemd/system/cassandra.service.d/50-easydblab-limits.conf
easydblab-limits.conf /etc/systemd/system/k3s.service.d/50-easydblab-limits.conf
easydblab-limits.conf /etc/systemd/system/k3s-agent.service.d/50-easydblab-limits.conf
edl-node-tuning /usr/local/sbin/edl-node-tuning
edl-node-tuning.service /etc/systemd/system/edl-node-tuning.service
90-easydblab.rules /etc/udev/rules.d/90-easydblab.rules
EOF
if [[ "$STATUS" -eq 0 && -z "$missing" ]]; then
  pass "every tuning file is installed, the limits drop-in once per database and K3s unit"
else
  fail "expected every file installed, got ${STATUS}; missing:${missing} / ${OUTPUT}"
fi
# Stress is a K8s Job under k3s-agent; there is no stress unit to give a drop-in.
if [[ $(grep -c '^install .*easydblab-limits.conf ' "${WORK}/calls.log") -eq 3 ]]; then
  pass "the limits drop-in goes to cassandra, k3s and k3s-agent only"
else
  fail "expected 3 limits drop-ins, got: $(grep 'easydblab-limits.conf' "${WORK}/calls.log")"
fi

if grep -qx "tee /etc/default/edl-node-tuning <- EDL_INSTANCE_TYPE=" "${WORK}/calls.log"; then
  pass "with no instance type given, the boot unit's defaults file says none"
else
  fail "expected an empty EDL_INSTANCE_TYPE in the defaults file, got: $(grep '^tee' "${WORK}/calls.log")"
fi
setup
EDL_INSTANCE_TYPE=i4i.xlarge run_tune > /dev/null
if grep -qx "tee /etc/default/edl-node-tuning <- EDL_INSTANCE_TYPE=i4i.xlarge" "${WORK}/calls.log"; then
  pass "the instance type node setup passes is kept for the boot unit"
else
  fail "expected EDL_INSTANCE_TYPE=i4i.xlarge in the defaults file, got: $(grep '^tee' "${WORK}/calls.log")"
fi

# The unit's output (its skips and its own verify) reaches setup's console, prefixed.
setup
OUTPUT="$(run_tune)"
if [[ "$OUTPUT" == *"edl-node-tuning.service: edl-node-tuning: skipped CPU governor on db0: i4i.xlarge exposes no frequency scaling"* ]] &&
  grep -qx "journalctl -b _SYSTEMD_INVOCATION_ID=0123abcd --no-pager -o cat" "${WORK}/calls.log"; then
  pass "the boot unit's lines from this run are printed after its restart"
else
  fail "expected the unit's journal lines in the output, got: ${OUTPUT} / $(grep '^journalctl' "${WORK}/calls.log")"
fi

# Every packaged file is installed: a new one that setup forgets fails here.
for file in "${COMMANDS}/node-tuning"/*; do
  name="$(basename "$file")"
  if grep -q "^install .*/node-tuning/${name} " "${WORK}/calls.log"; then
    pass "packaged ${name} is installed"
  else
    fail "packaged ${name} is never installed by setup"
  fi
done

if grep -q '^rm -f /etc/sysctl.d/60-cassandra.conf /etc/security/limits.d/cassandra.conf$' "${WORK}/calls.log"; then
  pass "the files earlier setups wrote are removed"
else
  fail "expected the old sysctl and limits files removed, got: $(cat "${WORK}/calls.log")"
fi

# --- the order that makes the values take: sysctl, udev, a re-exec for DefaultLimit*, then verify ---
order=$(grep -n -E '^(sysctl|udevadm trigger|systemctl daemon-reexec|systemctl enable edl-node-tuning.service|systemctl restart edl-node-tuning.service|systemctl enable --now irqbalance|edl-node-tuning verify)' "${WORK}/calls.log" | cut -d: -f2- | tr '\n' '|')
expected="sysctl -p /etc/sysctl.d/60-easydblab.conf|udevadm trigger --subsystem-match=block --action=change|systemctl daemon-reexec|systemctl enable --now irqbalance|systemctl enable edl-node-tuning.service|systemctl restart edl-node-tuning.service|edl-node-tuning verify|"
if [[ "$order" == "$expected" ]]; then
  pass "this file's settings are applied, systemd re-executed, irqbalance and the boot unit started, then verified"
else
  fail "unexpected order: ${order}"
fi

# The real swap edit ran on the fstab: active swap lines are commented, others and comments kept.
if [[ "$(cat "${WORK}/fstab")" == $'UUID=1 / ext4 defaults 0 1\n#/swap.img none swap sw 0 0\n# /old none swap sw 0 0' ]]; then
  pass "fstab swap lines are commented out so swap stays off after a reboot"
else
  fail "unexpected fstab after setup: $(cat "${WORK}/fstab")"
fi
# A re-run finds nothing to change and leaves the fstab alone.
: > "${WORK}/calls.log"
run_tune > /dev/null
if ! grep -q "^tee ${WORK}/fstab" "${WORK}/calls.log" && [[ "$(grep -c '^#/swap.img' "${WORK}/fstab")" -eq 1 ]]; then
  pass "a re-run does not rewrite an fstab with no active swap line"
else
  fail "a re-run should leave the fstab alone, got: $(grep '^tee' "${WORK}/calls.log") / $(cat "${WORK}/fstab")"
fi

# --- a sysctl key the kernel rejects fails setup, naming it ---
setup
OUTPUT="$(SYSCTL_FAILS=1 run_tune)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: sysctl did not apply /etc/sysctl.d/60-easydblab.conf: sysctl: cannot stat /proc/sys/net/core/rps_sock_flow_entries"* ]] &&
  ! grep -q "^edl-node-tuning verify" "${WORK}/calls.log"; then
  pass "a rejected sysctl key fails setup with sysctl's message, before verify"
else
  fail "a rejected sysctl key should fail setup naming it, got ${STATUS}: ${OUTPUT}"
fi

# --- the boot unit fails to start ---
setup
OUTPUT="$(RESTART_FAILS=1 run_tune)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: edl-node-tuning.service failed: edl-node-tuning.service: Main process exited, status=1/FAILURE"* ]] &&
  ! grep -q "^edl-node-tuning verify" "${WORK}/calls.log"; then
  pass "a failed boot unit fails setup with its status, before verify"
else
  fail "a failed boot unit should fail setup, got ${STATUS}: ${OUTPUT}"
fi

# --- a setting that did not take fails setup, naming it ---
setup
OUTPUT="$(VERIFY_FAILS=1 run_tune)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: vm.swappiness: wanted 1, got 60"* ]]; then
  pass "a failed verify fails setup with the setting's ERROR line"
else
  fail "a failed verify should fail setup, got ${STATUS}: ${OUTPUT}"
fi

# --- the upload lacks a file the script installs (a workspace script newer than the files) ---
setup
rm "${WORK}/home/node-tuning/90-easydblab.rules"
OUTPUT="$(run_tune)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: could not install the node tuning file 90-easydblab.rules"* ]] && ! grep -q "^sysctl" "${WORK}/calls.log"; then
  pass "a tuning file missing from the upload fails setup, naming it, before anything is applied"
else
  fail "a missing tuning file should fail setup naming it, got ${STATUS}: ${OUTPUT}"
fi

# --- the tuning files were not uploaded ---
setup no-tuning
OUTPUT="$(run_tune)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: the node tuning files are missing"* ]] && ! grep -q '^install' "${WORK}/calls.log"; then
  pass "missing tuning files fail setup before anything is installed"
else
  fail "missing tuning files should fail setup, got ${STATUS}: ${OUTPUT}"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
