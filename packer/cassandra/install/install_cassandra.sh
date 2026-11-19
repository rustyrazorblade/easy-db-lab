#!/bin/bash
#
# Bake-time setup of a db node, done once: the cassandra user, the install and data directories,
# cqlsh, and the files every runtime install needs. No Cassandra version is installed here: the
# CLI installs the version a cluster asks for at runtime, with install-cassandra-version
# (`easy-db-lab cassandra use` / `cassandra install`).

## exit unless INSTALL_CASSANDRA=1
if [ -z "${INSTALL_CASSANDRA:-}" ]; then
    echo "INSTALL_CASSANDRA is not set, exiting."
    return 0 2>/dev/null || exit 0
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

# The system default JDK at bake time. use-cassandra selects each version's JDK at runtime.
sudo update-java-alternatives -s "java-1.11.0-openjdk-$(dpkg --print-architecture)" >/tmp/cassandra-setup.log 2>&1

lsblk

# Every installed version gets this appended to its bin/cassandra.in.sh. Packer drops it in /tmp,
# which does not survive a reboot - install it durably so a runtime `cassandra install` finds it.
sudo install -D -m 0644 /tmp/cassandra.in.sh /usr/local/share/easy-db-lab/cassandra.in.sh

# The agent selection helpers that snippet sources on every Cassandra start. Same reason it cannot
# live in /tmp: a runtime `cassandra install` has to find it long after the bake.
sudo install -D -m 0644 /tmp/edl-cassandra-agents.sh /usr/local/lib/edl-cassandra-agents.sh

sudo chown -R cassandra:cassandra /usr/local/cassandra
