#!/usr/bin/env bash
#
# Installs sjk.jar via the S3 download cache (falls back to a direct download when absent).
set -euo pipefail

echo "=== Running: install_sjk.sh ==="

SJK_VERSION="0.21"

if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi

cached_fetch \
    "https://repo1.maven.org/maven2/org/gridkit/jvmtool/sjk/${SJK_VERSION}/sjk-${SJK_VERSION}.jar" \
    "sjk/${SJK_VERSION}/sjk-${SJK_VERSION}.jar" \
    /tmp/sjk.jar
sudo mv /tmp/sjk.jar /usr/local/lib/sjk.jar

echo "✓ install_sjk.sh completed successfully"
