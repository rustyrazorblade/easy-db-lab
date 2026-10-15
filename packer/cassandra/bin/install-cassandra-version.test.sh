#!/usr/bin/env bash
#
# Unit tests for install-cassandra-version — the one script both the AMI bake and
# `easy-db-lab cassandra install` use to install a single Cassandra version.
#
# These cover the decisions the script makes before it touches the network: argument handling,
# the already-installed early return, the guards on building from source, and JDK selection. The
# install paths themselves (download/extract/build) are exercised against a real container, not
# here. No network, no Docker, no root: sudo/dpkg/curl/git are stubbed on PATH and the install
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

# The tests above must not depend on the machine they run on: a node or a packer container has the
# real cache library at its default path. The download-cache cases below load it explicitly.
export EDL_CACHE_LIB=/nonexistent

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

run_script --help
if [[ "$STATUS" -eq 0 && "$OUTPUT" == *"Usage: install-cassandra-version"* ]]; then
  pass "--help exits 0 with usage"
else
  fail "--help should exit 0 with usage, got status ${STATUS}"
fi

# --- already installed is a no-op --------------------------------------------
mkdir -p "${CASSANDRA_INSTALL_DIR}/5.0"
run_script 5.0
if [[ "$STATUS" -eq 0 && "$OUTPUT" == *"already installed"* ]]; then
  pass "an installed version exits 0 without reinstalling"
else
  fail "an installed version should exit 0 as a no-op, got status ${STATUS}: ${OUTPUT}"
fi
assert_no_reach_out "an installed version downloads nothing"

# --- building from source needs both a repo and a JDK ------------------------
assert_fails_with "a branch with no url is rejected" "requires both --url and --branch" \
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
  "Missing ${CASSANDRA_IN_SH_SNIPPET}" 4.1
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

# aws s3 cp <src> <dest>: a read under download-cache/ is a miss unless AWS_CACHE_HIT=1, a local
# destination gets the fixture tarball, and a destination under download-cache/ is a write-back.
cat >"${CACHE_BIN}/aws" <<SHIM
#!/bin/bash
echo "aws \$*" >> "${CALLS}"
[[ "\$1" == s3 && "\$2" == cp ]] || exit 1
case "\$3" in
  *download-cache/*) [[ "\${AWS_CACHE_HIT:-}" == 1 ]] || exit 1 ;;
esac
case "\$4" in
  s3://*) exit 0 ;;
esac
cp "${FIXTURE}" "\$4"
SHIM

# curl lists one release on the dlcdn index, and with -o downloads the fixture tarball.
cat >"${CACHE_BIN}/curl" <<SHIM
#!/bin/bash
echo "curl \$*" >> "${CALLS}"
dest=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    -o) dest="\$2"; shift 2 ;;
    *) shift ;;
  esac
done
if [[ -z "\$dest" ]]; then
  echo '<a href="5.0.6/">5.0.6/</a>'
  exit 0
fi
[[ "\${CURL_FAILS:-}" == 1 ]] && exit 22
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

# Runs the script with the real cache library, a bucket configured, the cache stubs first on PATH,
# and a new install directory (an earlier case leaves its version installed). Extra environment for
# the stubs comes first as NAME=value arguments.
run_cached() {
  local env_args=()
  while [[ "$1" == *=* ]]; do
    env_args+=("$1")
    shift
  done
  case_number=$((case_number + 1))
  : >"$CALLS"
  OUTPUT="$(env ${env_args[@]+"${env_args[@]}"} \
    PATH="${CACHE_BIN}:${PATH}" \
    EDL_CACHE_LIB="${SCRIPT_DIR}/../../base/install/edl-cache-lib.sh" \
    EDL_S3_BUCKET=test-bucket \
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
assert_output_contains "a failed url download reports the existing error" "Failed to download version 6.0-HEAD from ${NIGHTLY_URL}"
assert_calls_contain "a failed url download was attempted from its url" "curl -fsSL --retry 3 ${NIGHTLY_URL} -o"
assert_calls_lack "a failed url download does not fall back to the cache" "download-cache/"

run_cached 5.0-my-build --url "$S3_URL"
assert_calls_contain "an s3 url is copied directly from S3" "aws s3 cp ${S3_URL} "
assert_output_contains "an s3 url logs the direct download" "direct download (not cached): ${S3_URL}"
assert_calls_lack "an s3 url does not touch the download cache" "download-cache/"
assert_calls_lack "an s3 url is not fetched with curl" "curl"

run_cached 5.0
assert_calls_contain "a released version reads the download cache under its full release number" \
  "aws s3 cp s3://test-bucket/download-cache/${RELEASE_KEY} "
assert_output_contains "a released version reports a cache miss" "cache miss: cassandra-dist/5.0.6/"
assert_calls_contain "a released version is written back to the download cache" \
  "s3://test-bucket/download-cache/${RELEASE_KEY} --no-progress"
assert_calls_contain "a released cache miss downloads the release from dlcdn" \
  "curl -fsSL --retry 3 https://dlcdn.apache.org/cassandra/5.0.6/apache-cassandra-5.0.6-bin.tar.gz -o"

run_cached AWS_CACHE_HIT=1 5.0
assert_output_contains "a released version reports a cache hit" "cache hit:  ${RELEASE_KEY}"
assert_calls_lack "a released cache hit does not download the release from dlcdn" "apache-cassandra-5.0.6-bin.tar.gz -o"

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
