#!/usr/bin/env bash
#
# Unit tests for the OS tuning step of setup_instance.sh (node-os-tuning): it installs every
# packaged tuning file where the system reads it, removes the files earlier setups wrote, keeps
# swap off across a reboot, and fails naming the reason when the files are missing or a setting
# did not take.
#
# The script is sourced, which defines its functions without running setup. sudo, install, rm,
# sed, sysctl, swapoff, udevadm and systemctl are stubbed on PATH and log their calls;
# edl-node-tuning verify is the stub's exit status. No root, no Docker.
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
  for tool in rm sed sysctl swapoff udevadm systemctl; do
    cat > "${WORK}/bin/${tool}" <<EOF
#!/bin/bash
echo "${tool} \$*" >> "${WORK}/calls.log"
EOF
  done
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
easydblab-limits.conf /etc/systemd/system/cassandra-easy-stress.service.d/50-easydblab-limits.conf
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
order=$(grep -n -E '^(sysctl --system|udevadm trigger|systemctl daemon-reexec|systemctl enable edl-node-tuning.service|systemctl restart edl-node-tuning.service|systemctl enable --now irqbalance|edl-node-tuning verify)' "${WORK}/calls.log" | cut -d: -f2- | tr '\n' '|')
expected="sysctl --system|udevadm trigger --subsystem-match=block --action=change|systemctl daemon-reexec|systemctl enable edl-node-tuning.service|systemctl restart edl-node-tuning.service|systemctl enable --now irqbalance|edl-node-tuning verify|"
if [[ "$order" == "$expected" ]]; then
  pass "settings are applied, systemd re-executed, the boot unit and irqbalance enabled, then verified"
else
  fail "unexpected order: ${order}"
fi

if grep -q "^sed -i -E /^\[^#\].\*\[\[:space:\]\]swap\[\[:space:\]\]/s/^/#/ /etc/fstab$" "${WORK}/calls.log"; then
  pass "fstab swap lines are commented out so swap stays off after a reboot"
else
  fail "expected the fstab swap edit, got: $(grep '^sed' "${WORK}/calls.log")"
fi

# The swap edit, run on a real fstab: swap lines are commented, others and comments are kept.
fstab="${WORK}/fstab"
printf '%s\n' 'UUID=1 / ext4 defaults 0 1' '/swap.img none swap sw 0 0' '# /old none swap sw 0 0' > "$fstab"
/usr/bin/sed -i.bak -E '/^[^#].*[[:space:]]swap[[:space:]]/s/^/#/' "$fstab"
if [[ "$(cat "$fstab")" == $'UUID=1 / ext4 defaults 0 1\n#/swap.img none swap sw 0 0\n# /old none swap sw 0 0' ]]; then
  pass "the swap edit comments only active swap lines"
else
  fail "unexpected fstab after the swap edit: $(cat "$fstab")"
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
