#!/usr/bin/env bash
#
# Installs every JDK required by the Cassandra versions we support (8, 11, 17, 21) plus their
# debug-symbol packages. Installs both OpenJDK and Amazon Corretto distributions so users can
# switch between them at runtime via update-java-alternatives.
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

# --- OpenJDK ---
OPENJDK_PACKAGES="openjdk-8-jdk openjdk-8-dbg \
                  openjdk-11-jdk openjdk-11-dbg \
                  openjdk-17-jdk openjdk-17-dbg \
                  openjdk-21-jdk openjdk-21-dbg"

echo "Installing OpenJDK packages (verbose output -> ${LOG})..."
sudo apt-get update >>"${LOG}" 2>&1
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y $OPENJDK_PACKAGES >>"${LOG}" 2>&1
echo "OpenJDK packages installed."

# --- Amazon Corretto ---
echo "Adding Amazon Corretto repository..."
sudo apt-get install -y gnupg software-properties-common >>"${LOG}" 2>&1
wget -qO- https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" \
  | sudo tee /etc/apt/sources.list.d/corretto.list >/dev/null

CORRETTO_PACKAGES="java-1.8.0-amazon-corretto-jdk \
                   java-11-amazon-corretto-jdk \
                   java-17-amazon-corretto-jdk \
                   java-21-amazon-corretto-jdk"

echo "Installing Amazon Corretto packages (verbose output -> ${LOG})..."
sudo apt-get update >>"${LOG}" 2>&1
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y $CORRETTO_PACKAGES >>"${LOG}" 2>&1
echo "Amazon Corretto packages installed."

# Default to OpenJDK 11; tolerate update-java-alternatives's known non-zero exit when some
# components are absent, matching the previous behavior. Output also goes to the log (it is noisy).
sudo update-java-alternatives -s "/usr/lib/jvm/java-1.11.0-openjdk-${ARCH}" >>"${LOG}" 2>&1 || true
sudo sed -i '/hl jexec.*/d' "/usr/lib/jvm/.java-1.8.0-openjdk-${ARCH}.jinfo" || true
echo "JDK setup complete (OpenJDK 11 default)."
