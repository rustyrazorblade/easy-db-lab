#!/bin/bash

####################################################################
##### THE HEADER OF THIS FILE SHOULD BE SHELL FUNCTIONS ONLY #######
### THE INTENT IS TO SAFELY SOURCE THE FILE WITHOUT SIDE EFFECTS ###
####################################################################

## Downloads the latest patch release of a cassandra version
## This saves us from having to update cassandra_versions.yaml
## every time a new patch release is made
download_cassandra_version() {
    # Check if version prefix is provided
    if [ -z "$1" ]; then
        echo "Usage: download_cassandra_version <version-prefix>"
        return 1
    fi

    # Assign the version prefix from the first argument
    version_prefix="$1"

    # Get the list of versions from the Cassandra download page
    versions=$(curl -s https://dlcdn.apache.org/cassandra/ | grep -o 'href="[0-9]\+\.[0-9]\+\.[0-9]\+/\"' | sed 's/href="//' | sed 's/\/"//')

    # Find the latest version that matches the given prefix
    full_version=$(echo "$versions" | grep "^$version_prefix" | sort -V | tail -n 1)

    # Check if a version was found
    if [ -z "$full_version" ]; then
        echo "ERROR: No matching version found for prefix $version_prefix"
        return 1
    fi

    # Construct the download URL
    archive="apache-cassandra-$full_version-bin.tar.gz"
    download_url="https://dlcdn.apache.org/cassandra/$full_version/$archive"

    # Download the file (via S3 cache, keyed by the resolved patch version)
    echo "Downloading Cassandra version $full_version from $download_url..."
    cached_fetch "$download_url" "cassandra-dist/$full_version/$archive" "$archive" || {
        echo "ERROR: Failed to download Cassandra version $full_version from $download_url"
        return 1
    }

    # Verify download was successful and file is not empty
    if [[ ! -f "$archive" || ! -s "$archive" ]]; then
        echo "ERROR: Downloaded file $archive is missing or empty for version $full_version"
        return 1
    fi

    echo "Download completed successfully."

    # Extract the archive (quiet: verbose file listing floods packer's SSH stream and drops it)
    tar zxf "$archive" || {
        echo "ERROR: Failed to extract $archive for version $full_version"
        return 1
    }

    # Move and verify
    mv "apache-cassandra-$full_version" "$version_prefix" || {
        echo "ERROR: Failed to rename apache-cassandra-$full_version to $version_prefix"
        return 1
    }

    # Verify the directory exists
    if [[ ! -d "$version_prefix" ]]; then
        echo "ERROR: Directory $version_prefix does not exist after extraction and rename"
        return 1
    fi

    return 0
}

####################################################################
###### DO NOT ADD ANYTHING ABOVE THIS LINE THAT MAKES CHANGES ######
###### TO THE FILE SYSTEM OR DEPENDS ON EXTERNAL RESOURCES #########
###### SHELL FUNCTIONS AND ALIASES ARE OK ##########################
####################################################################

## exit unless INSTALL_CASSANDRA=1
if [ -z "$INSTALL_CASSANDRA" ]; then
    echo "INSTALL_CASSANDRA is not set, exiting."
    return
    exit 0
fi

# Enable strict error handling
set -euo pipefail
set -x

# Shared S3 download cache (provides cached_fetch, used by download_cassandra_version above).
# Falls back to a direct download when the cache lib is absent (local script tests / no cache).
if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi

# Trap errors and report line number
trap 'echo "ERROR: Installation failed at line $LINENO with exit code $?" >&2; exit 1' ERR

# creating cassandra user with UID 999 to match the cassandra-sidecar container image
sudo useradd -m -u 999 cassandra
mkdir cassandra

sudo mkdir -p /usr/local/cassandra
sudo mkdir -p /mnt/db1/cassandra/logs
sudo chown -R cassandra:cassandra /mnt/db1/cassandra

# Install cqlsh globally (works with all Cassandra versions)
echo "Installing cqlsh via uv..."
uv tool install cqlsh

# used to skip the expensive checkstyle checks

sudo update-java-alternatives -s "java-1.11.0-openjdk-$(dpkg --print-architecture)" >/tmp/cassandra-setup.log 2>&1

lsblk

# Change to cassandra directory with error checking
cd cassandra || {
    echo "ERROR: Cannot change to cassandra directory"
    exit 1
}

YAML=/etc/cassandra_versions.yaml
VERSIONS=$(yq '.[].version' "$YAML")
echo "Installing versions: $VERSIONS"

## Installs a single Cassandra version end-to-end: download (or git build), place under
## /usr/local/cassandra/$version, and apply the per-version configuration.
##
## Safe to run as a background job: it operates in its own private temp working directory
## (so concurrent versions never share extraction state, e.g. the `find . -name '*cassandra*'`
## below only ever sees its own tarball) and otherwise writes only version-specific paths.
## Genuinely shared/global steps (user + directory creation, update-java-alternatives, the
## Maven cache cleanup) are kept serial outside this function.
install_cassandra_version() {
  local version="$1"
  # yq's env(version) reads this from the environment; the export stays isolated to this
  # subshell when the function is backgrounded, so parallel versions never clobber each other.
  export version

  local workdir
  workdir=$(mktemp -d) || {
      echo "ERROR: Failed to create temp working directory for version $version"
      return 1
  }
  cd "$workdir" || {
      echo "ERROR: Cannot change to working directory $workdir for version $version"
      return 1
  }

  echo "Configuring version: $version"

  local URL BRANCH
  URL=$(yq '.[] | select(.version == env(version)) | .url // ""' "$YAML")
  echo "$URL"
  BRANCH=$(yq '.[] | select(.version == env(version)) | .branch // ""' "$YAML")

  # if $version is set, $URL is blank, and $BRANCH is blank
  if [[ $version != "" && $URL == "" && $BRANCH == "" ]]; then
    download_cassandra_version "$version" || {
        echo "ERROR: download_cassandra_version failed for version $version"
        return 1
    }

    # check if $version exists in the current directory
    if [[ ! -d $version ]]; then
      echo "ERROR: Failed to download Cassandra version $version - directory not found"
      return 1
    fi

    sudo mv "$version" "/usr/local/cassandra/$version" || {
        echo "ERROR: Failed to move $version to /usr/local/cassandra/"
        return 1
    }

  # if a URL is set and ends in .tar.gz, download it (via the S3 cache, version-keyed)
  elif [[ $URL == *.tar.gz ]]; then
    echo "Downloading $URL for version $version"

    local archive_file
    archive_file=$(basename "$URL")

    # Route the nightly tarball through the S3 download cache (keyed by version) so it is not
    # re-fetched cold from GitHub on every build. cached_fetch falls back to a direct download
    # when no cache bucket is configured (local packer Docker tests).
    cached_fetch "$URL" "cassandra-dist/$version/$archive_file" "$archive_file" || {
        echo "ERROR: Failed to download version $version from $URL"
        return 1
    }

    # Verify download succeeded and file is not empty
    if [[ ! -f "$archive_file" || ! -s "$archive_file" ]]; then
        echo "ERROR: Downloaded file $archive_file is missing or empty for version $version"
        return 1
    fi

    echo "Extracting $archive_file"
    tar zxf "$archive_file" || {
        echo "ERROR: Failed to extract $archive_file for version $version"
        return 1
    }

    rm -f "$archive_file"

    # Find the extracted directory (should be the only directory created)
    # Look for directories starting with 'apache-cassandra' or 'cassandra'
    local f
    f=$(find . -maxdepth 1 -type d -name '*cassandra*' ! -name '.' -printf '%f\n' | head -n 1)

    # Verify extracted directory exists
    if [[ -z "$f" || ! -d "$f" ]]; then
        echo "ERROR: Could not find extracted Cassandra directory for version $version"
        echo "Available directories:"
        ls -la
        return 1
    fi

    echo "Found extracted directory: $f"

    sudo mv "$f" "/usr/local/cassandra/$version" || {
        echo "ERROR: Failed to move $f to /usr/local/cassandra/$version"
        return 1
    }

  else
    # Clone the git repos specified in the yaml file (ending in .git)
    # Use the directory name of the version field as the dir name
    # as the directory to clone into
    # checkout the branch specified in the yaml file
    # do a build and create the tar.gz
    local ANT_FLAGS
    ANT_FLAGS=$(yq '.[] | select(.version == env(version)) | .ant_flags // ""' "$YAML")
    # all builds work with JDK 11 for now

    echo "Cloning repo for version $version from $URL branch $BRANCH"
    git clone --depth=1 --single-branch --branch "$BRANCH" "$URL" "$version" || {
        echo "ERROR: Git clone failed for version $version from $URL branch $BRANCH"
        return 1
    }

    # Verify clone was successful
    if [[ ! -d "$version/.git" ]]; then
        echo "ERROR: Git clone incomplete for version $version - .git directory not found"
        return 1
    fi

    echo "Building version $version with ant"
    # Per-version build log so concurrent builds do not clobber a shared file.
    local ant_log="/tmp/ant-build-$version.log"
    (
      cd "$version" || exit 1
      # Quiet: ant output is voluminous and floods packer's SSH stream
      # shellcheck disable=SC2086
      ant realclean >"$ant_log" 2>&1 && ant -Dno-checkstyle=true $ANT_FLAGS >>"$ant_log" 2>&1 || exit 1
      rm -rf .git
    ) || {
        echo "ERROR: Ant build failed for version $version"
        return 1
    }

    sudo mv "$version" "/usr/local/cassandra/$version" || {
        echo "ERROR: Failed to move built version $version to /usr/local/cassandra/"
        return 1
    }
  fi

  # Verify the version was successfully moved to /usr/local/cassandra/
  if [[ ! -d "/usr/local/cassandra/$version" ]]; then
      echo "ERROR: Version $version not found in /usr/local/cassandra/ after installation"
      return 1
  fi

  # at this point the $version is in place, however it was installed
  # do any general customizations in the below subshell
  echo "Configuring version $version"
  (
      cd "/usr/local/cassandra/$version" || exit 1
      rm -rf data
      cp -R conf conf.orig
      # create a pristine backup of the original conf
      sudo cp conf/cassandra.yaml conf/cassandra.orig.yaml
      cat /tmp/cassandra.in.sh >> bin/cassandra.in.sh
  ) || {
      echo "ERROR: Configuration failed for version $version"
      return 1
  }

  # Remove bundled cqlsh from Cassandra 2.x and 3.x to use uv-installed version
  if [[ "$version" == 2.* || "$version" == 3.* ]]; then
    echo "Removing bundled cqlsh from Cassandra $version (using uv-installed version instead)"
    rm -f "/usr/local/cassandra/$version/bin/cqlsh"
    rm -f "/usr/local/cassandra/$version/bin/cqlsh.py"
  fi

  cd / && rm -rf "$workdir"

  echo "✓ Successfully installed and configured version $version"
}

# Install every version concurrently. Each job runs in its own temp working directory and
# writes only version-specific paths, so the fan-out is parallel-safe. The shared/global
# setup above (user + directory creation, update-java-alternatives) already ran serially.
loop_start=$(date +%s)
echo "Starting Cassandra version install loop at epoch $loop_start"

declare -A version_pids=()
declare -A version_logs=()

for version in $VERSIONS; do
  log="/tmp/cassandra-install-${version}.log"
  version_logs["$version"]="$log"
  # Capture each version's output (incl. set -x trace) to its own log so parallel jobs do
  # not interleave; the logs are replayed serially below for readable success/failure output.
  install_cassandra_version "$version" >"$log" 2>&1 &
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

# Final verification - ensure all versions are installed
echo ""
echo "Verifying all versions were installed successfully..."
for version in $VERSIONS; do
    if [[ ! -d "/usr/local/cassandra/$version" ]]; then
        echo "ERROR: Final verification failed - version $version not found in /usr/local/cassandra/"
        exit 1
    fi
    echo "✓ Version $version verified"
done

echo ""
echo "All Cassandra versions installed and verified successfully!"

#rm -rf cassandra
sudo chown -R cassandra:cassandra /usr/local/cassandra
