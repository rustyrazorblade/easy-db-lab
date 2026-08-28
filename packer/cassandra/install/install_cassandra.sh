#!/bin/bash
#
# Bake-time driver: global one-time node setup, then install every version declared in
# /etc/cassandra_versions.yaml by calling install-cassandra-version - the same script
# `easy-db-lab cassandra install` runs at runtime, so there is one install code path.

####################################################################
##### THE HEADER OF THIS FILE SHOULD BE SHELL FUNCTIONS ONLY #######
### THE INTENT IS TO SAFELY SOURCE THE FILE WITHOUT SIDE EFFECTS ###
####################################################################

# Version metadata is read from this file; overridable so the functions above can be tested.
YAML=${YAML:-/etc/cassandra_versions.yaml}

## Reads one field of a version's entry, returning an empty string when unset.
## Usage: version_field <version> <field>
version_field() {
  local version="$1" field="$2"
  export version
  yq ".[] | select(.version == env(version)) | .$field // \"\"" "$YAML"
}

## True when a version is declared but deliberately not baked into the AMI.
version_is_lazy() {
  local version="$1"
  export version
  [ "$(yq '.[] | select(.version == env(version)) | .lazy // false' "$YAML")" = "true" ]
}

## Builds the install-cassandra-version argument list for a version into INSTALL_ARGS.
## An array, not a string: ant_flags can contain spaces that must stay one argument.
version_install_args() {
  local version="$1"
  INSTALL_ARGS=("$version")

  local url branch java ant_flags
  url=$(version_field "$version" url)
  if [ -n "$url" ]; then
    INSTALL_ARGS+=(--url "$url")
  fi
  branch=$(version_field "$version" branch)
  if [ -n "$branch" ]; then
    INSTALL_ARGS+=(--branch "$branch")
  fi
  java=$(version_field "$version" java)
  if [ -n "$java" ]; then
    INSTALL_ARGS+=(--java "$java")
  fi
  ant_flags=$(version_field "$version" ant_flags)
  if [ -n "$ant_flags" ]; then
    INSTALL_ARGS+=(--ant-flags "$ant_flags")
  fi
  return 0
}

####################################################################
###### DO NOT ADD ANYTHING ABOVE THIS LINE THAT MAKES CHANGES ######
###### TO THE FILE SYSTEM OR DEPENDS ON EXTERNAL RESOURCES #########
###### SHELL FUNCTIONS AND ALIASES ARE OK ##########################
####################################################################

## exit unless INSTALL_CASSANDRA=1
if [ -z "${INSTALL_CASSANDRA:-}" ]; then
    echo "INSTALL_CASSANDRA is not set, exiting."
    return
    exit 0
fi

# Enable strict error handling
set -euo pipefail
set -x

# Trap errors and report line number
trap 'echo "ERROR: Installation failed at line $LINENO with exit code $?" >&2; exit 1' ERR

# creating cassandra user with UID 999 to match the cassandra-sidecar container image
sudo useradd -m -u 999 cassandra

sudo mkdir -p /usr/local/cassandra
sudo mkdir -p /mnt/db1/cassandra/logs
sudo chown -R cassandra:cassandra /mnt/db1/cassandra

# Install cqlsh globally (works with all Cassandra versions)
echo "Installing cqlsh via uv..."
uv tool install cqlsh

# used to skip the expensive checkstyle checks

sudo update-java-alternatives -s "java-1.11.0-openjdk-$(dpkg --print-architecture)" >/tmp/cassandra-setup.log 2>&1

lsblk

# Every installed version gets this appended to its bin/cassandra.in.sh. Packer drops it in /tmp,
# which does not survive a reboot - install it durably so a runtime `cassandra install` finds it.
sudo install -D -m 0644 /tmp/cassandra.in.sh /usr/local/share/easy-db-lab/cassandra.in.sh

VERSIONS=$(yq '.[].version' "$YAML")
echo "Installing versions: $VERSIONS"

# Install every version concurrently. install-cassandra-version runs in its own temp working
# directory and writes only version-specific paths, so the fan-out is parallel-safe. The
# shared/global setup above (user + directory creation, update-java-alternatives) already ran
# serially.
loop_start=$(date +%s)
echo "Starting Cassandra version install loop at epoch $loop_start"

declare -A version_pids=()
declare -A version_logs=()
installed_versions=()

for version in $VERSIONS; do
  # A lazy version is declared but not baked: it ships in /etc/cassandra_versions.yaml so it is
  # discoverable and installable at runtime, costing nothing at AMI build time.
  if version_is_lazy "$version"; then
    echo "Skipping lazy version $version - install it at runtime with 'easy-db-lab cassandra install $version'"
    continue
  fi

  version_install_args "$version"

  log="/tmp/cassandra-install-${version}.log"
  version_logs["$version"]="$log"
  installed_versions+=("$version")
  # Capture each version's output (incl. set -x trace) to its own log so parallel jobs do
  # not interleave; the logs are replayed serially below for readable success/failure output.
  install-cassandra-version "${INSTALL_ARGS[@]}" >"$log" 2>&1 &
  version_pids["$version"]=$!
done

# Fail loud: wait on every job, replay its log, and record any version that exited non-zero.
install_failed=()
for version in "${!version_pids[@]}"; do
  if wait "${version_pids[$version]}"; then
    echo "===== install log: $version (OK) ====="
    cat "${version_logs[$version]}"
  else
    echo "===== install log: $version (FAILED) ====="
    cat "${version_logs[$version]}"
    install_failed+=("$version")
  fi
done

loop_end=$(date +%s)
echo "Cassandra version install loop took $((loop_end - loop_start))s"

if [ "${#install_failed[@]}" -ne 0 ]; then
  echo "ERROR: Cassandra install failed for version(s): ${install_failed[*]}" >&2
  exit 1
fi

# Clean up the Maven cache once, after all (potential) source builds have finished. This is
# shared/global state (HOME), so it is kept out of the parallel fan-out.
rm -rf ~/.m2 || true

# Final verification - ensure all non-lazy versions are installed
echo ""
echo "Verifying all versions were installed successfully..."
for version in "${installed_versions[@]}"; do
    if [[ ! -d "/usr/local/cassandra/$version" ]]; then
        echo "ERROR: Final verification failed - version $version not found in /usr/local/cassandra/"
        exit 1
    fi
    echo "✓ Version $version verified"
done

echo ""
echo "All Cassandra versions installed and verified successfully!"

sudo chown -R cassandra:cassandra /usr/local/cassandra
