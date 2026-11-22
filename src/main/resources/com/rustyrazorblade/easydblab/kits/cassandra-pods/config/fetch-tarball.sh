#!/bin/sh
# fetch-tarball.sh - the cassandra-fetch init container: puts the Cassandra tarball the CLI resolved
# into this pod's claim, extracted, at $EDL_ARTIFACT_CACHE/sha256-<hex>/, marked by `.complete`.
#
# A tree with its `.complete` marker is used as it is, with no network call, so a restart (or a
# rollback to an earlier version) reuses it. Otherwise the tarball is downloaded into a temporary
# directory on the claim (from EDL_TARBALL_URL, then EDL_TARBALL_FALLBACK_URL), checked against
# EDL_TARBALL_SHA256, extracted there, marked, and renamed into place: a failure at any step
# leaves no tree under the final name. Older trees are never removed; an interrupted fetch's
# work directory (`.fetch-*`) is removed at the next start.
#
# POSIX sh: the runtime image runs it under /bin/sh. Tested by
# src/test/shell/cassandra-pods-fetch.test.sh (./gradlew testCassandraPodsFetch).
set -eu

cache="${EDL_ARTIFACT_CACHE:-/var/lib/cassandra/.edl/artifacts}"
url="${EDL_TARBALL_URL:?EDL_TARBALL_URL must name the tarball}"
fallback="${EDL_TARBALL_FALLBACK_URL:-}"
sha="${EDL_TARBALL_SHA256:?EDL_TARBALL_SHA256 must give the tarball sha256}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

# A URL as it may be printed: a presigned URL's query holds a credential, so it is cut off.
shown() {
  printf '%s' "${1%%\?*}"
}

case "${sha}" in
  *[!0-9a-f]*) fail "EDL_TARBALL_SHA256 '${sha}' is not a lowercase hex sha256" ;;
esac
[ "${#sha}" -eq 64 ] || fail "EDL_TARBALL_SHA256 '${sha}' is not 64 hex characters"

# Work directories an interrupted fetch left (a killed container never runs its EXIT trap). The claim
# is this pod's alone and its one fetch runs before Cassandra, so any work directory now is stale.
rm -rf "${cache}"/.fetch-*

tree="${cache}/sha256-${sha}"
if [ -f "${tree}/.complete" ]; then
  echo "Cassandra $(shown "${url}") is cached at ${tree}."
  exit 0
fi

mkdir -p "${cache}"
work="$(mktemp -d "${cache}/.fetch-${sha}.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

download() {
  curl -fsSL --retry 3 --connect-timeout 30 -o "${work}/tarball.tgz" "$1" 2> "${work}/curl.err"
}

if ! download "${url}"; then
  reason="$(cat "${work}/curl.err")"
  case "${url}" in
    *X-Amz-Expires=*)
      expires="${url#*X-Amz-Expires=}"
      expires="${expires%%&*}"
      [ -n "${fallback}" ] ||
        fail "could not download $(shown "${url}") (${reason}). The presigned URL is valid for ${expires}s after the start or upgrade that signed it, and may have expired: run 'cassandra-pods upgrade' again, or 'cassandra-pods stop' then 'cassandra-pods start', to sign a new one."
      ;;
  esac
  [ -n "${fallback}" ] || fail "could not download $(shown "${url}"): ${reason}"
  echo "Could not download $(shown "${url}") (${reason}); trying $(shown "${fallback}")."
  download "${fallback}" ||
    fail "could not download the tarball from $(shown "${url}") or $(shown "${fallback}"): $(cat "${work}/curl.err")"
fi

actual="$(sha256sum "${work}/tarball.tgz" | cut -d' ' -f1)"
[ "${actual}" = "${sha}" ] ||
  fail "the tarball from $(shown "${url}") has sha256 ${actual}, not the expected ${sha}; nothing was cached."

mkdir "${work}/extract"
tar -xzf "${work}/tarball.tgz" -C "${work}/extract" ||
  fail "could not extract the tarball from $(shown "${url}"); nothing was cached."
# Release tarballs hold one top-level directory (apache-cassandra-<version>/); the tree is its content.
set -- "${work}/extract"/*
[ "$#" -eq 1 ] && [ -d "$1" ] ||
  fail "the tarball from $(shown "${url}") does not hold one top-level directory; nothing was cached."
[ -f "$1/conf/cassandra.yaml" ] ||
  fail "the tarball from $(shown "${url}") has no conf/cassandra.yaml; nothing was cached."
touch "$1/.complete"
# A tree without its marker (an older interrupted fetch) is replaced; the rename is atomic.
rm -rf "${tree}"
mv "$1" "${tree}"
echo "Cassandra $(shown "${url}") is cached at ${tree}."
