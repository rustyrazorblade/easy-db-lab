#!/usr/bin/env bash
#
# Unit tests for edl-node-tuning (node-os-tuning): the packet-steering mask and flow counts, the
# CPU governor and idle states where the instance exposes them and a logged skip where it does
# not, and verify naming each setting that does not match.
#
# The script runs against a fake /sys and /proc under a temporary directory (EDL_SYS_ROOT,
# EDL_PROC_ROOT); logger, systemctl and hostname are stubbed on PATH. No root, no Docker.
# It runs under dash when dash is installed, as on the nodes.
#
# Run directly:  bash src/test/shell/edl-node-tuning.test.sh
# Or via gradle: ./gradlew testEdlNodeTuning

set -uo pipefail
unset JOURNAL_STREAM

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
RESOURCES="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/commands/node-tuning"
SCRIPT="${RESOURCES}/edl-node-tuning"
# Nodes run the script under dash (Ubuntu's /bin/sh); use it when it is installed so a bashism fails here.
TEST_SH="$(command -v dash || echo sh)"

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

# A node with [cpus] online CPUs, one physical link ens5 (an ENA NIC) with [queues] receive
# queues, and a loopback.
setup() {
  local cpus="$1" queues="$2" i
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  SYS="${WORK}/sys"
  PROC="${WORK}/proc"
  mkdir -p "${WORK}/bin" "${SYS}/devices/system/cpu" "${SYS}/kernel/mm/transparent_hugepage" "${SYS}/bus/pci/drivers/ena" "${PROC}/sys"
  echo "0-$((cpus - 1))" > "${SYS}/devices/system/cpu/online"
  for ((i = 0; i < cpus; i++)); do mkdir -p "${SYS}/devices/system/cpu/cpu${i}"; done
  echo "always [madvise] never" > "${SYS}/kernel/mm/transparent_hugepage/enabled"
  echo "always defer defer+madvise [madvise] never" > "${SYS}/kernel/mm/transparent_hugepage/defrag"

  # ens5 is a physical link (it has a device/ entry, as an ENA, gve or virtio NIC does); lo has
  # none. Its device directory holds net/ens5, as the kernel's does.
  mkdir -p "${SYS}/devices/pci0000:00/0000:00:05.0/net/ens5" "${SYS}/class/net/lo/queues/rx-0"
  ln -s "${SYS}/devices/pci0000:00/0000:00:05.0/net/ens5" "${SYS}/class/net/ens5"
  ln -s "${SYS}/devices/pci0000:00/0000:00:05.0" "${SYS}/devices/pci0000:00/0000:00:05.0/net/ens5/device"
  ln -s "${SYS}/bus/pci/drivers/ena" "${SYS}/devices/pci0000:00/0000:00:05.0/driver"
  add_queues ens5 "${queues}"
  echo 0 > "${SYS}/class/net/lo/queues/rx-0/rps_cpus"

  echo "Filename Type Size Used Priority" > "${PROC}/swaps"

  # Cassandra is loaded; it is running (pid 4242, limits in the fake /proc) only when CASSANDRA_ACTIVE=active.
  cat > "${WORK}/bin/systemctl" <<'EOF'
#!/bin/bash
case "$*" in
  "is-active irqbalance") echo "${IRQBALANCE:-active}" ;;
  "is-enabled edl-node-tuning.service") echo "${UNIT_ENABLED:-enabled}" ;;
  "show -p DefaultLimitNOFILE --value") echo "${DEFAULT_NOFILE:-1048576}" ;;
  "show -p DefaultLimitMEMLOCK --value") echo infinity ;;
  "show -p LoadState --value cassandra.service") echo loaded ;;
  "show -p LoadState --value "*) echo not-found ;;
  "show -p LimitNOFILE --value cassandra.service") echo "${CASSANDRA_NOFILE:-1048576}" ;;
  "show -p LimitMEMLOCK --value cassandra.service") echo "${CASSANDRA_MEMLOCK:-infinity}" ;;
  "show -p ActiveState --value cassandra.service") echo "${CASSANDRA_ACTIVE:-inactive}" ;;
  "show -p MainPID --value cassandra.service") echo 4242 ;;
esac
EOF
  mkdir -p "${PROC}/4242"
  process_limits 1048576 unlimited
  cat > "${WORK}/bin/logger" <<EOF
#!/bin/bash
echo "\$*" >> "${WORK}/logger.log"
EOF
  printf '#!/bin/bash\necho db0\n' > "${WORK}/bin/hostname"
  chmod +x "${WORK}"/bin/*
  : > "${WORK}/logger.log"
}

# Gives link [$1] [$2] receive queues, each with steering off.
add_queues() {
  local i
  for ((i = 0; i < $2; i++)); do
    mkdir -p "${SYS}/class/net/$1/queues/rx-${i}"
    echo 0 > "${SYS}/class/net/$1/queues/rx-${i}/rps_cpus"
    echo 0 > "${SYS}/class/net/$1/queues/rx-${i}/rps_flow_cnt"
  done
}

# The running Cassandra's /proc/<pid>/limits, as the kernel prints it: open files [$1] and locked
# memory [$2], soft and hard alike.
process_limits() {
  printf '%-26s%-21s%-21s%-10s\n' \
    "Limit" "Soft Limit" "Hard Limit" "Units" \
    "Max processes" unlimited unlimited processes \
    "Max open files" "$1" "$1" files \
    "Max locked memory" "$2" "$2" bytes > "${PROC}/4242/limits"
}

# CPU idle states on every CPU, one "<n> <name> <latency>" argument per state.
idle_states() {
  local cpu row n name latency d
  for cpu in "${SYS}/devices/system/cpu"/cpu[0-9]*; do
    for row in "$@"; do
      read -r n name latency <<< "${row}"
      d="${cpu}/cpuidle/state${n}"
      mkdir -p "$d"
      echo "$name" > "$d/name"
      echo "$latency" > "$d/latency"
      echo 0 > "$d/disable"
    done
  done
}

# cpu1's disable flags, state0 first, as one string.
disable_flags() {
  local d flags=""
  for d in "${SYS}/devices/system/cpu/cpu1/cpuidle"/state*; do flags="${flags}$(cat "$d/disable")"; done
  echo "$flags"
}

# Writes every key of the packaged sysctl file into the fake /proc/sys with its tuned value, the
# way the kernel prints it (tab-separated), plus fs.nr_open and fs.file-max at systemd's values.
tuned_sysctls() {
  local key value path
  grep -E '^[a-z]' "${RESOURCES}/60-easydblab.conf" | while IFS='=' read -r key value; do
    key=$(echo "$key" | tr -d ' ')
    value=$(echo "$value" | sed 's/^ *//; s/ *$//' | tr ' ' '\t')
    path="${PROC}/sys/$(echo "$key" | tr . /)"
    mkdir -p "$(dirname "$path")"
    echo "$value" > "$path"
  done
  mkdir -p "${PROC}/sys/fs"
  echo 1073741816 > "${PROC}/sys/fs/nr_open"
  echo 9223372036854775807 > "${PROC}/sys/fs/file-max"
}

run() {
  PATH="${WORK}/bin:${PATH}" EDL_SYS_ROOT="${SYS}" EDL_PROC_ROOT="${PROC}" \
    EDL_SYSCTL_CONF="${RESOURCES}/60-easydblab.conf" EDL_DEFAULTS="${WORK}/edl-node-tuning.defaults" \
    EDL_LOCK_DIR="${WORK}" "${TEST_SH}" "${SCRIPT}" "$@" 2>&1
}

# --- RPS mask covers every CPU, flow count is shared across the queues ---
for row in "2 3" "8 ff" "33 1,ffffffff" "96 ffffffff,ffffffff,ffffffff"; do
  read -r cpus expected <<< "${row}"
  setup "${cpus}" 4
  run rps ens5 > /dev/null
  actual=$(cat "${SYS}/class/net/ens5/queues/rx-3/rps_cpus")
  if [[ "$actual" == "$expected" ]]; then
    pass "RPS mask for ${cpus} CPUs is ${expected}"
  else
    fail "RPS mask for ${cpus} CPUs: expected ${expected}, got ${actual}"
  fi
done

setup 8 4
run rps ens5 > /dev/null
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")" == 8192 ]]; then
  pass "4 receive queues each get 32768 / 4 flows"
else
  fail "expected 8192 flows per queue, got $(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")"
fi

# The kernel rounds rps_flow_cnt up to a power of two and reads that back, so a queue count that
# is not a power of two must write the rounded share, and verify must accept it.
for row in "3 16384" "6 8192" "12 4096"; do
  read -r queues expected <<< "${row}"
  setup 8 "${queues}"
  run rps ens5 > /dev/null
  actual=$(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")
  if [[ "$actual" == "$expected" ]]; then
    pass "${queues} receive queues each get the power-of-two share ${expected}"
  else
    fail "${queues} receive queues: expected ${expected} flows per queue, got ${actual}"
  fi
done

# --- RPS mask for a sparse CPU list: CPUs 0-3, 8, 10 and 11 ---
setup 12 1
echo "0-3,8,10-11" > "${SYS}/devices/system/cpu/online"
run rps ens5 > /dev/null
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-0/rps_cpus")" == d0f ]]; then
  pass "a sparse online CPU list 0-3,8,10-11 gives the mask d0f"
else
  fail "expected d0f for 0-3,8,10-11, got $(cat "${SYS}/class/net/ens5/queues/rx-0/rps_cpus")"
fi

# --- rps edge cases ---
setup 2 0
OUTPUT=$(EDL_QUEUE_WAIT_TRIES=1 run rps ens5)
STATUS=$?
if [[ "$STATUS" -eq 0 && -z "$OUTPUT" && -z "$(ls "${SYS}/class/net/ens5/queues" 2> /dev/null)" ]]; then
  pass "an interface whose queues never appear is waited for, then left alone"
else
  fail "expected exit 0 and nothing written, got ${STATUS}: ${OUTPUT} / $(ls "${SYS}/class/net/ens5/queues" 2> /dev/null)"
fi
setup 2 1
OUTPUT=$(run rps)
STATUS=$?
if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"usage: edl-node-tuning rps <iface>"* ]]; then
  pass "rps with no interface exits 1 with its usage"
else
  fail "rps with no interface should exit 1, got ${STATUS}: ${OUTPUT}"
fi
OUTPUT=$(run tune)
STATUS=$?
if [[ "$STATUS" -eq 2 && "$OUTPUT" == *"usage: edl-node-tuning apply"* ]]; then
  pass "an unknown subcommand exits 2 with the usage"
else
  fail "an unknown subcommand should exit 2, got ${STATUS}: ${OUTPUT}"
fi

# --- a changed receive queue count (ethtool -L) steers the whole interface again ---
setup 4 2
run rps ens5 > /dev/null
add_queues ens5 4
run rps-queue /devices/pci0000:00/0000:00:05.0/net/ens5/queues/rx-3 > /dev/null
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-3/rps_cpus")" == f && "$(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")" == 8192 ]]; then
  pass "a new receive queue gets the mask, and every queue the flow share for the new count"
else
  fail "after 2 -> 4 queues expected rx-3 mask f and rx-0 flows 8192, got $(cat "${SYS}/class/net/ens5/queues/rx-3/rps_cpus") / $(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")"
fi
OUTPUT=$(run rps-queue /devices/pci0000:00/0000:00:06.0/net/eth0/queues/rx-0)
STATUS=$?
if [[ "$STATUS" -eq 0 && -z "$OUTPUT" ]]; then
  pass "a queue of an interface renamed since is left to that interface's own add event"
else
  fail "a queue of a gone interface should be a no-op, got ${STATUS}: ${OUTPUT}"
fi

# ethtool -L sends one event per queue and udev runs them in parallel: steering one interface is
# serialized, and the flow share is computed inside the lock, so the last event to run uses the
# final queue count. Here an event waits on the lock while the count drops from 8 to 4.
setup 4 2
add_queues ens5 8
flock "${WORK}/edl-node-tuning-ens5.lock" sleep 2 &
HOLDER=$!
sleep 0.5
run rps-queue /devices/pci0000:00/0000:00:05.0/net/ens5/queues/rx-7 > /dev/null &
WAITER=$!
sleep 0.5
rm -rf "${SYS}/class/net/ens5/queues"/rx-[4-7]
wait "$HOLDER" "$WAITER"
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")" == 8192 ]]; then
  pass "steering waits for the interface lock and uses the queue count it sees inside it"
else
  fail "after 8 -> 4 queues under the lock expected rx-0 flows 8192, got $(cat "${SYS}/class/net/ens5/queues/rx-0/rps_flow_cnt")"
fi

# --- apply steers physical links only ---
setup 4 2
# A virtual link the CNI creates: queues, no device behind it.
mkdir -p "${SYS}/class/net/lxc1234/queues/rx-0"
echo 0 > "${SYS}/class/net/lxc1234/queues/rx-0/rps_cpus"
run apply > /dev/null
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-1/rps_cpus")" == f && "$(cat "${SYS}/class/net/lo/queues/rx-0/rps_cpus")" == 0 &&
  "$(cat "${SYS}/class/net/lxc1234/queues/rx-0/rps_cpus")" == 0 ]]; then
  pass "apply steers the physical link and leaves loopback and virtual links alone"
else
  fail "apply should steer ens5 only, got ens5=$(cat "${SYS}/class/net/ens5/queues/rx-1/rps_cpus") lo=$(cat "${SYS}/class/net/lo/queues/rx-0/rps_cpus") lxc=$(cat "${SYS}/class/net/lxc1234/queues/rx-0/rps_cpus")"
fi
# A physical NIC with another driver (gve, virtio) is steered too: selection is by device, not driver.
setup 4 1
rm "${SYS}/devices/pci0000:00/0000:00:05.0/driver"
mkdir -p "${SYS}/bus/pci/drivers/gve"
ln -s "${SYS}/bus/pci/drivers/gve" "${SYS}/devices/pci0000:00/0000:00:05.0/driver"
run apply > /dev/null
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-0/rps_cpus")" == f ]]; then
  pass "apply steers a physical link whatever its driver"
else
  fail "a gve link should be steered, got $(cat "${SYS}/class/net/ens5/queues/rx-0/rps_cpus")"
fi
if [[ "$(cat "${SYS}/kernel/mm/transparent_hugepage/enabled")" == madvise && "$(cat "${SYS}/kernel/mm/transparent_hugepage/defrag")" == never ]]; then
  pass "apply sets THP to madvise with defrag never"
else
  fail "THP not set: $(cat "${SYS}/kernel/mm/transparent_hugepage/enabled") / $(cat "${SYS}/kernel/mm/transparent_hugepage/defrag")"
fi

# --- no frequency scaling or idle states: one skip line each, naming the instance type ---
setup 2 1
OUTPUT=$(EDL_INSTANCE_TYPE=i4i.xlarge run apply)
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && grep -qx -- "-t edl-node-tuning skipped CPU governor on db0: i4i.xlarge exposes no frequency scaling" "${WORK}/logger.log" &&
  grep -qx -- "-t edl-node-tuning skipped CPU idle states on db0: i4i.xlarge exposes no idle states" "${WORK}/logger.log" &&
  [[ $(wc -l < "${WORK}/logger.log") -eq 2 ]]; then
  pass "an instance with no CPU controls logs a skip naming the node and instance type"
else
  fail "expected two skip lines and success, got ${STATUS}: ${OUTPUT} / $(cat "${WORK}/logger.log")"
fi
setup 2 1
echo "EDL_INSTANCE_TYPE=c6id.2xlarge" > "${WORK}/edl-node-tuning.defaults"
run apply > /dev/null
if grep -q "skipped CPU governor on db0: c6id.2xlarge exposes no frequency scaling" "${WORK}/logger.log"; then
  pass "with no EDL_INSTANCE_TYPE the instance type comes from the file node setup wrote"
else
  fail "expected the instance type from the defaults file, got $(cat "${WORK}/logger.log")"
fi
setup 2 1
run apply > /dev/null
if grep -q "skipped CPU governor on db0: unknown instance type" "${WORK}/logger.log"; then
  pass "a node setup did not tell its instance type says unknown instance type"
else
  fail "expected unknown instance type, got $(cat "${WORK}/logger.log")"
fi
# Under systemd the output is the journal already, so nothing is logged a second time.
setup 2 1
OUTPUT=$(JOURNAL_STREAM=8:1234 EDL_INSTANCE_TYPE=i4i.xlarge run apply)
if [[ ! -s "${WORK}/logger.log" && "$OUTPUT" == *"skipped CPU governor on db0: i4i.xlarge"* ]]; then
  pass "under systemd the skip lines are printed once, to the journal stream"
else
  fail "expected no logger calls under JOURNAL_STREAM, got: $(cat "${WORK}/logger.log") / ${OUTPUT}"
fi

# --- frequency scaling exposed: every CPU gets performance ---
setup 2 1
for i in 0 1; do
  mkdir -p "${SYS}/devices/system/cpu/cpu${i}/cpufreq"
  echo "powersave performance" > "${SYS}/devices/system/cpu/cpu${i}/cpufreq/scaling_available_governors"
  echo powersave > "${SYS}/devices/system/cpu/cpu${i}/cpufreq/scaling_governor"
done
run apply > /dev/null
if [[ "$(cat "${SYS}/devices/system/cpu/cpu1/cpufreq/scaling_governor")" == performance ]] && ! grep -q "CPU governor" "${WORK}/logger.log"; then
  pass "an instance with frequency scaling gets the performance governor on every CPU"
else
  fail "expected performance on cpu1, got $(cat "${SYS}/devices/system/cpu/cpu1/cpufreq/scaling_governor")"
fi

# An offline CPU has no cpufreq directory: apply and verify use only the online CPUs.
setup 3 1
echo "0-1" > "${SYS}/devices/system/cpu/online"
for i in 0 1; do
  mkdir -p "${SYS}/devices/system/cpu/cpu${i}/cpufreq"
  echo "powersave performance" > "${SYS}/devices/system/cpu/cpu${i}/cpufreq/scaling_available_governors"
  echo powersave > "${SYS}/devices/system/cpu/cpu${i}/cpufreq/scaling_governor"
done
OUTPUT="$(run apply)"
if [[ "$OUTPUT" != *"could not write"* && "$(cat "${SYS}/devices/system/cpu/cpu1/cpufreq/scaling_governor")" == performance ]] &&
  ! run verify | grep -q "cpu2"; then
  pass "an offline CPU is left alone by apply and verify"
else
  fail "an offline CPU should be skipped, got: ${OUTPUT} / $(run verify)"
fi

# A write the kernel refuses is reported once, by apply's own line, with no shell error beside it.
setup 2 1
mkdir -p "${SYS}/block/nvme1n1/queue/scheduler"
echo 128 > "${SYS}/block/nvme1n1/queue/read_ahead_kb"
OUTPUT="$(run apply)"
if [[ "$(printf '%s\n' "$OUTPUT" | grep -c "nvme1n1/queue/scheduler")" -eq 1 && "$OUTPUT" == *"could not write none to"* ]]; then
  pass "a failed write prints one line"
else
  fail "a failed write should print only apply's line, got: ${OUTPUT}"
fi

# --- disks: apply sets the queue values itself, so the boot verify does not race udev ---
setup 2 1
mkdir -p "${SYS}/block/nvme1n1/queue" "${SYS}/block/xvda/queue" "${SYS}/block/loop0/queue"
for d in nvme1n1 xvda loop0; do
  echo "none [mq-deadline]" > "${SYS}/block/${d}/queue/scheduler"
  echo 128 > "${SYS}/block/${d}/queue/read_ahead_kb"
done
run apply > /dev/null
if [[ "$(cat "${SYS}/block/nvme1n1/queue/scheduler")" == none && "$(cat "${SYS}/block/nvme1n1/queue/read_ahead_kb")" == 4 &&
  "$(cat "${SYS}/block/xvda/queue/read_ahead_kb")" == 4 && "$(cat "${SYS}/block/loop0/queue/read_ahead_kb")" == 128 ]]; then
  pass "apply sets the scheduler and readahead of every NVMe and Xen disk, and no other"
else
  fail "expected nvme1n1 and xvda tuned and loop0 untouched, got $(cat "${SYS}/block"/*/queue/read_ahead_kb | tr '\n' ' ')"
fi

# --- idle states exposed: every state deeper than C1 is disabled ---
# Each row: the states as "<n> <name> <latency>" separated by commas, the disable flags expected
# on cpu1 (state0 first), and what the case is.
while IFS='|' read -r states expected description; do
  setup 2 1
  IFS=',' read -r -a rows <<< "${states}"
  idle_states "${rows[@]}"
  run apply > /dev/null
  actual=$(disable_flags)
  if [[ "$actual" == "$expected" ]]; then
    pass "idle states: ${description}"
  else
    fail "idle states, ${description}: expected disable flags ${expected}, got ${actual}"
  fi
done <<'EOF'
0 POLL 0,1 C1 2,2 C1E 10,3 C6 133|0011|C1E and C6 are disabled, POLL and C1 stay
0 POLL 0,1 C1_ACPI 1,2 C2 50,3 C3 100|0011|C1_ACPI is C1; C2 and C3 are disabled
0 POLL 0,1 HALT 1,2 C6 100|001|HALT, the first state after POLL, is C1; C6 is disabled
0 WFI 1,1 cpu-sleep 300|01|arm64 metal: WFI is C1, cpu-sleep is disabled
0 WFI 1|0|an arm64 VM with only WFI: nothing is disabled
EOF

# --- verify: a fully tuned node passes ---
setup 4 2
tuned_sysctls
run apply > /dev/null
OUTPUT=$(run verify)
STATUS=$?
if [[ "$STATUS" -eq 0 && -z "$OUTPUT" ]]; then
  pass "verify passes on a tuned node"
else
  fail "verify should pass, got ${STATUS}: ${OUTPUT}"
fi

# --- verify: each kind of mismatch fails, naming the setting, the wanted and the read value ---
# The line is also in the journal, where a mismatch after a reboot is read.
expect_mismatch() {
  local description="$1" expected="$2"
  OUTPUT=$(run verify)
  STATUS=$?
  if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"$expected"* ]] && grep -qF -- "-t edl-node-tuning ${expected}" "${WORK}/logger.log"; then
    pass "verify fails on ${description}"
  else
    fail "verify on ${description}: expected status 1 and '${expected}' printed and journalled, got ${STATUS}: ${OUTPUT} / $(cat "${WORK}/logger.log")"
  fi
}

tuned_node() {
  setup 4 2
  tuned_sysctls
  run apply > /dev/null
}

tuned_node
echo 128 > "${PROC}/sys/fs/inotify/max_user_instances"
expect_mismatch "a sysctl that did not take" "ERROR: fs.inotify.max_user_instances: wanted 8192, got 128"

tuned_node
printf '32768\t60999\n' > "${PROC}/sys/net/ipv4/ip_local_port_range"
expect_mismatch "a multi-value sysctl" "ERROR: net.ipv4.ip_local_port_range: wanted 1024 65535, got 32768 60999"

tuned_node
echo 524288 > "${PROC}/sys/fs/nr_open"
expect_mismatch "fs.nr_open below the minimum" "ERROR: fs.nr_open: wanted at least 1048576, got 524288"

tuned_node
echo "always madvise [never]" > "${SYS}/kernel/mm/transparent_hugepage/enabled"
expect_mismatch "THP" "ERROR: transparent_hugepage/enabled: wanted madvise, got never"

tuned_node
echo 1 > "${SYS}/class/net/ens5/queues/rx-1/rps_cpus"
expect_mismatch "an RPS mask" "ERROR: ens5 rx-1 rps_cpus: wanted f, got 1"

# 3 queues: the kernel keeps 16384, so an unrounded 10922 means the write did not take.
setup 4 3
tuned_sysctls
run apply > /dev/null
echo 10922 > "${SYS}/class/net/ens5/queues/rx-2/rps_flow_cnt"
expect_mismatch "an unrounded flow count" "ERROR: ens5 rx-2 rps_flow_cnt: wanted 16384, got 10922"

tuned_node
for i in 0 1 2 3; do
  mkdir -p "${SYS}/devices/system/cpu/cpu${i}/cpufreq"
  echo "powersave performance" > "${SYS}/devices/system/cpu/cpu${i}/cpufreq/scaling_available_governors"
  echo performance > "${SYS}/devices/system/cpu/cpu${i}/cpufreq/scaling_governor"
done
echo powersave > "${SYS}/devices/system/cpu/cpu1/cpufreq/scaling_governor"
expect_mismatch "a CPU governor" "ERROR: cpu1 scaling_governor: wanted performance, got powersave"

tuned_node
idle_states "0 POLL 0" "1 C1 2" "2 C1E 10" "3 C6 133"
run apply > /dev/null
echo 0 > "${SYS}/devices/system/cpu/cpu2/cpuidle/state3/disable"
expect_mismatch "an idle state turned back on" "ERROR: cpu2 idle state3 disable: wanted 1, got 0"

tuned_node
mkdir -p "${SYS}/block/nvme1n1/queue"
echo "none [mq-deadline]" > "${SYS}/block/nvme1n1/queue/scheduler"
echo 4 > "${SYS}/block/nvme1n1/queue/read_ahead_kb"
expect_mismatch "a disk scheduler" "ERROR: nvme1n1 scheduler: wanted none, got mq-deadline"

tuned_node
echo 0000000f > "${SYS}/class/net/ens5/queues/rx-1/rps_cpus"
if OUTPUT=$(run verify); then
  pass "verify accepts the kernel's zero-padded RPS mask"
else
  fail "a padded mask should pass, got: ${OUTPUT}"
fi

tuned_node
mkdir -p "${SYS}/block/nvme1n1/queue"
echo "[none] mq-deadline" > "${SYS}/block/nvme1n1/queue/scheduler"
echo 128 > "${SYS}/block/nvme1n1/queue/read_ahead_kb"
expect_mismatch "a disk readahead" "ERROR: nvme1n1 read_ahead_kb: wanted 4, got 128"

tuned_node
echo "/swap.img file 1024 0 -2" >> "${PROC}/swaps"
expect_mismatch "swap on" "ERROR: swap devices: wanted 0, got 1"

# A boot unit that is not enabled would not restore anything after a reboot.
tuned_node
OUTPUT="$(UNIT_ENABLED=disabled run verify)"
STATUS=$?
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"ERROR: edl-node-tuning.service: wanted enabled, got disabled"* ]]; then
  pass "verify fails when the boot unit is not enabled"
else
  fail "a disabled boot unit should fail verify, got ${STATUS}: ${OUTPUT}"
fi

tuned_node
OUTPUT=$(IRQBALANCE=inactive run verify)
STATUS=$?
if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"ERROR: irqbalance: wanted active, got inactive"* ]]; then
  pass "verify fails when irqbalance is not running"
else
  fail "expected irqbalance failure, got ${STATUS}: ${OUTPUT}"
fi

# irqbalance's unit has ConditionCPUs=>1, so on one CPU it is inactive by design.
setup 1 1
tuned_sysctls
run apply > /dev/null
if OUTPUT=$(IRQBALANCE=inactive run verify) && grep -q "skipped irqbalance on db0" "${WORK}/logger.log"; then
  pass "verify skips irqbalance on a single-CPU node"
else
  fail "expected verify to pass and log the irqbalance skip on one CPU, got: ${OUTPUT} / $(cat "${WORK}/logger.log")"
fi

tuned_node
OUTPUT=$(CASSANDRA_MEMLOCK=8388608 run verify)
STATUS=$?
if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"ERROR: cassandra LimitMEMLOCK: wanted infinity, got 8388608"* ]]; then
  pass "verify fails when a database service lacks its limits"
else
  fail "expected the cassandra limit failure, got ${STATUS}: ${OUTPUT}"
fi

# A running service keeps the limits it started with: verify reads them from its process.
tuned_node
OUTPUT=$(CASSANDRA_ACTIVE=active run verify)
STATUS=$?
if [[ "$STATUS" -eq 0 && -z "$OUTPUT" ]]; then
  pass "verify passes when the running database process has the limits"
else
  fail "verify with a running Cassandra at the limits should pass, got ${STATUS}: ${OUTPUT}"
fi

tuned_node
process_limits 524288 8388608
OUTPUT=$(CASSANDRA_ACTIVE=active run verify)
STATUS=$?
if [[ "$STATUS" -eq 1 &&
  "$OUTPUT" == *"ERROR: cassandra running process open files: wanted 1048576, got 524288; restart cassandra to apply"* &&
  "$OUTPUT" == *"ERROR: cassandra running process locked memory: wanted unlimited, got 8388608; restart cassandra to apply"* ]]; then
  pass "verify fails, saying to restart it, when a running service started before its limits"
else
  fail "expected the running-process limit failures, got ${STATUS}: ${OUTPUT}"
fi

tuned_node
process_limits 524288 8388608
OUTPUT=$(run verify)
STATUS=$?
if [[ "$STATUS" -eq 0 && -z "$OUTPUT" ]]; then
  pass "a stopped service is checked by its unit only"
else
  fail "an inactive Cassandra's stale /proc should not be read, got ${STATUS}: ${OUTPUT}"
fi

tuned_node
OUTPUT=$(DEFAULT_NOFILE=524288 run verify)
STATUS=$?
if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"ERROR: systemd DefaultLimitNOFILE: wanted 1048576, got 524288"* ]]; then
  pass "verify fails when systemd's default limits did not load"
else
  fail "expected the DefaultLimitNOFILE failure, got ${STATUS}: ${OUTPUT}"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
