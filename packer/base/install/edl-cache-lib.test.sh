#!/usr/bin/env bash
#
# Unit tests for cached_fetch — the fetch helper every provisioning script and
# install-cassandra-version routes downloads through.
#
# The bug these exist for: cached_fetch assumed curl could fetch anything it was handed. Given an
# s3:// url it fell through to `curl -fsSL s3://...`, which fails with
# `curl: (1) Protocol "s3" not supported`. An error that points nowhere near its cause, and which
# only appears on a real node. It cost a full cluster provisioning cycle to find.
#
# The second thing asserted here is that an s3:// source is NOT written back into the download
# cache. It is already an object in that same bucket, so caching it stores a second copy of a file
# that is already there.
#
# aws and curl are stubbed, so this needs no network, no AWS credentials and no root.
#
# Run directly:  ./edl-cache-lib.test.sh
# Or via gradle: ./gradlew testCacheLib

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tests_run=0
tests_failed=0

pass() {
  tests_run=$((tests_run + 1))
  echo "ok   - $1"
}

fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  echo "FAIL - $1"
}

# Each test runs in a fresh temp dir with stub aws/curl on PATH that log their arguments rather
# than doing anything. CALLS is the transcript the assertions read.
setup() {
  TMP="$(mktemp -d)"
  CALLS="$TMP/calls"
  : > "$CALLS"
  mkdir -p "$TMP/bin"

  cat > "$TMP/bin/aws" <<'STUB'
#!/usr/bin/env bash
echo "aws $*" >> "$CALLS"
# "cp <src> <dest>" — create the destination so callers see a successful fetch, unless the
# test asked for a cache miss by setting AWS_CP_FAILS for cache-key reads.
if [ "${1:-}" = "s3" ] && [ "${2:-}" = "cp" ]; then
  case "$3" in
    *download-cache/*) [ -n "${AWS_CACHE_MISS:-}" ] && exit 1 ;;
  esac
  [ "${4:-}" != "" ] && [ "${4}" != "-" ] && echo "fetched" > "$4"
fi
exit 0
STUB

  cat > "$TMP/bin/curl" <<'STUB'
#!/usr/bin/env bash
echo "curl $*" >> "$CALLS"
for a in "$@"; do
  [ "$prev" = "-o" ] 2>/dev/null && echo "fetched" > "$a"
  prev="$a"
done
exit 0
STUB

  chmod +x "$TMP/bin/aws" "$TMP/bin/curl"
  PATH="$TMP/bin:$PATH"
  export CALLS

  # Source the library with a bucket configured. /etc/edl-cache.env is absent in a test
  # environment, so the value set here survives.
  EDL_S3_BUCKET="test-bucket"
  # shellcheck source=/dev/null
  source "${SCRIPT_DIR}/edl-cache-lib.sh"
  EDL_S3_BUCKET="test-bucket"
}

teardown() { rm -rf "$TMP"; }

calls_contain() { grep -qF "$1" "$CALLS"; }

# --- s3:// sources ------------------------------------------------------------------------------

setup
cached_fetch "s3://my-bucket/cassandra-builds/build/x-bin.tar.gz" "cassandra-dist/build/x-bin.tar.gz" "$TMP/out.tar.gz" >/dev/null 2>&1
if calls_contain "aws s3 cp s3://my-bucket/cassandra-builds/build/x-bin.tar.gz"; then
  pass "an s3:// url is fetched with aws s3 cp"
else
  fail "an s3:// url should be fetched with aws s3 cp; calls were: $(cat "$CALLS")"
fi
if grep -q "^curl" "$CALLS"; then
  fail "an s3:// url must never reach curl (curl has no s3 protocol)"
else
  pass "an s3:// url never reaches curl"
fi
if grep -q "download-cache" "$CALLS"; then
  fail "an s3:// url must not touch the download cache; calls were: $(cat "$CALLS")"
else
  pass "an s3:// url is neither read from nor written to the download cache"
fi
teardown

# --- https:// sources, cache hit ----------------------------------------------------------------

setup
cached_fetch "https://example.com/x.tar.gz" "thing/1.0/x.tar.gz" "$TMP/out.tar.gz" >/dev/null 2>&1
if calls_contain "aws s3 cp s3://test-bucket/download-cache/thing/1.0/x.tar.gz"; then
  pass "an https:// url is served from the download cache when present"
else
  fail "expected a cache read; calls were: $(cat "$CALLS")"
fi
if grep -q "^curl" "$CALLS"; then
  fail "a cache hit must not also fetch from the origin"
else
  pass "a cache hit does not fetch from the origin"
fi
teardown

# --- https:// sources, cache miss ---------------------------------------------------------------

setup
AWS_CACHE_MISS=1 cached_fetch "https://example.com/x.tar.gz" "thing/1.0/x.tar.gz" "$TMP/out.tar.gz" >/dev/null 2>&1
if calls_contain "curl -fsSL --retry 3 https://example.com/x.tar.gz"; then
  pass "an https:// url falls back to the origin on a cache miss"
else
  fail "expected an origin fetch; calls were: $(cat "$CALLS")"
fi
if [ "$(grep -c "aws s3 cp $TMP/out.tar.gz s3://test-bucket/download-cache" "$CALLS")" -eq 1 ]; then
  pass "a cache miss populates the cache for next time"
else
  fail "expected the cache to be populated; calls were: $(cat "$CALLS")"
fi
teardown

# --- no bucket configured -----------------------------------------------------------------------

setup
EDL_S3_BUCKET="" cached_fetch "https://example.com/x.tar.gz" "thing/1.0/x.tar.gz" "$TMP/out.tar.gz" >/dev/null 2>&1
if grep -q "^aws" "$CALLS"; then
  fail "with no bucket configured there is no cache to touch; calls were: $(cat "$CALLS")"
else
  pass "with no bucket configured, no aws calls are made"
fi
if calls_contain "curl -fsSL --retry 3 https://example.com/x.tar.gz"; then
  pass "with no bucket configured the origin is still fetched"
else
  fail "expected an origin fetch; calls were: $(cat "$CALLS")"
fi
teardown

echo
echo "${tests_run} tests, ${tests_failed} failed"
[ "$tests_failed" -eq 0 ]
