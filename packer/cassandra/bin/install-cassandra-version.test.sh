#!/usr/bin/env bash
#
# Unit tests for install-cassandra-version — the one script both the AMI bake and
# `easy-db-lab cassandra install` use to install a single Cassandra version.
#
# The CLI resolves every version (design D1 of issue 1130) and passes the script explicit
# arguments: --url always, and for an Apache release --fallback-url and --cache-uri. These cover
# argument handling, the already-installed early return, --reinstall, the guards on building from
# source, JDK selection, and which URLs are fetched through the download cache. No network, no Docker, no root: sudo/dpkg/curl/git are stubbed on PATH and the install
# directory is redirected into a temp dir.
#
# Run directly:  ./install-cassandra-version.test.sh
# Or via gradle: ./gradlew testCassandraInstallScript

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/install-cassandra-version"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"

# sudo runs the command directly; the test never needs root.
cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM

# Record any network or VCS reach so a test can assert the script never got that far.
for tool in curl git ant; do
  cat >"${BIN}/${tool}" <<SHIM
#!/bin/bash
echo "${tool} \$*" >> "${SANDBOX}/reached-out.log"
exit 1
SHIM
done

# dpkg is Debian-only; the script only asks it for the architecture.
cat >"${BIN}/dpkg" <<'SHIM'
#!/bin/bash
echo amd64
SHIM

chmod +x "${BIN}"/*
export PATH="${BIN}:${PATH}"

# Always load the repo copy of the cache library, never one the machine happens to have at its
# default path. With no bucket configured it downloads directly, through the stubbed curl.
export EDL_CACHE_LIB="${SCRIPT_DIR}/../../base/install/edl-cache-lib.sh"
unset EDL_S3_BUCKET

export CASSANDRA_INSTALL_DIR="${SANDBOX}/cassandra"
export CASSANDRA_IN_SH_SNIPPET="${SANDBOX}/cassandra.in.sh"
# The script's working directory comes from mktemp -d, so point that at the sandbox to make what
# it leaves behind observable.
export TMPDIR="${SANDBOX}/tmp"
mkdir -p "$CASSANDRA_INSTALL_DIR" "$TMPDIR"
echo '# test snippet' >"$CASSANDRA_IN_SH_SNIPPET"

# GNU mktemp (the nodes, and CI) honors TMPDIR; BSD mktemp (macOS) ignores it and uses the
# per-user Darwin temp dir, which would make the cleanup assertion below silently vacuous.
probe="$(mktemp -d)"
case "$probe" in
  "${TMPDIR}"/*) tmpdir_observable=true ;;
  *) tmpdir_observable=false ;;
esac
rmdir "$probe"

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

# Runs the script, capturing combined output in $OUTPUT and the exit code in $STATUS.
run_script() {
  rm -f "${SANDBOX}/reached-out.log"
  OUTPUT="$(bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}

assert_fails_with() {
  local desc="$1" expected="$2"
  shift 2
  run_script "$@"
  if [[ "$STATUS" -eq 0 ]]; then
    fail "$desc: expected non-zero exit, got 0"
  elif [[ "$OUTPUT" != *"$expected"* ]]; then
    fail "$desc: expected output to contain [$expected], got [$OUTPUT]"
  else
    pass "$desc"
  fi
}

assert_no_reach_out() {
  local desc="$1"
  if [[ -f "${SANDBOX}/reached-out.log" ]]; then
    fail "$desc: script reached out — $(cat "${SANDBOX}/reached-out.log")"
  else
    pass "$desc"
  fi
}

# --- argument handling --------------------------------------------------------
assert_fails_with "unknown flag is rejected" "unknown option --bogus" 5.0 --bogus
assert_fails_with "a second positional argument is rejected" "unexpected argument" 5.0 6.0
assert_fails_with "missing version is rejected" "<version> is required"
assert_fails_with "a missing --url is rejected; the script resolves no version itself" "--url is required" 5.0
assert_no_reach_out "a missing --url downloads nothing"
for bad in ".." "../../opt" "feature/x" "-x" ".hidden" "a b"; do
  assert_fails_with "version name [$bad] is refused" "invalid version name" "$bad" --url https://example.test/x.tgz
  assert_no_reach_out "version name [$bad] downloads nothing"
done
# Every option that takes a value names itself when it is the last argument, instead of dying on set -u.
for opt in --url --fallback-url --cache-uri --branch --java --ant-flags; do
  assert_fails_with "$opt with no value is rejected" "$opt needs a value" 5.0 --url https://example.test/x.tar.gz "$opt"
done
assert_fails_with "a git --url with no --branch is refused" "pass --branch" \
  from-source --url https://github.com/apache/cassandra.git
assert_no_reach_out "a git --url with no --branch downloads nothing"
assert_fails_with "--cache-uri with a git build is rejected" "--cache-uri applies only to a tarball" \
  from-source --url https://github.com/apache/cassandra.git --branch trunk --java 17 --cache-uri s3://b/k

# The node no longer scrapes dlcdn or reads the bake-time cache config; the CLI decides both.
for gone in 'dlcdn.apache.org' 'edl-cache.env' 'EDL_S3_BUCKET' 'cassandra_versions.yaml'; do
  if grep -qF "$gone" "$SCRIPT"; then
    fail "install-cassandra-version still refers to $gone: $(grep -nF "$gone" "$SCRIPT")"
  else
    pass "install-cassandra-version does not refer to $gone"
  fi
done

run_script --help
if [[ "$STATUS" -eq 0 && "$OUTPUT" == *"Usage: install-cassandra-version"* ]]; then
  pass "--help exits 0 with usage"
else
  fail "--help should exit 0 with usage, got status ${STATUS}"
fi

# --- already installed is a no-op --------------------------------------------
mkdir -p "${CASSANDRA_INSTALL_DIR}/5.0"
run_script 5.0 --url https://example.com/apache-cassandra-5.0.6-bin.tar.gz
if [[ "$STATUS" -eq 0 && "$OUTPUT" == *"already installed"* ]]; then
  pass "an installed version exits 0 without reinstalling"
else
  fail "an installed version should exit 0 as a no-op, got status ${STATUS}: ${OUTPUT}"
fi
assert_no_reach_out "an installed version downloads nothing"

# --- building from source needs both a repo and a JDK ------------------------
assert_fails_with "a branch with no url is rejected" "--url is required" \
  from-source --branch trunk --java 17
assert_no_reach_out "a branch with no url clones nothing"

assert_fails_with "building from source with no --java is rejected" "requires --java" \
  from-source --url https://github.com/apache/cassandra.git --branch trunk
assert_no_reach_out "building with no --java clones nothing"

# --- a branch cannot be checked out of a tarball ------------------------------
assert_fails_with "a branch against a tarball url is rejected, not silently ignored" \
  "cannot be used with a tarball" \
  from-tarball --url https://example.com/apache-cassandra-x-bin.tar.gz --branch trunk --java 17
assert_no_reach_out "a branch against a tarball url downloads nothing"

# --- JDK selection comes from --java, and never switches the node's default ---
assert_fails_with "an unavailable JDK is reported, not silently substituted" \
  "No JDK found on this node for java version 99" \
  from-source --url https://github.com/apache/cassandra.git --branch trunk --java 99
assert_no_reach_out "an unavailable JDK clones nothing"

if grep -q "update-java-alternatives" "$SCRIPT"; then
  fail "the script must never switch the node's default JDK (update-java-alternatives)"
else
  pass "the script never switches the node's default JDK"
fi

# --- a failed install leaves no working directory behind ---------------------
# The working directory holds the git clone, so a failed build would otherwise strand the
# credentialed remote in <workdir>/<version>/.git/config for the life of the node.
run_script leftovers --url https://example.com/apache-cassandra-leftovers-bin.tar.gz --java 11
if [[ "$STATUS" -eq 0 ]]; then
  fail "expected the download to fail (curl is stubbed to fail)"
else
  pass "a failing download exits non-zero"
fi

if [[ "$tmpdir_observable" != true ]]; then
  echo "skip - mktemp here ignores TMPDIR (BSD); this assertion runs on the nodes and in CI"
elif [[ -z "$(find "$TMPDIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  pass "a failed install removes its working directory"
else
  fail "a failed install left $(find "$TMPDIR" -mindepth 1 -maxdepth 1) behind"
fi

# --- the config snippet is required, and checked before any download ---------
mv "$CASSANDRA_IN_SH_SNIPPET" "${SANDBOX}/held"
assert_fails_with "a missing cassandra.in.sh snippet is reported" \
  "Missing ${CASSANDRA_IN_SH_SNIPPET}" 4.1 --url https://example.com/apache-cassandra-4.1.7-bin.tar.gz
assert_no_reach_out "a missing snippet fails before downloading anything"
mv "${SANDBOX}/held" "$CASSANDRA_IN_SH_SNIPPET"

# --- no netty-codec-http side-trip any more -----------------------------------
# The script used to add netty-codec-http to a release's lib directory, purely so the MCAC metrics
# agent's Netty HTTP endpoint could start. Metrics now come from the OTel Java agent over OTLP, so
# nothing on the node needs that jar and the download must not come back.
if grep -q 'netty' "$SCRIPT"; then
  fail "install-cassandra-version still mentions netty: $(grep -n netty "$SCRIPT")"
else
  pass "no netty jar is downloaded for a metrics endpoint that no longer exists"
fi

# --- the S3 download cache holds only immutable releases ----------------------
# A tarball url (the nightlies: 5.0-HEAD, 6.0-HEAD, trunk) serves a new build every night under the
# same name, so a cached copy goes stale and every later install gets the old build. These cases
# load the real cache library against stub aws/curl and read the transcript of what was fetched.
CACHE_BIN="${SANDBOX}/cache-bin"
CALLS="${SANDBOX}/calls.log"
FIXTURE="${SANDBOX}/apache-cassandra-5.0.6-bin.tar.gz"
mkdir -p "$CACHE_BIN" "${SANDBOX}/fixture/apache-cassandra-5.0.6/conf" "${SANDBOX}/fixture/apache-cassandra-5.0.6/bin"
echo 'cluster_name: fixture' >"${SANDBOX}/fixture/apache-cassandra-5.0.6/conf/cassandra.yaml"
echo '# fixture' >"${SANDBOX}/fixture/apache-cassandra-5.0.6/bin/cassandra.in.sh"
tar -czf "$FIXTURE" -C "${SANDBOX}/fixture" apache-cassandra-5.0.6
# Another file, whose hash a tampered or wrong checksum file would hold.
OTHER_FIXTURE="${SANDBOX}/other.bin"
echo other >"$OTHER_FIXTURE"

# aws s3 cp <src> <dest>: a read under download-cache/ is a miss unless AWS_CACHE_HIT=1, a local
# destination gets the fixture tarball, and a destination under download-cache/ is a write-back.
cat >"${CACHE_BIN}/aws" <<SHIM
#!/bin/bash
echo "aws \$*" >> "${CALLS}"
[[ "\$1" == s3 && "\$2" == rm ]] && exit 0
[[ "\$1" == s3 && "\$2" == cp ]] || exit 1
case "\$3" in
  *download-cache/*) [[ "\${AWS_CACHE_HIT:-}" == 1 ]] || exit 1 ;;
esac
case "\$4" in
  s3://*) exit 0 ;;
esac
cp "${FIXTURE}" "\$4"
SHIM

# curl with -o downloads the fixture tarball. CURL_FAILS=1 fails every download; CURL_FAIL_URL
# fails only the download of that URL, as a 404 on dlcdn for an archived release would.
cat >"${CACHE_BIN}/curl" <<SHIM
#!/bin/bash
echo "curl \$*" >> "${CALLS}"
dest=""
url=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    -o) dest="\$2"; shift 2 ;;
    http*) url="\$1"; shift ;;
    *) shift ;;
  esac
done
[[ "\${CURL_FAILS:-}" == 1 ]] && exit 22
# CURL_FAIL_URL fails that URL and the checksum files beside it, as a host without the release does.
[[ -n "\${CURL_FAIL_URL:-}" && ( "\$url" == "\${CURL_FAIL_URL}" || "\$url" == "\${CURL_FAIL_URL}".* ) ]] && exit 22
# A checksum file: served only for the algorithms in CURL_SUMS ("+"-separated, default sha512), holding the
# fixture's hash (or CURL_SUM_BAD=1: another file's), bare or, with CURL_SUM_NAMED=1, as
# "hash  filename" with no trailing newline.
case "\$url" in
  *.sha512|*.sha256|*.sha1)
    alg="\${url##*.}"
    [[ "+\${CURL_SUMS-sha512}+" == *"+\$alg+"* ]] || exit 22
    src="${FIXTURE}"
    [[ "\${CURL_SUM_BAD:-}" == 1 ]] && src="${OTHER_FIXTURE}"
    hash="\$("\${alg}sum" "\$src" | awk '{print \$1}')"
    if [[ "\${CURL_SUM_NAMED:-}" == 1 ]]; then printf '%s  %s' "\$hash" "\${url%.*}" > "\$dest"; else echo "\$hash" > "\$dest"; fi
    exit 0
    ;;
esac
# CURL_BODY_HTML=1: the URL is a web page (a repository URL with no .git), not a tarball.
if [[ "\${CURL_BODY_HTML:-}" == 1 ]]; then echo "<html>repo</html>" > "\$dest"; exit 0; fi
cp "${FIXTURE}" "\$dest"
SHIM

# There is no cassandra user here.
cat >"${CACHE_BIN}/chown" <<'SHIM'
#!/bin/bash
exit 0
SHIM
chmod +x "${CACHE_BIN}"/*

NIGHTLY_URL="https://github.com/example/cassandra/releases/download/6.0-HEAD/apache-cassandra-6.0-HEAD-bin.tar.gz"
S3_URL="s3://test-bucket/cassandra-builds/my-build/apache-cassandra-my-build-bin.tar.gz"
RELEASE_KEY="cassandra-dist/5.0.6/apache-cassandra-5.0.6-bin.tar.gz"

case_number=0

# Runs the script with the real cache library, the cache stubs first on PATH, and a new install
# directory (an earlier case leaves its version installed), unless KEEP_INSTALL_DIR=1. A stale
# EDL_S3_BUCKET is set to prove the script ignores it. Extra environment for the stubs comes first
# as NAME=value arguments.
run_cached() {
  local env_args=()
  while [[ "$1" == *=* ]]; do
    env_args+=("$1")
    shift
  done
  [[ "${KEEP_INSTALL_DIR:-}" == 1 ]] || case_number=$((case_number + 1))
  : >"$CALLS"
  OUTPUT="$(env ${env_args[@]+"${env_args[@]}"} \
    PATH="${CACHE_BIN}:${PATH}" \
    EDL_CACHE_LIB="${SCRIPT_DIR}/../../base/install/edl-cache-lib.sh" \
    EDL_S3_BUCKET=bake-account-bucket \
    CASSANDRA_INSTALL_DIR="${SANDBOX}/cached-install-${case_number}" \
    bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}

assert_calls_contain() {
  local desc="$1" expected="$2"
  if grep -qF -- "$expected" "$CALLS"; then
    pass "$desc"
  else
    fail "$desc: expected [$expected] in calls, got [$(cat "$CALLS")]"
  fi
}

assert_calls_lack() {
  local desc="$1" unexpected="$2"
  if grep -qF -- "$unexpected" "$CALLS"; then
    fail "$desc: unexpected [$unexpected] in calls: $(grep -F -- "$unexpected" "$CALLS")"
  else
    pass "$desc"
  fi
}

assert_output_contains() {
  local desc="$1" expected="$2"
  if [[ "$OUTPUT" == *"$expected"* ]]; then
    pass "$desc"
  else
    fail "$desc: expected output to contain [$expected], got [$OUTPUT]"
  fi
}

assert_output_lacks() {
  local desc="$1" unexpected="$2"
  if [[ "$OUTPUT" == *"$unexpected"* ]]; then
    fail "$desc: output should not contain [$unexpected], got [$OUTPUT]"
  else
    pass "$desc"
  fi
}

# A nightly url is fetched from its origin, never from the download cache.
assert_fetched_from_origin_only() {
  local desc="$1"
  assert_calls_contain "$desc: the tarball is downloaded from its url" "curl -fsSL --retry 3 ${NIGHTLY_URL} -o"
  assert_output_contains "$desc: the direct download is logged" "direct download (not cached): ${NIGHTLY_URL}"
  assert_calls_lack "$desc: the download cache is not read or written" "download-cache/"
  assert_output_lacks "$desc: no cache hit is reported" "cache hit:"
  assert_output_lacks "$desc: no cache miss is reported" "cache miss:"
}

run_cached AWS_CACHE_HIT=1 6.0-HEAD --url "$NIGHTLY_URL"
assert_fetched_from_origin_only "a url version with a stale cache object"

run_cached 6.0-HEAD --url "$NIGHTLY_URL"
assert_fetched_from_origin_only "a url version with a cold cache"

run_cached AWS_CACHE_HIT=1 CURL_FAILS=1 6.0-HEAD --url "$NIGHTLY_URL"
if [[ "$STATUS" -ne 0 ]]; then
  pass "a failed url download exits non-zero"
else
  fail "a failed url download should exit non-zero, got 0: ${OUTPUT}"
fi
assert_output_contains "a failed url download names the version and the URL" "Failed to download version 6.0-HEAD from: ${NIGHTLY_URL}"
assert_calls_contain "a failed url download was attempted from its url" "curl -fsSL --retry 3 ${NIGHTLY_URL} -o"
assert_calls_lack "a failed url download does not fall back to the cache" "download-cache/"

run_cached CURL_BODY_HTML=1 6.0-HEAD --url "https://github.example/me/cassandra"
if [[ "$STATUS" -ne 0 ]]; then
  pass "a --url that downloads no gzip tarball exits non-zero"
else
  fail "a web page --url should exit non-zero, got 0: ${OUTPUT}"
fi
assert_output_contains "a --url that is no tarball says to pass --branch for a git repository" "is not a gzip tarball; for a git repository pass --branch"

run_cached 5.0-my-build --url "$S3_URL"
assert_calls_contain "an s3 url is copied directly from S3" "aws s3 cp ${S3_URL} "
assert_output_contains "an s3 url logs the direct download" "direct download (not cached): ${S3_URL}"
assert_calls_lack "an s3 url does not touch the download cache" "download-cache/"
assert_calls_lack "an s3 url is not fetched with curl" "curl"

# An Apache release: the CLI gives the dlcdn URL, the archive URL and the cache object.
DLCDN_URL="https://dlcdn.example/cassandra/5.0.6/apache-cassandra-5.0.6-bin.tar.gz"
ARCHIVE_URL="https://archive.example/dist/cassandra/5.0.6/apache-cassandra-5.0.6-bin.tar.gz"
CACHE_URI="s3://user-account-bucket/download-cache/${RELEASE_KEY}"
RELEASE_ARGS=(5.0 --url "$DLCDN_URL" --fallback-url "$ARCHIVE_URL" --cache-uri "$CACHE_URI")

run_cached "${RELEASE_ARGS[@]}"
assert_calls_contain "a release reads the cache object the CLI named" "aws s3 cp ${CACHE_URI} "
assert_calls_lack "a release never uses the bake-time bucket" "bake-account-bucket"
assert_calls_contain "a release cache miss downloads the --url" "curl -fsSL --retry 3 ${DLCDN_URL} -o"
assert_calls_contain "a release cache miss is written back to the cache object" " ${CACHE_URI} --no-progress"
assert_calls_lack "a release found on dlcdn does not try the archive" "$ARCHIVE_URL"
if [[ "$STATUS" -eq 0 && -d "${SANDBOX}/cached-install-${case_number}/5.0/conf" ]]; then
  pass "a release installs under the name the CLI gave, not the release number"
else
  fail "a release should install as 5.0 (status $STATUS): $OUTPUT"
fi

run_cached AWS_CACHE_HIT=1 "${RELEASE_ARGS[@]}"
assert_output_contains "a release reports a cache hit" "cache hit:  ${CACHE_URI}"
assert_calls_lack "a release cache hit downloads nothing" "curl"

run_cached CURL_FAIL_URL="$DLCDN_URL" "${RELEASE_ARGS[@]}"
assert_calls_contain "a release dlcdn does not hold is downloaded from the archive" "curl -fsSL --retry 3 ${ARCHIVE_URL} -o"
if [[ "$STATUS" -eq 0 ]]; then pass "an archive fallback install succeeds"; else fail "archive fallback failed: $OUTPUT"; fi

# --- an Apache release is checked against the strongest checksum ASF publishes ------------------
# --verify-checksum (the CLI passes it for an ASF release only) checks the tarball against
# <url>.sha512, else .sha256, else .sha1, from the host that has the release, before extracting it.
# Each row: description | stub environment (space-separated NAME=value) | expected exit (0 or 1) |
# expected output fragment.
VERIFY_ARGS=("${RELEASE_ARGS[@]}" --verify-checksum)
checksum_rows=(
  "a matching .sha512 installs|CURL_SUMS=sha512|0|checksum sha512 OK: ${DLCDN_URL}.sha512"
  "a .sha512 in the hash-and-filename form installs|CURL_SUMS=sha512 CURL_SUM_NAMED=1|0|checksum sha512 OK"
  "a release with only .sha256 is checked with sha256|CURL_SUMS=sha256|0|checksum sha256 OK: ${DLCDN_URL}.sha256"
  "a release with only .sha1 is checked with sha1, and says so|CURL_SUMS=sha1|0|checksum sha1 OK: ${DLCDN_URL}.sha1"
  "sha512 is preferred over sha1 when both are published|CURL_SUMS=sha1+sha512|0|checksum sha512 OK"
  "a release dlcdn does not hold is checked against the archive's checksum|CURL_FAIL_URL=${DLCDN_URL}|0|checksum sha512 OK: ${ARCHIVE_URL}.sha512"
  "a mismatch fails, naming the URL and both hashes|CURL_SUM_BAD=1|1|ERROR: checksum mismatch for ${DLCDN_URL}"
  "a release that publishes no checksum fails, naming every file tried|CURL_SUMS=none|1|${ARCHIVE_URL}.sha1"
)
for row in "${checksum_rows[@]}"; do
  IFS='|' read -r desc envs want_status want_output <<<"$row"
  read -r -a env_list <<<"$envs"
  run_cached "${env_list[@]}" "${VERIFY_ARGS[@]}"
  install_dir="${SANDBOX}/cached-install-${case_number}"
  if [[ "$want_status" == 0 ]]; then
    if [[ "$STATUS" -eq 0 && -d "${install_dir}/5.0/conf" && "$OUTPUT" == *"$want_output"* ]]; then
      pass "checksum: $desc"
    else
      fail "checksum: $desc: expected an install and [$want_output] (status $STATUS): $OUTPUT"
    fi
  else
    if [[ "$STATUS" -ne 0 && ! -d "${install_dir}/5.0" && "$OUTPUT" == *"$want_output"* ]]; then
      pass "checksum: $desc"
    else
      fail "checksum: $desc: expected a failure with [$want_output] and no install (status $STATUS): $OUTPUT"
    fi
    assert_calls_contain "checksum: $desc: the cache object is removed" "aws s3 rm ${CACHE_URI}"
  fi
done

run_cached CURL_SUM_BAD=1 "${VERIFY_ARGS[@]}"
expected_hash="$(sha512sum "$OTHER_FIXTURE" | awk '{print $1}')"
actual_hash="$(sha512sum "$FIXTURE" | awk '{print $1}')"
if [[ "$OUTPUT" == *"expected sha512 ${expected_hash}"* && "$OUTPUT" == *"got ${actual_hash}"* ]]; then
  pass "checksum: a mismatch prints the expected and the actual hash"
else
  fail "checksum: a mismatch should print both hashes: $OUTPUT"
fi
assert_output_lacks "checksum: a mismatched tarball is never extracted" "Extracting"

run_cached AWS_CACHE_HIT=1 CURL_SUM_BAD=1 "${VERIFY_ARGS[@]}"
if [[ "$STATUS" -ne 0 ]] && grep -qF "aws s3 rm ${CACHE_URI}" "$CALLS"; then
  pass "checksum: a cached tarball that fails the check fails the install and is removed from the cache"
else
  fail "checksum: a bad cache object should fail and be removed (status $STATUS): $(cat "$CALLS")"
fi

# Without --verify-checksum (a nightly, a catalog or profile build, a --url) nothing is checked,
# so a source with no published checksum installs.
run_cached CURL_SUMS=none 6.0-HEAD --url "$NIGHTLY_URL"
if [[ "$STATUS" -eq 0 ]] && ! grep -qE '\.(sha512|sha256|sha1)' "$CALLS"; then
  pass "checksum: a tarball outside ASF fetches no checksum file and installs"
else
  fail "checksum: a non-ASF tarball should install unchecked (status $STATUS): $(cat "$CALLS")"
fi
assert_fails_with "--verify-checksum with a git build is rejected" "--verify-checksum applies only to a tarball" \
  from-source --url https://github.com/apache/cassandra.git --branch trunk --java 17 --verify-checksum

run_cached CURL_FAILS=1 "${RELEASE_ARGS[@]}"
if [[ "$STATUS" -ne 0 && "$OUTPUT" == *"5.0"* && "$OUTPUT" == *"$DLCDN_URL"* && "$OUTPUT" == *"$ARCHIVE_URL"* ]]; then
  pass "a release that no URL serves fails, naming the version and both URLs"
else
  fail "expected a failure naming 5.0 and both URLs (status $STATUS): $OUTPUT"
fi

# --- --reinstall --------------------------------------------------------------------------------
# The old install stays in place until the new one is complete, so a failed download changes nothing.
run_cached 6.0-HEAD --url "$NIGHTLY_URL"
REINSTALL_DIR="${SANDBOX}/cached-install-${case_number}"
echo old > "${REINSTALL_DIR}/6.0-HEAD/marker"

KEEP_INSTALL_DIR=1 run_cached 6.0-HEAD --url "$NIGHTLY_URL"
assert_calls_lack "without --reinstall an installed version downloads nothing" "curl"

KEEP_INSTALL_DIR=1 run_cached CURL_FAILS=1 6.0-HEAD --url "$NIGHTLY_URL" --reinstall
if [[ "$STATUS" -ne 0 && -f "${REINSTALL_DIR}/6.0-HEAD/marker" ]]; then
  pass "a failed --reinstall exits non-zero and leaves the old install in place"
else
  fail "a failed --reinstall must keep the old install (status $STATUS): $(ls -a "${REINSTALL_DIR}/6.0-HEAD" 2>&1)"
fi
if [[ -z "$(find "$REINSTALL_DIR" -mindepth 1 -maxdepth 1 -name '.*')" ]]; then
  pass "a failed --reinstall leaves no staging directory behind"
else
  fail "a failed --reinstall left $(find "$REINSTALL_DIR" -mindepth 1 -maxdepth 1 -name '.*')"
fi

KEEP_INSTALL_DIR=1 run_cached 6.0-HEAD --url "$NIGHTLY_URL" --reinstall
if [[ "$STATUS" -eq 0 && ! -f "${REINSTALL_DIR}/6.0-HEAD/marker" && -f "${REINSTALL_DIR}/6.0-HEAD/conf/cassandra.orig.yaml" ]]; then
  pass "--reinstall replaces the install with a new, configured one"
else
  fail "--reinstall should replace the install (status $STATUS): $OUTPUT"
fi
assert_calls_contain "--reinstall downloads from the origin again" "curl -fsSL --retry 3 ${NIGHTLY_URL} -o"
if [[ -z "$(find "$REINSTALL_DIR" -mindepth 1 -maxdepth 1 -name '.*')" ]]; then
  pass "a successful --reinstall leaves no staging or old directory behind"
else
  fail "a successful --reinstall left $(find "$REINSTALL_DIR" -mindepth 1 -maxdepth 1 -name '.*')"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
