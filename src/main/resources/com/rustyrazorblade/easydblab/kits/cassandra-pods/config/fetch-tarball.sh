#!/bin/sh
# fetch-tarball.sh - the cassandra-fetch init container: puts the Cassandra tarball the CLI resolved
# into this pod's claim, extracted, at $EDL_ARTIFACT_CACHE/sha256-<hex>/, marked by `.complete`.
#
# A tree with its `.complete` marker is used as it is, with no network call, so a restart (or a
# rollback to an earlier version) reuses it. Otherwise the tarball is downloaded into a temporary
# directory on the claim from EDL_TARBALL_URL and checked against EDL_TARBALL_SHA256; when that
# download fails or does not match, EDL_TARBALL_FALLBACK_URL is tried the same way. It is then
# extracted there, marked, and renamed into place: a failure at any step
# leaves no complete tree under the final name. Older trees are never removed; an interrupted fetch's
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

# fetch_from <url>: downloads <url> into the work directory and checks its sha256. On failure it
# sets `problem` to what went wrong and `failure` to `download` or `checksum`, and returns 1.
fetch_from() {
  if ! curl -fsSL --retry 3 --connect-timeout 30 -o "${work}/tarball.tgz" "$1" 2> "${work}/curl.err"; then
    failure=download
    problem="could not download $(shown "$1"): $(cat "${work}/curl.err")"
    return 1
  fi
  actual="$(sha256sum "${work}/tarball.tgz" | cut -d' ' -f1)"
  if [ "${actual}" != "${sha}" ]; then
    failure=checksum
    problem="the tarball from $(shown "$1") has sha256 ${actual}, not the expected ${sha}"
    return 1
  fi
}

# expiry_hint: when the primary URL is presigned and could not be downloaded, the sentence naming its
# lifetime and the fix; empty otherwise. A checksum mismatch is not an expired link.
expiry_hint() {
  case "${primary_failure}:${url}" in
    download:*X-Amz-Expires=*)
      expires="${url#*X-Amz-Expires=}"
      expires="${expires%%&*}"
      printf ' The presigned URL is valid for %ss after the start or upgrade that signed it, and may have expired: run %s again, or %s then %s, to sign a new one.' \
        "${expires}" "'cassandra-pods upgrade'" "'cassandra-pods stop'" "'cassandra-pods start'"
      ;;
  esac
}

# The primary URL, then the fallback when the primary cannot be downloaded or fails its checksum.
if ! fetch_from "${url}"; then
  primary_failure="${failure}"
  if [ -z "${fallback}" ]; then
    hint="$(expiry_hint)"
    [ -z "${hint}" ] || fail "${problem}.${hint}"
    fail "${problem}; nothing was cached."
  fi
  first="${problem}"
  echo "${first}; trying $(shown "${fallback}")."
  fetch_from "${fallback}" ||
    fail "the tarball could not be fetched from $(shown "${url}") or $(shown "${fallback}"): ${first}; ${problem}; nothing was cached.$(expiry_hint)"
fi

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
# A tree without its marker (an older interrupted fetch) is removed, then the new one renamed into
# place. The rename is atomic but the pair is not: a kill between them leaves no tree, and the next
# start fetches again. A tree with `.complete` is never removed (it is a cache hit, above).
rm -rf "${tree}"
mv "$1" "${tree}"
echo "Cassandra $(shown "${url}") is cached at ${tree}."
