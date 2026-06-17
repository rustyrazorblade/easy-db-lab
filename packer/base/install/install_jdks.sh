#!/usr/bin/env bash
#
# Installs every JDK required by the Cassandra versions we support (8, 11, 17, 21) plus their
# debug-symbol packages.
#
# apt's verbose output is redirected to a log file rather than streamed over SSH. This is the
# longest step and floods hundreds of unpack lines; that heavy output disrupts packer's SSH
# connection (especially through restrictive corporate networks), causing packer to abort with a
# spurious non-zero status even though the install succeeds on the instance. Keeping the stream
# quiet lets packer's keepalive hold the connection for the duration.
#
# Env vars (set by the Packer provisioner):
#   ARCH  dpkg architecture suffix used in the JVM paths (e.g. amd64, arm64)
set -euo pipefail

ARCH="${ARCH:?ARCH must be set}"
LOG=/tmp/jdk-install.log

# On failure, surface the tail of the captured log (since it isn't streamed live).
trap 'echo "=== JDK install FAILED — tail of ${LOG} ==="; tail -50 "${LOG}" 2>/dev/null || true' ERR

PACKAGES="openjdk-8-jdk openjdk-8-dbg \
          openjdk-11-jdk openjdk-11-dbg \
          openjdk-17-jdk openjdk-17-dbg \
          openjdk-21-jdk openjdk-21-dbg"

echo "Installing JDKs (verbose output -> ${LOG} to keep the packer SSH stream quiet)..."
sudo apt-get update >>"${LOG}" 2>&1
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y $PACKAGES >>"${LOG}" 2>&1
echo "JDK packages installed."

# Default to Java 11; tolerate update-java-alternatives's known non-zero exit when some
# components are absent, matching the previous behavior. Output also goes to the log (it is noisy).
sudo update-java-alternatives -s "/usr/lib/jvm/java-1.11.0-openjdk-${ARCH}" >>"${LOG}" 2>&1 || true
sudo sed -i '/hl jexec.*/d' "/usr/lib/jvm/.java-1.8.0-openjdk-${ARCH}.jinfo" || true
echo "JDK setup complete (Java 11 default)."
