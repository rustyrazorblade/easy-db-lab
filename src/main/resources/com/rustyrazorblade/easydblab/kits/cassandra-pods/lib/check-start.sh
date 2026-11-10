#!/usr/bin/env bash
# Start checks for the cassandra-pods kit; nothing has been created yet when this runs.
#
# Resolves the image (--image, else cassandra:<--version>) and writes it, with the build label,
# to resolved-image.env for apply-statefulset.sh. Exits 1, naming the cause, when:
#   - --nodes is more than the number of db nodes
#   - the AMI-based Cassandra is running on a db node (both would report under job="cassandra")
#   - the image's manifest is not in its registry (anonymous HEAD through the workspace curl)
#
# Reads CASSANDRA_VERSION, CASSANDRA_IMAGE, CASSANDRA_NODES, DB_NODE_COUNT.
set -euo pipefail

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

nodes="${CASSANDRA_NODES:-${DB_NODE_COUNT}}"
if [ "${nodes}" -gt "${DB_NODE_COUNT}" ]; then
  fail "ERROR: --nodes ${nodes} is more than the ${DB_NODE_COUNT} db nodes in this cluster; the kit runs at most one pod per db node."
fi

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  image="${CASSANDRA_IMAGE}"
else
  image="cassandra:${CASSANDRA_VERSION}"
fi

i=0
while [ "${i}" -lt "${DB_NODE_COUNT}" ]; do
  if ssh "db${i}" systemctl is-active --quiet cassandra > /dev/null 2>&1; then
    fail "ERROR: the AMI Cassandra is running on db${i}. It would report under the same metrics job (job=\"cassandra\") as this kit; run 'easy-db-lab cassandra stop' first."
  fi
  i=$((i + 1))
done

# Split <registry>/<repository>:<tag>. A first component with no '.' or ':' and not localhost is a
# Docker Hub namespace, and a Hub name with no namespace is under library/.
name="${image}"
tag=latest
last="${name##*/}"
case "${last}" in
  *:*)
    tag="${last##*:}"
    name="${name%:*}"
    ;;
esac
first="${name%%/*}"
case "${first}" in
  *.* | *:* | localhost)
    registry="${first}"
    repository="${name#*/}"
    ;;
  *)
    registry="registry-1.docker.io"
    repository="${name}"
    case "${repository}" in
      */*) ;;
      *) repository="library/${repository}" ;;
    esac
    ;;
esac
if [ "${registry}" = "registry-1.docker.io" ]; then
  token_url="https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repository}:pull"
else
  token_url="https://${registry}/token?service=${registry}&scope=repository:${repository}:pull"
fi

token="$(curl -fsS "${token_url}" 2> /dev/null | sed -n 's/.*"token" *: *"\([^"]*\)".*/\1/p')" || token=""
code="$(curl -sS -o /dev/null -w '%{http_code}' -I \
  ${token:+-H "Authorization: Bearer ${token}"} \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
  "https://${registry}/v2/${repository}/manifests/${tag}" 2> /dev/null)" || code=""
if [ "${code}" != "200" ]; then
  fail "ERROR: image ${image} was not found (registry ${registry} answered '${code:-no answer}' for ${repository}:${tag}). Check --version or --image."
fi

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  build="${CASSANDRA_IMAGE}"
else
  build="${CASSANDRA_VERSION}"
fi
printf 'RESOLVED_IMAGE=%s\nRESOLVED_BUILD=%s\n' "${image}" "${build}" > resolved-image.env
echo "Image ${image} found."
