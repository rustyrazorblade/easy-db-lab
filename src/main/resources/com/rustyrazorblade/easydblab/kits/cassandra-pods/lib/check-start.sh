#!/usr/bin/env bash
# Start checks for the cassandra-pods kit; nothing has been created yet when this runs.
#
# Resolves the image (--image, else cassandra:<--version>) and writes it, with the build label,
# to resolved-image.env for apply-statefulset.sh. Exits 1, naming the cause, when:
#   - --nodes is more than the number of db nodes
#   - the AMI-based Cassandra is running on a db node (both would report under job="cassandra")
#   - --nodes is not a whole number >= 1, or --heap is not a whole number of G or M
#   - a db node cannot be checked over ssh (fail fast: an unchecked node may be running the AMI Cassandra)
#   - the image's manifest is definitely not in its registry (404). A 401/403 (private image) warns
#     and continues; any other answer fails with curl's own message.
#
# Reads CASSANDRA_VERSION, CASSANDRA_IMAGE, CASSANDRA_NODES, DB_NODE_COUNT.
set -euo pipefail

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

nodes="${CASSANDRA_NODES:-${DB_NODE_COUNT}}"
case "${nodes}" in
  '' | *[!0-9]* | 0*)
    fail "ERROR: --nodes '${nodes}' is not valid; it must be a whole number of at least 1."
    ;;
esac
if [ "${nodes}" -gt "${DB_NODE_COUNT}" ]; then
  fail "ERROR: --nodes ${nodes} is more than the ${DB_NODE_COUNT} db nodes in this cluster; the kit runs at most one pod per db node."
fi

# --heap is a whole number of G or M (for example 4G, 512M); the pod derives HEAP_NEWSIZE from it.
heap="${CASSANDRA_HEAP:-}"
if [ -n "${heap}" ]; then
  amount="${heap%[GgMm]}"
  case "${heap}:${amount}" in
    "${amount}:"*) fail "ERROR: --heap '${heap}' is not valid; use a whole number followed by G or M, such as 4G or 512M." ;;
  esac
  case "${amount}" in
    '' | *[!0-9]* | 0*) fail "ERROR: --heap '${heap}' is not valid; use a whole number followed by G or M, such as 4G or 512M." ;;
  esac
fi

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  image="${CASSANDRA_IMAGE}"
else
  image="cassandra:${CASSANDRA_VERSION}"
fi

i=0
while [ "${i}" -lt "${DB_NODE_COUNT}" ]; do
  # systemctl is-active exits 0 when active and 3 (or 4) when not; anything else, such as ssh's
  # 255, means the node could not be checked, which fails start rather than assuming it is clear.
  rc=0
  err="$(ssh "db${i}" systemctl is-active --quiet cassandra 2>&1 > /dev/null)" || rc=$?
  case "${rc}" in
    0) fail "ERROR: the AMI Cassandra is running on db${i}. It would report under the same metrics job (job=\"cassandra\") as this kit; run 'easy-db-lab cassandra stop' first." ;;
    3 | 4) ;;
    *) fail "ERROR: could not check db node db${i}: ${err:-ssh exited ${rc}}" ;;
  esac
  i=$((i + 1))
done

# Split <registry>/<repository>:<tag>. A first component with no '.' or ':' and not localhost is a
# Docker Hub namespace, and a Hub name with no namespace is under library/.
name="${image}"
tag=latest
reference=""
case "${name}" in
  *@*)
    reference="${name#*@}"
    name="${name%%@*}"
    ;;
esac
last="${name##*/}"
case "${last}" in
  *:*)
    tag="${last##*:}"
    name="${name%:*}"
    ;;
esac
[ -n "${reference}" ] || reference="${tag}"
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

curl_err="$(mktemp)"
token_body="$(mktemp)"
trap 'rm -f "${curl_err}" "${token_body}"' EXIT
# A registry that answers its token endpoint with 401, 403 or 404 is checked anonymously; any other
# failure (network, 5xx) stops start, naming the image.
token_code="$(curl -sS -o "${token_body}" -w '%{http_code}' "${token_url}" 2> "${curl_err}")" || token_code=""
token=""
case "${token_code}" in
  200) token="$(sed -n 's/.*"token" *: *"\([^"]*\)".*/\1/p' "${token_body}")" ;;
  401 | 403 | 404) ;;
  *)
    detail="$(cat "${curl_err}")"
    fail "ERROR: could not check image ${image}: the token request to ${registry} answered '${token_code:-no answer}'${detail:+: ${detail}}. Check the network or SOCKS tunnel."
    ;;
esac
code="$(curl -sS -o /dev/null -w '%{http_code}' -I \
  ${token:+-H "Authorization: Bearer ${token}"} \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
  "https://${registry}/v2/${repository}/manifests/${reference}" 2> "${curl_err}")" || code=""
case "${code}" in
  200) ;;
  401 | 403)
    echo "WARNING: could not verify the image anonymously (HTTP ${code}), continuing. Registry ${registry} wants credentials for ${repository}; a bad name will surface when the pod pulls it."
    ;;
  404)
    fail "ERROR: image ${image} was not found (registry ${registry} answered 404 for ${repository}:${reference}). Check --version or --image."
    ;;
  *)
    detail="$(cat "${curl_err}")"
    fail "ERROR: could not check image ${image} (registry ${registry} answered '${code:-no answer}'${detail:+: ${detail}}). Check the network or SOCKS tunnel, --version and --image."
    ;;
esac

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  build="${CASSANDRA_IMAGE}"
else
  build="${CASSANDRA_VERSION}"
fi
printf 'RESOLVED_IMAGE=%s\nRESOLVED_BUILD=%s\n' "${image}" "${build}" > resolved-image.env
echo "Image ${image} found."
