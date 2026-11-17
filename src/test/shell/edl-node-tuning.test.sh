#!/usr/bin/env bash
#
# Unit tests for edl-node-tuning (node-os-tuning): the packet-steering mask and flow counts, the
# CPU governor and idle states where the instance exposes them and a logged skip where it does
# not, and verify naming each setting that does not match.
#
# The script runs against a fake /sys and /proc under a temporary directory (EDL_SYS_ROOT,
# EDL_PROC_ROOT); logger, systemctl, hostname and curl are stubbed on PATH. No root, no Docker.
# It runs under dash when dash is installed, as on the nodes.
#
# Run directly:  bash src/test/shell/edl-node-tuning.test.sh
# Or via gradle: ./gradlew testEdlNodeTuning

set -uo pipefail

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

# A node with [cpus] online CPUs, one ENA link ens5 with [queues] receive queues, and a loopback.
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

  mkdir -p "${SYS}/class/net/ens5/device" "${SYS}/class/net/lo/queues/rx-0"
  ln -s "${SYS}/bus/pci/drivers/ena" "${SYS}/class/net/ens5/device/driver"
  for ((i = 0; i < queues; i++)); do
    mkdir -p "${SYS}/class/net/ens5/queues/rx-${i}"
    echo 0 > "${SYS}/class/net/ens5/queues/rx-${i}/rps_cpus"
    echo 0 > "${SYS}/class/net/ens5/queues/rx-${i}/rps_flow_cnt"
  done
  echo 0 > "${SYS}/class/net/lo/queues/rx-0/rps_cpus"

  echo "Filename Type Size Used Priority" > "${PROC}/swaps"

  cat > "${WORK}/bin/systemctl" <<'EOF'
#!/bin/bash
case "$*" in
  "is-active irqbalance") echo "${IRQBALANCE:-active}" ;;
  "show -p DefaultLimitNOFILE --value") echo "${DEFAULT_NOFILE:-1048576}" ;;
  "show -p DefaultLimitMEMLOCK --value") echo infinity ;;
  "show -p LoadState --value cassandra.service") echo loaded ;;
  "show -p LoadState --value "*) echo not-found ;;
  "show -p LimitNOFILE --value cassandra.service") echo "${CASSANDRA_NOFILE:-1048576}" ;;
  "show -p LimitMEMLOCK --value cassandra.service") echo "${CASSANDRA_MEMLOCK:-infinity}" ;;
esac
EOF
  cat > "${WORK}/bin/logger" <<EOF
#!/bin/bash
echo "\$*" >> "${WORK}/logger.log"
EOF
  printf '#!/bin/bash\necho db0\n' > "${WORK}/bin/hostname"
  printf '#!/bin/bash\necho call >> "%s/curl.log"\nexit 7\n' "${WORK}" > "${WORK}/bin/curl"
  chmod +x "${WORK}"/bin/*
  : > "${WORK}/logger.log"
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
    EDL_SYSCTL_CONF="${RESOURCES}/60-easydblab.conf" "${TEST_SH}" "${SCRIPT}" "$@" 2>&1
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

# --- apply steers ENA links only ---
setup 4 2
run apply > /dev/null
if [[ "$(cat "${SYS}/class/net/ens5/queues/rx-1/rps_cpus")" == f && "$(cat "${SYS}/class/net/lo/queues/rx-0/rps_cpus")" == 0 ]]; then
  pass "apply steers the ENA link and leaves loopback alone"
else
  fail "apply should steer ens5 only, got ens5=$(cat "${SYS}/class/net/ens5/queues/rx-1/rps_cpus") lo=$(cat "${SYS}/class/net/lo/queues/rx-0/rps_cpus")"
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
run apply > /dev/null
if grep -q "skipped CPU governor on db0: unknown instance type" "${WORK}/logger.log"; then
  pass "an unreachable instance metadata service says unknown instance type"
else
  fail "expected unknown instance type, got $(cat "${WORK}/logger.log")"
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

# --- idle states exposed: every state deeper than C1 is disabled ---
setup 2 1
for i in 0 1; do
  for s in "0 POLL 0" "1 C1 2" "2 C1E 10" "3 C6 133"; do
    read -r n name latency <<< "$s"
    d="${SYS}/devices/system/cpu/cpu${i}/cpuidle/state${n}"
    mkdir -p "$d"
    echo "$name" > "$d/name"
    echo "$latency" > "$d/latency"
    echo 0 > "$d/disable"
  done
done
run apply > /dev/null
states=""
for n in 0 1 2 3; do states="${states}$(cat "${SYS}/devices/system/cpu/cpu1/cpuidle/state${n}/disable")"; done
if [[ "$states" == 0011 ]]; then
  pass "C1E and C6 are disabled, POLL and C1 stay"
else
  fail "expected disable flags 0011, got ${states}"
fi

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

# --- verify: a node with 3 queues passes with the rounded flow count the kernel reads back ---
setup 4 3
tuned_sysctls
run apply > /dev/null
for q in 0 1 2; do echo 16384 > "${SYS}/class/net/ens5/queues/rx-${q}/rps_flow_cnt"; done
OUTPUT=$(run verify)
STATUS=$?
if [[ "$STATUS" -eq 0 && -z "$OUTPUT" ]]; then
  pass "verify accepts the power-of-two flow count on 3 receive queues"
else
  fail "verify with 3 queues should pass, got ${STATUS}: ${OUTPUT}"
fi

# --- the instance type is read from metadata once per run, however many settings are skipped ---
setup 2 1
run apply > /dev/null
if [[ $(wc -l < "${WORK}/curl.log") -le 2 && $(grep -c "unknown instance type" "${WORK}/logger.log") -eq 2 ]]; then
  pass "one metadata lookup (token and type) serves every skip line"
else
  fail "expected at most 2 metadata calls for 2 skips, got $(wc -l < "${WORK}/curl.log"): $(cat "${WORK}/logger.log")"
fi

# --- verify: each kind of mismatch fails, naming the setting, the wanted and the read value ---
expect_mismatch() {
  local description="$1" expected="$2"
  OUTPUT=$(run verify)
  STATUS=$?
  if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"$expected"* ]]; then
    pass "verify fails on ${description}"
  else
    fail "verify on ${description}: expected status 1 and '${expected}', got ${STATUS}: ${OUTPUT}"
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

tuned_node
OUTPUT=$(IRQBALANCE=inactive run verify)
STATUS=$?
if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"ERROR: irqbalance: wanted active, got inactive"* ]]; then
  pass "verify fails when irqbalance is not running"
else
  fail "expected irqbalance failure, got ${STATUS}: ${OUTPUT}"
fi

tuned_node
OUTPUT=$(CASSANDRA_MEMLOCK=8388608 run verify)
STATUS=$?
if [[ "$STATUS" -eq 1 && "$OUTPUT" == *"ERROR: cassandra LimitMEMLOCK: wanted infinity, got 8388608"* ]]; then
  pass "verify fails when a database service lacks its limits"
else
  fail "expected the cassandra limit failure, got ${STATUS}: ${OUTPUT}"
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
