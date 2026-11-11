#!/usr/bin/env bash
# Start checks for the cassandra-pods kit; nothing has been created yet when this runs.
#
# Resolves the image (--image, else cassandra:<--version>) and writes it, with the build label,
# to resolved-image.env for apply-statefulset.sh. Exits 1, naming the cause, when:
#   - --nodes is more than the number of db nodes
#   - the AMI-based Cassandra is running on a db node (both would report under job="cassandra")
#   - --nodes is not a whole number >= 1, or --heap is not a whole number of G or M
#   - --dc is not a plain name (letters, digits, _ and -, starting with a letter or digit): the
#     name is substituted into the StatefulSet as a YAML scalar and used as the snitch DC
#   - --memory is not a whole number of Gi or Mi, or --heap is not smaller than --memory
#   - a db node cannot be checked over ssh (fail fast: an unchecked node may be running the AMI Cassandra)
#   - EDL_NODE_SSH is not true or false. With EDL_NODE_SSH=false (no node SSH) the AMI Cassandra
#     check is skipped with one printed line.
#   - jq is not installed (it reads the registry token, `token` or `access_token`)
#   - the image's manifest is definitely not in its registry (404). A 401/403 (private image) warns
#     and continues; any other answer fails with curl's own message.
#
# Reads CASSANDRA_VERSION, CASSANDRA_IMAGE, CASSANDRA_NODES, CASSANDRA_HEAP, CASSANDRA_MEMORY, CASSANDRA_DC, DB_NODE_COUNT, EDL_NODE_SSH.
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

# --dc is rendered into the StatefulSet as a bare YAML scalar, so only a plain name is accepted.
dc="${CASSANDRA_DC:-dc1}"
case "${dc}" in
  '' | -* | *[!A-Za-z0-9_-]*)
    fail "ERROR: --dc '${dc}' is not valid; use letters, digits, _ and -, starting with a letter or digit, such as dc1."
    ;;
esac

# --memory is the container's memory request and limit: a whole number of Gi or Mi. The default
# heap is half of it, and an explicit --heap must be smaller, leaving room for off-heap memory.
memory="${CASSANDRA_MEMORY:-4Gi}"
memory_amount="${memory%[GM]i}"
case "${memory}:${memory_amount}" in
  "${memory_amount}:"*) fail "ERROR: --memory '${memory}' is not valid; use a whole number followed by Gi or Mi, such as 4Gi or 2048Mi." ;;
esac
case "${memory_amount}" in
  '' | *[!0-9]* | 0*) fail "ERROR: --memory '${memory}' is not valid; use a whole number followed by Gi or Mi, such as 4Gi or 2048Mi." ;;
esac
case "${memory}" in
  *Gi) memory_mib=$((memory_amount * 1024)) ;;
  *) memory_mib=${memory_amount} ;;
esac
if [ -n "${heap}" ]; then
  case "${heap}" in
    *[Gg]) heap_mib=$((amount * 1024)) ;;
    *) heap_mib=${amount} ;;
  esac
  if [ "${heap_mib}" -ge "${memory_mib}" ]; then
    fail "ERROR: --heap ${heap} does not fit inside --memory ${memory}; the heap must be smaller than the container memory, which also holds off-heap memory. Raise --memory or lower --heap."
  fi
fi

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  image="${CASSANDRA_IMAGE}"
else
  image="cassandra:${CASSANDRA_VERSION}"
fi

# The OCI reference grammar's characters. apply-statefulset.sh substitutes the image into the
# manifest, so anything else (such as '&' or '|') is refused here, before any lookup.
case "${image}" in
  '' | *[!A-Za-z0-9._/:@-]*)
    fail "ERROR: '${image}' is not a valid image reference; use only letters, digits and . _ / : @ - (for example ghcr.io/example/cassandra:my-branch)."
    ;;
esac

# EDL_NODE_SSH comes from easy-db-lab: true when the workspace can reach its nodes over SSH (it has
# an sshConfig). Without node SSH there is no AMI Cassandra to collide with, so the check is skipped.
case "${EDL_NODE_SSH:-}" in
  true) check_nodes=yes ;;
  false)
    check_nodes=no
    echo "Skipping the AMI Cassandra check: this environment has no SSH to its nodes, so no AMI Cassandra can run on them."
    ;;
  *) fail "ERROR: EDL_NODE_SSH is '${EDL_NODE_SSH:-}', not true or false; run this through easy-db-lab, which sets it." ;;
esac

i=0
while [ "${check_nodes}" = yes ] && [ "${i}" -lt "${DB_NODE_COUNT}" ]; do
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
tag_given=no
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
    tag_given=yes
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

# The registry's token answer is JSON, read with jq on the operator's machine.
command -v jq > /dev/null 2>&1 ||
  fail "ERROR: jq is not installed; the cassandra-pods start check needs it to read the registry token. Install jq (for example 'brew install jq') and run start again."

curl_err="$(mktemp)"
token_body="$(mktemp)"
trap 'rm -f "${curl_err}" "${token_body}"' EXIT
# A registry that answers its token endpoint with 401, 403 or 404 is checked anonymously; any other
# failure (network, 5xx) stops start, naming the image.
token_code="$(curl -sS -o "${token_body}" -w '%{http_code}' "${token_url}" 2> "${curl_err}")" || token_code=""
token=""
case "${token_code}" in
  200) token="$(jq -r '.token // .access_token // empty' "${token_body}")" ;;
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

# The cassandra_build label is the image tag; for a digest-only reference, sha256- plus the first
# 12 hex chars of the digest, so a dashboard variable stays short.
if [ -z "${CASSANDRA_IMAGE:-}" ]; then
  build="${CASSANDRA_VERSION}"
elif [ "${tag_given}" = yes ]; then
  build="${tag}"
else
  digest="${reference#sha256:}"
  build="sha256-$(printf '%s' "${digest}" | cut -c1-12)"
fi
printf 'RESOLVED_IMAGE=%s\nRESOLVED_BUILD=%s\n' "${image}" "${build}" > resolved-image.env
echo "Image ${image} found."
