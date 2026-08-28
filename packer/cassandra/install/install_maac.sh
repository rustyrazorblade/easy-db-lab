#!/bin/bash
# Install Management API for Apache Cassandra (MAAC) metrics agent
# Downloads version-specific JARs that expose Cassandra metrics as Prometheus endpoint on port 9000
set -euo pipefail

MAAC_VERSION="0.1.125"
MAAC_URL="https://github.com/k8ssandra/management-api-for-apache-cassandra/releases/download/v${MAAC_VERSION}/jars.zip"
MAAC_BASE="/opt/management-api"

# The 6.0 agent is not in the release's jars.zip. Upstream builds management-api-agent-6.0.x only
# under its `trunk` maven profile, against a cassandra-all SNAPSHOT that is published nowhere, so
# the released zip contains 4.x, 4.1.x and 5.0.x only. The built jar does ship - in k8ssandra's
# own nightly container images - so that is where it is taken from. The same 6.0.x jar serves
# Cassandra 6.0 and 7.0/trunk; upstream's own 7.0 nightly image ships exactly this jar.
#
# Pinned to a dated tag rather than -latest so a rebake is reproducible.
MAAC_TRUNK_IMAGE="k8ssandra/cass-management-api"
MAAC_TRUNK_TAG="6.0-nightly-20260820"
MAAC_TRUNK_JAR_RE='^opt/management-api/datastax-mgmtapi-agent-6\.0\.x-.*\.jar$'

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

# Use the shared S3 download cache when present; otherwise download directly (local script
# tests, or no cache configured).
if [ -f /usr/local/lib/edl-cache.sh ]; then
    # shellcheck disable=SC1091
    source /usr/local/lib/edl-cache.sh
else
    cached_fetch() { echo "no S3 cache; downloading $1"; curl -fsSL --retry 3 "$1" -o "$3"; }
fi
EDL_S3_BUCKET="${EDL_S3_BUCKET:-}"

# oci_extract_file <repo> <tag> <path-regex> <dest>
#
# Copies one file out of a linux/amd64 container image using the registry HTTP API only - no
# docker, no containerd, no skopeo, none of which the build instance has. Layers are listed from
# the image manifest and searched in order; the first one holding a matching path wins.
oci_extract_file() {
    local repo="$1" tag="$2" path_re="$3" dest="$4"
    local accept token index digest manifest layer match work

    accept="application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

    token=$(curl -fsSL --retry 3 "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repo}:pull" | jq -r .token)
    index=$(curl -fsSL --retry 3 -H "Authorization: Bearer ${token}" -H "Accept: ${accept}" \
        "https://registry-1.docker.io/v2/${repo}/manifests/${tag}")

    # A multi-arch index needs one more hop to the amd64 manifest; a single-arch manifest is
    # already the thing with .layers on it.
    digest=$(echo "$index" | jq -r '.manifests[]? | select(.platform.architecture=="amd64" and .platform.os=="linux") | .digest' | head -n 1)
    if [ -n "$digest" ] && [ "$digest" != "null" ]; then
        manifest=$(curl -fsSL --retry 3 -H "Authorization: Bearer ${token}" -H "Accept: ${accept}" \
            "https://registry-1.docker.io/v2/${repo}/manifests/${digest}")
    else
        manifest="$index"
    fi

    work="${TEMP_DIR}/oci"
    mkdir -p "$work"

    for layer in $(echo "$manifest" | jq -r '.layers[].digest'); do
        curl -fsSL --retry 3 -H "Authorization: Bearer ${token}" \
            "https://registry-1.docker.io/v2/${repo}/blobs/${layer}" -o "${work}/layer.tgz"
        match=$(tar -tzf "${work}/layer.tgz" 2>/dev/null | grep -E "$path_re" | head -n 1 || true)
        if [ -n "$match" ]; then
            tar -xzf "${work}/layer.tgz" -C "$work" "$match"
            mv "${work}/${match}" "$dest"
            rm -f "${work}/layer.tgz"
            echo "extracted ${match} from ${repo}:${tag}"
            return 0
        fi
        rm -f "${work}/layer.tgz"
    done

    echo "ERROR: no path matching ${path_re} in ${repo}:${tag}" >&2
    return 1
}

# Same contract as cached_fetch, but the origin is a container image rather than a URL. The
# extraction walks up to a few hundred MB of layers, so it is worth caching the 18MB result.
cached_oci_extract() {
    local repo="$1" tag="$2" path_re="$3" key="$4" dest="$5"
    local s3="s3://${EDL_S3_BUCKET}/download-cache/${key}"

    if [ -n "$EDL_S3_BUCKET" ] && aws s3 cp "$s3" "$dest" --no-progress 2>/dev/null; then
        echo "cache hit:  $key"
        return 0
    fi

    echo "cache miss: $key (extracting from ${repo}:${tag})"
    oci_extract_file "$repo" "$tag" "$path_re" "$dest"

    if [ -n "$EDL_S3_BUCKET" ]; then
        aws s3 cp "$dest" "$s3" --no-progress 2>/dev/null || echo "WARN: failed to populate cache for $key"
    fi
}

echo "Downloading MAAC agent v${MAAC_VERSION}..."
cached_fetch "${MAAC_URL}" "maac/v${MAAC_VERSION}/jars.zip" "${TEMP_DIR}/jars.zip"

echo "Extracting MAAC JARs..."
cd "${TEMP_DIR}"
unzip -q jars.zip

# Install version-specific agent JARs
sudo mkdir -p "${MAAC_BASE}/4.0" "${MAAC_BASE}/4.1" "${MAAC_BASE}/5.0" "${MAAC_BASE}/6.0" "${MAAC_BASE}/7.0" "${MAAC_BASE}/configs"

sudo cp "management-api-agent-4.x/target/datastax-mgmtapi-agent-4.x-${MAAC_VERSION}.jar" \
    "${MAAC_BASE}/4.0/datastax-mgmtapi-agent.jar"

sudo cp "management-api-agent-4.1.x/target/datastax-mgmtapi-agent-4.1.x-${MAAC_VERSION}.jar" \
    "${MAAC_BASE}/4.1/datastax-mgmtapi-agent.jar"

sudo cp "management-api-agent-5.0.x/target/datastax-mgmtapi-agent-5.0.x-${MAAC_VERSION}.jar" \
    "${MAAC_BASE}/5.0/datastax-mgmtapi-agent.jar"

echo "Extracting the 6.0 agent from ${MAAC_TRUNK_IMAGE}:${MAAC_TRUNK_TAG}..."
cached_oci_extract "$MAAC_TRUNK_IMAGE" "$MAAC_TRUNK_TAG" "$MAAC_TRUNK_JAR_RE" \
    "maac/${MAAC_TRUNK_TAG}/datastax-mgmtapi-agent-6.0.x.jar" "${TEMP_DIR}/agent-6.0.x.jar"

sudo cp "${TEMP_DIR}/agent-6.0.x.jar" "${MAAC_BASE}/6.0/datastax-mgmtapi-agent.jar"
sudo cp "${TEMP_DIR}/agent-6.0.x.jar" "${MAAC_BASE}/7.0/datastax-mgmtapi-agent.jar"

# Create metrics collector config
sudo tee "${MAAC_BASE}/configs/metrics-collector.yaml" > /dev/null <<'EOF'
endpoint:
  address: "127.0.0.1"
  port: 9000
EOF

# Set ownership
sudo chown -R cassandra: "${MAAC_BASE}"

echo "MAAC agent v${MAAC_VERSION} installed successfully"
echo "  4.0 JAR: ${MAAC_BASE}/4.0/datastax-mgmtapi-agent.jar"
echo "  4.1 JAR: ${MAAC_BASE}/4.1/datastax-mgmtapi-agent.jar"
echo "  5.0 JAR: ${MAAC_BASE}/5.0/datastax-mgmtapi-agent.jar"
echo "  6.0 JAR: ${MAAC_BASE}/6.0/datastax-mgmtapi-agent.jar (from ${MAAC_TRUNK_IMAGE}:${MAAC_TRUNK_TAG})"
echo "  7.0 JAR: ${MAAC_BASE}/7.0/datastax-mgmtapi-agent.jar (same 6.0.x agent, as upstream ships it)"
echo "  Config:  ${MAAC_BASE}/configs/metrics-collector.yaml"
