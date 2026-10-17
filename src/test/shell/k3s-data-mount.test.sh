#!/usr/bin/env bash
#
# Unit tests for the data-mount relocation in the runtime K3s start scripts
# (services/start-k3s-server.sh and services/start-k3s-agent.sh).
#
# Database data and pod logs must never be on a node's root volume. Each script moves the K3s data
# directory and /var/log/pods onto /mnt/db1 and leaves a symlink behind, before K3s starts. The
# relocation is one shell function, relocate_to_data_mount; this test extracts it from each script
# and runs it against temporary directories, with `mountpoint` stubbed. No node, no root.
#
# Run directly:  bash src/test/shell/k3s-data-mount.test.sh
# Or via gradle: ./gradlew testK3sDataMount

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SERVICES="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/services"
SCRIPTS=("${SERVICES}/start-k3s-server.sh" "${SERVICES}/start-k3s-agent.sh")

tests_run=0
tests_failed=0
WORK=""
trap 'rm -rf "${WORK}"' EXIT

fail() {
  echo "FAIL: $1"
  tests_failed=$((tests_failed + 1))
}

# A fresh work dir with a `mountpoint` stub that reports a mount when STUB_MOUNTED=true.
setup() {
  [ -n "${WORK}" ] && rm -rf "${WORK}"
  WORK="$(mktemp -d)"
  mkdir -p "${WORK}/stubs" "${WORK}/mnt/db1" "${WORK}/var/log"
  cat > "${WORK}/stubs/mountpoint" <<'EOF'
#!/usr/bin/env bash
[ "${STUB_MOUNTED:-false}" = true ]
EOF
  chmod +x "${WORK}/stubs/mountpoint"
}

# Runs the relocation function from script $1 with the remaining arguments.
relocate() {
  local script="$1"
  shift
  (
    PATH="${WORK}/stubs:${PATH}"
    eval "$(sed -n '/^relocate_to_data_mount()/,/^}/p' "${script}")"
    relocate_to_data_mount "$@"
  ) > "${WORK}/out.txt" 2>&1
}

test_each_script_defines_the_relocation_function() {
  for script in "${SCRIPTS[@]}"; do
    tests_run=$((tests_run + 1))
    grep -q '^relocate_to_data_mount()' "${script}" || fail "$(basename "${script}") has no relocate_to_data_mount function"
  done
}

test_each_script_moves_pod_logs_before_k3s_starts() {
  for script in "${SCRIPTS[@]}"; do
    tests_run=$((tests_run + 1))
    local name call start
    name="$(basename "${script}")"
    call="$(grep -n '^relocate_to_data_mount /mnt/db1 /var/log/pods /mnt/db1/container-logs$' "${script}" | head -1 | cut -d: -f1)"
    start="$(grep -n '^systemctl start k3s' "${script}" | head -1 | cut -d: -f1)"
    if [ -z "${call}" ]; then
      fail "${name} does not move /var/log/pods to /mnt/db1/container-logs"
      continue
    fi
    [ -n "${start}" ] && [ "${call}" -lt "${start}" ] || fail "${name} moves /var/log/pods after K3s starts (line ${call}, start ${start:-none})"
    grep -q '^relocate_to_data_mount /mnt/db1 /var/lib/rancher/k3s /mnt/db1/k3s$' "${script}" ||
      fail "${name} no longer moves the K3s data directory to /mnt/db1/k3s"
  done
}

test_existing_logs_move_to_the_data_mount() {
  for script in "${SCRIPTS[@]}"; do
    tests_run=$((tests_run + 1))
    setup
    mkdir -p "${WORK}/var/log/pods/default_p_1/c"
    echo "line" > "${WORK}/var/log/pods/default_p_1/c/0.log"
    STUB_MOUNTED=true relocate "${script}" "${WORK}/mnt/db1" "${WORK}/var/log/pods" "${WORK}/mnt/db1/container-logs" ||
      { fail "$(basename "${script}"): relocation failed: $(cat "${WORK}/out.txt")"; continue; }
    [ -L "${WORK}/var/log/pods" ] || fail "$(basename "${script}"): /var/log/pods is not a symlink"
    [ "$(readlink "${WORK}/var/log/pods")" = "${WORK}/mnt/db1/container-logs" ] ||
      fail "$(basename "${script}"): /var/log/pods points at $(readlink "${WORK}/var/log/pods")"
    [ "$(cat "${WORK}/mnt/db1/container-logs/default_p_1/c/0.log" 2>/dev/null)" = "line" ] ||
      fail "$(basename "${script}"): the existing log was not moved to the data mount"
  done
}

test_a_missing_directory_becomes_a_symlink() {
  for script in "${SCRIPTS[@]}"; do
    tests_run=$((tests_run + 1))
    setup
    STUB_MOUNTED=true relocate "${script}" "${WORK}/mnt/db1" "${WORK}/var/log/pods" "${WORK}/mnt/db1/container-logs" ||
      { fail "$(basename "${script}"): relocation failed: $(cat "${WORK}/out.txt")"; continue; }
    [ -L "${WORK}/var/log/pods" ] && [ -d "${WORK}/mnt/db1/container-logs" ] ||
      fail "$(basename "${script}"): a missing /var/log/pods was not linked to an existing target"
  done
}

test_an_existing_symlink_is_left_alone() {
  for script in "${SCRIPTS[@]}"; do
    tests_run=$((tests_run + 1))
    setup
    mkdir -p "${WORK}/elsewhere"
    ln -s "${WORK}/elsewhere" "${WORK}/var/log/pods"
    STUB_MOUNTED=true relocate "${script}" "${WORK}/mnt/db1" "${WORK}/var/log/pods" "${WORK}/mnt/db1/container-logs" ||
      { fail "$(basename "${script}"): relocation failed: $(cat "${WORK}/out.txt")"; continue; }
    [ "$(readlink "${WORK}/var/log/pods")" = "${WORK}/elsewhere" ] || fail "$(basename "${script}"): an existing symlink was replaced"
    [ ! -e "${WORK}/mnt/db1/container-logs" ] || fail "$(basename "${script}"): a second run created the target again"
  done
}

test_nothing_moves_without_the_data_mount() {
  for script in "${SCRIPTS[@]}"; do
    tests_run=$((tests_run + 1))
    setup
    mkdir -p "${WORK}/var/log/pods"
    STUB_MOUNTED=false relocate "${script}" "${WORK}/mnt/db1" "${WORK}/var/log/pods" "${WORK}/mnt/db1/container-logs" ||
      { fail "$(basename "${script}"): relocation failed: $(cat "${WORK}/out.txt")"; continue; }
    [ -d "${WORK}/var/log/pods" ] && [ ! -L "${WORK}/var/log/pods" ] || fail "$(basename "${script}"): moved with no data mount"
  done
}

test_each_script_defines_the_relocation_function
test_each_script_moves_pod_logs_before_k3s_starts
test_existing_logs_move_to_the_data_mount
test_a_missing_directory_becomes_a_symlink
test_an_existing_symlink_is_left_alone
test_nothing_moves_without_the_data_mount

echo "${tests_run} tests, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
