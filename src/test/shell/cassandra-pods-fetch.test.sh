#!/usr/bin/env bash
#
# Unit tests for the cassandra-pods kit's config/fetch-tarball.sh, run by each pod's
# cassandra-fetch init container. It runs under /bin/sh (dash on the runtime image), so it is run
# here through a real POSIX sh. curl is a stub that serves a fixture tarball; no network.
#
# Run directly:  bash src/test/shell/cassandra-pods-fetch.test.sh
# Or via gradle: ./gradlew testCassandraPodsFetch

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT="${ROOT}/src/main/resources/com/rustyrazorblade/easydblab/kits/cassandra-pods/config/fetch-tarball.sh"
POSIX_SH="$(command -v dash || command -v sh)"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

tests_run=0
tests_failed=0

check() {
  local desc="$1" cond="$2"
  tests_run=$((tests_run + 1))
  if eval "$cond"; then
    echo "PASS: ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "FAIL: ${desc}"
    echo "  status=${status}"
    echo "  output: ${output}"
  fi
}

# A release tarball with one top-level directory, as ASF and cassandra-builds publish them.
mkdir -p "${WORK}/src/apache-cassandra-5.0.6/bin" "${WORK}/src/apache-cassandra-5.0.6/conf"
echo "cluster_name: 'Test Cluster'" > "${WORK}/src/apache-cassandra-5.0.6/conf/cassandra.yaml"
printf '#!/bin/sh\necho cassandra\n' > "${WORK}/src/apache-cassandra-5.0.6/bin/cassandra"
tar -czf "${WORK}/tarball.tgz" -C "${WORK}/src" apache-cassandra-5.0.6
SHA="$(sha256sum "${WORK}/tarball.tgz" | cut -d' ' -f1)"
OTHER_SHA="$(printf '%064d' 0)"

mkdir -p "${WORK}/stubs"
# curl: logs each URL; a URL in $STUB_FAIL_URLS answers HTTP $STUB_FAIL_CODE (22 is curl -f's exit),
# any other URL gets the fixture tarball written to its -o file.
cat > "${WORK}/stubs/curl" << 'STUB'
#!/bin/sh
out=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift ;;
    -*) ;;
    *) url="$1" ;;
  esac
  shift
done
echo "$url" >> "${STUB_DIR}/curl.calls"
case " ${STUB_FAIL_URLS:-} " in
  *" $url "*) echo "curl: (22) The requested URL returned error: ${STUB_FAIL_CODE:-404}" >&2; exit 22 ;;
esac
cp "${STUB_DIR}/tarball.tgz" "$out"
STUB
# tar: a tar that writes part of the tree and then fails, as a killed or full-disk extract does.
mkdir -p "${WORK}/broken-tar"
cat > "${WORK}/broken-tar/tar" << 'STUB'
#!/bin/sh
while [ "$#" -gt 0 ]; do [ "$1" = -C ] && dir="$2"; shift; done
mkdir -p "$dir/apache-cassandra-5.0.6/bin" && echo partial > "$dir/apache-cassandra-5.0.6/bin/cassandra"
exit 2
STUB
chmod +x "${WORK}/stubs/curl" "${WORK}/broken-tar/tar"
export STUB_DIR="${WORK}"

URL="https://dlcdn.example/apache-cassandra-5.0.6-bin.tar.gz"
FALLBACK="https://archive.example/apache-cassandra-5.0.6-bin.tar.gz"

# run_fetch [VAR=value]... -> sets status, output; the cache is ${WORK}/cache
run_fetch() {
  output="$(env PATH="${WORK}/stubs:${PATH}" EDL_ARTIFACT_CACHE="${WORK}/cache" \
    EDL_TARBALL_URL="${URL}" EDL_TARBALL_FALLBACK_URL="${FALLBACK}" EDL_TARBALL_SHA256="${SHA}" \
    "$@" "${POSIX_SH}" "${SCRIPT}" 2>&1)"
  status=$?
}

calls() { cat "${WORK}/curl.calls" 2>/dev/null | wc -l | tr -d ' '; }

reset() { rm -rf "${WORK}/cache" "${WORK}/curl.calls"; }

reset
run_fetch
check "a cold cache downloads and extracts the tarball" '[ "$status" -eq 0 ] && [ -f "${WORK}/cache/sha256-${SHA}/bin/cassandra" ]'
check "the tree is marked complete" '[ -f "${WORK}/cache/sha256-${SHA}/.complete" ]'
check "the tarball's own conf is in the tree" '[ -f "${WORK}/cache/sha256-${SHA}/conf/cassandra.yaml" ]'
check "nothing is left beside the tree" '[ "$(ls -A "${WORK}/cache")" = "sha256-${SHA}" ]'

rm -f "${WORK}/curl.calls"
run_fetch EDL_TARBALL_URL="https://unreachable.example/x.tgz" EDL_TARBALL_FALLBACK_URL=""
check "a cache hit makes no network call, whatever the URL" '[ "$status" -eq 0 ] && [ "$(calls)" -eq 0 ]'

reset
run_fetch STUB_FAIL_URLS="${URL}"
check "an unreachable primary URL falls back" '[ "$status" -eq 0 ] && [ "$(calls)" -eq 2 ] && [ -f "${WORK}/cache/sha256-${SHA}/.complete" ]'

reset
run_fetch STUB_FAIL_URLS="${URL} ${FALLBACK}"
check "no URL answering fails, naming both" '[ "$status" -ne 0 ] && [[ "$output" == *"${URL}"* && "$output" == *"${FALLBACK}"* ]]'
check "a failed download leaves no cache directory" '[ ! -e "${WORK}/cache/sha256-${SHA}" ] && [ -z "$(ls -A "${WORK}/cache" 2>/dev/null)" ]'

reset
run_fetch EDL_TARBALL_SHA256="${OTHER_SHA}"
check "a sha256 mismatch fails" '[ "$status" -ne 0 ]'
check "the mismatch names the expected and the actual hash" '[[ "$output" == *"${OTHER_SHA}"* && "$output" == *"${SHA}"* ]]'
check "a mismatch leaves no cache directory" '[ ! -e "${WORK}/cache/sha256-${OTHER_SHA}" ] && [ -z "$(ls -A "${WORK}/cache" 2>/dev/null)" ]'

reset
output="$(env PATH="${WORK}/broken-tar:${WORK}/stubs:${PATH}" EDL_ARTIFACT_CACHE="${WORK}/cache" \
  EDL_TARBALL_URL="${URL}" EDL_TARBALL_FALLBACK_URL="" EDL_TARBALL_SHA256="${SHA}" "${POSIX_SH}" "${SCRIPT}" 2>&1)"
status=$?
check "an interrupted extract fails" '[ "$status" -ne 0 ]'
check "an interrupted extract leaves no complete tree" '[ ! -e "${WORK}/cache/sha256-${SHA}" ] && [ -z "$(find "${WORK}/cache" -name .complete 2>/dev/null)" ]'

reset
mkdir -p "${WORK}/cache/sha256-${SHA}/bin"
echo partial > "${WORK}/cache/sha256-${SHA}/bin/cassandra"
run_fetch
check "a tree without its marker is fetched again" '[ "$status" -eq 0 ] && [ "$(calls)" -eq 1 ] && [ -f "${WORK}/cache/sha256-${SHA}/.complete" ]'

reset
mkdir -p "${WORK}/cache/sha256-${OTHER_SHA}"
touch "${WORK}/cache/sha256-${OTHER_SHA}/.complete"
run_fetch
check "another version's tree is kept, so a rollback is a cache hit" '[ -f "${WORK}/cache/sha256-${OTHER_SHA}/.complete" ]'

reset
mkdir -p "${WORK}/cache/.fetch-${SHA}.AbC123/extract" "${WORK}/cache/.fetch-${OTHER_SHA}.XyZ789"
echo partial > "${WORK}/cache/.fetch-${SHA}.AbC123/tarball.tgz"
run_fetch
check "an interrupted fetch's work directories are removed at the next start" '[ "$status" -eq 0 ] && [ "$(ls -A "${WORK}/cache")" = "sha256-${SHA}" ]'

mkdir -p "${WORK}/cache/.fetch-${SHA}.Left01"
rm -f "${WORK}/curl.calls"
run_fetch
check "a cache hit removes them too, with no download" '[ "$status" -eq 0 ] && [ "$(calls)" -eq 0 ] && [ "$(ls -A "${WORK}/cache")" = "sha256-${SHA}" ]'

reset
PRESIGNED="https://bucket.s3.example/b.tgz?X-Amz-Expires=604800&X-Amz-Signature=abc"
run_fetch EDL_TARBALL_URL="${PRESIGNED}" EDL_TARBALL_FALLBACK_URL="" STUB_FAIL_URLS="${PRESIGNED}" STUB_FAIL_CODE=403
check "an expired presigned URL names the expiry and the fix" '[ "$status" -ne 0 ] && [[ "$output" == *"604800"* && "$output" == *"upgrade"* && "$output" == *"stop"*"start"* ]]'
check "the error does not print the signature" '[[ "$output" != *"X-Amz-Signature=abc"* ]]'

reset
run_fetch EDL_TARBALL_SHA256="not-a-hash"
check "a malformed sha256 is refused before any download" '[ "$status" -ne 0 ] && [ "$(calls)" -eq 0 ]'

echo
echo "${tests_run} run, ${tests_failed} failed"
[ "${tests_failed}" -eq 0 ]
