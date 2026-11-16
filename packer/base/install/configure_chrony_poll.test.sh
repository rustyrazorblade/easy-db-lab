#!/usr/bin/env bash
#
# Unit tests for configure_chrony_poll.sh: every Amazon Time Sync source in the chrony config is
# polled every 8 s (minpoll 3 maxpoll 3), every other line is left alone, and the script fails when
# the config has no Time Sync source.
#
# sudo is stubbed on PATH and CHRONY_DIR points at a sandbox, so this needs no root, no network and
# no Docker.
#
# Run directly:  ./configure_chrony_poll.test.sh
# Or via gradle: ./gradlew testBaseChronyPoll

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/configure_chrony_poll.sh"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

BIN="${SANDBOX}/bin"
mkdir -p "$BIN"
cat >"${BIN}/sudo" <<'SHIM'
#!/bin/bash
exec "$@"
SHIM
chmod +x "${BIN}/sudo"

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

# Builds a chrony dir shaped like Ubuntu's EC2 image: the cloud drop-in, the stock pool sources and
# a main chrony.conf.
make_chrony_dir() {
  local dir="$1"
  mkdir -p "${dir}/conf.d" "${dir}/sources.d"
  cat >"${dir}/conf.d/00-cpc.conf" <<'EOF'
# Amazon Time Sync Service
server 169.254.169.123 prefer iburst minpoll 4 maxpoll 4
server fd00:ec2::123 iburst minpoll 4 maxpoll 4
#server 169.254.169.123 minpoll 6 maxpoll 6
server 169.254.169.1234 iburst minpoll 6 maxpoll 6
server 10.0.0.5 minpoll 6
EOF
  cat >"${dir}/sources.d/ubuntu-ntp-pools.sources" <<'EOF'
pool ntp.ubuntu.com iburst maxsources 4 maxpoll 7
EOF
  cat >"${dir}/chrony.conf" <<'EOF'
confdir /etc/chrony/conf.d
sourcedir /etc/chrony/sources.d
makestep 1 3
EOF
  chmod 0640 "${dir}/conf.d/00-cpc.conf"
}

run_script() {
  PATH="${BIN}:${PATH}" CHRONY_DIR="$1" bash "$SCRIPT" 2>&1
}

# --- The Ubuntu EC2 config moves to an 8 s poll ---
DIR="${SANDBOX}/cpc"
make_chrony_dir "$DIR"
cp "${DIR}/sources.d/ubuntu-ntp-pools.sources" "${SANDBOX}/pools.before"
cp "${DIR}/chrony.conf" "${SANDBOX}/chrony.before"
OUTPUT="$(run_script "$DIR")"
STATUS=$?

if [[ "$STATUS" -eq 0 ]]; then
  pass "exits 0 on the Ubuntu EC2 config"
else
  fail "should exit 0, got ${STATUS}: ${OUTPUT}"
fi

CPC="${DIR}/conf.d/00-cpc.conf"
if grep -qx 'server 169.254.169.123 prefer iburst minpoll 3 maxpoll 3' "$CPC"; then
  pass "the IPv4 Time Sync source polls every 8 s and keeps prefer iburst"
else
  fail "IPv4 Time Sync line is wrong: $(grep '^server 169' "$CPC")"
fi

if grep -qx 'server fd00:ec2::123 iburst minpoll 3 maxpoll 3' "$CPC"; then
  pass "the IPv6 Time Sync source polls every 8 s"
else
  fail "IPv6 Time Sync line is wrong: $(grep '^server fd00' "$CPC")"
fi

if grep -qx '#server 169.254.169.123 minpoll 6 maxpoll 6' "$CPC"; then
  pass "a commented-out Time Sync line is left alone"
else
  fail "the commented-out line changed: $(grep '^#server' "$CPC")"
fi

if grep -qx 'server 169.254.169.1234 iburst minpoll 6 maxpoll 6' "$CPC" \
  && grep -qx 'server 10.0.0.5 minpoll 6' "$CPC"; then
  pass "other servers in the same file, including a near-miss address, are left alone"
else
  fail "a non-Time-Sync server line changed: $(grep -E '^server (169.254.169.1234|10\.)' "$CPC")"
fi

if cmp -s "${SANDBOX}/pools.before" "${DIR}/sources.d/ubuntu-ntp-pools.sources" \
  && cmp -s "${SANDBOX}/chrony.before" "${DIR}/chrony.conf"; then
  pass "files with no Time Sync source are not changed"
else
  fail "a file with no Time Sync source changed"
fi

if [[ "$(stat -c %a "$CPC" 2>/dev/null || stat -f %Lp "$CPC")" == "640" ]]; then
  pass "the rewritten file keeps its mode"
else
  fail "the rewritten file lost its 0640 mode"
fi

# --- A second run gives the same file ---
cp "$CPC" "${SANDBOX}/cpc.first"
OUTPUT="$(run_script "$DIR")"
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && cmp -s "${SANDBOX}/cpc.first" "$CPC"; then
  pass "a second run changes nothing"
else
  fail "a second run changed the file or failed (${STATUS}): $(cat "$CPC")"
fi

# --- A Time Sync source with no poll options gets them, in chrony.conf itself ---
DIR="${SANDBOX}/bare"
mkdir -p "$DIR"
printf 'server 169.254.169.123 prefer iburst\nmakestep 1 3\n' >"${DIR}/chrony.conf"
OUTPUT="$(run_script "$DIR")"
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && grep -qx 'server 169.254.169.123 prefer iburst minpoll 3 maxpoll 3' "${DIR}/chrony.conf"; then
  pass "a Time Sync source with no poll options gets minpoll 3 maxpoll 3"
else
  fail "bare Time Sync source not updated (${STATUS}): $(cat "${DIR}/chrony.conf")"
fi

# --- An indented pool line with tabs and its poll options split around another option ---
DIR="${SANDBOX}/split"
mkdir -p "${DIR}/sources.d"
printf '\tpool 169.254.169.123\tminpoll 4 prefer maxpoll 4\n' >"${DIR}/sources.d/aws.sources"
OUTPUT="$(run_script "$DIR")"
STATUS=$?
if [[ "$STATUS" -eq 0 ]] && grep -qx "$(printf '\tpool 169.254.169.123 prefer minpoll 3 maxpoll 3')" "${DIR}/sources.d/aws.sources"; then
  pass "an indented, tab-separated pool line with split poll options ends with minpoll 3 maxpoll 3"
else
  fail "split pool line not updated (${STATUS}): $(cat "${DIR}/sources.d/aws.sources")"
fi

# --- An unreadable config file fails the bake instead of being skipped ---
# Root reads any file, so this case only means something for a non-root run (CI, a laptop).
if [[ "$(id -u)" -ne 0 ]]; then
  DIR="${SANDBOX}/unreadable"
  make_chrony_dir "$DIR"
  printf 'server fd00:ec2::123 iburst\n' >"${DIR}/conf.d/10-v6.conf"
  chmod 000 "${DIR}/conf.d/10-v6.conf"
  OUTPUT="$(run_script "$DIR")"
  STATUS=$?
  chmod 644 "${DIR}/conf.d/10-v6.conf"
  if [[ "$STATUS" -ne 0 ]] && [[ "$OUTPUT" == *"cannot read ${DIR}/conf.d/10-v6.conf"* ]]; then
    pass "an unreadable config file fails the bake and is named"
  else
    fail "an unreadable config file should fail the bake, got ${STATUS}: ${OUTPUT}"
  fi
fi

# --- No chrony config at all: the bake fails with a clear message ---
DIR="${SANDBOX}/empty"
mkdir -p "$DIR"
OUTPUT="$(run_script "$DIR")"
STATUS=$?
if [[ "$STATUS" -ne 0 ]] && [[ "$OUTPUT" == *"no chrony config"* ]]; then
  pass "fails with a clear message when there is no chrony config"
else
  fail "should fail on an empty chrony dir, got ${STATUS}: ${OUTPUT}"
fi

# --- No Time Sync source: the bake fails and nothing changes ---
DIR="${SANDBOX}/none"
mkdir -p "${DIR}/sources.d"
printf 'pool ntp.ubuntu.com iburst maxsources 4\n' >"${DIR}/sources.d/ubuntu-ntp-pools.sources"
cp "${DIR}/sources.d/ubuntu-ntp-pools.sources" "${SANDBOX}/none.before"
OUTPUT="$(run_script "$DIR")"
STATUS=$?
if [[ "$STATUS" -ne 0 ]] && [[ "$OUTPUT" == *"no Amazon Time Sync source"* ]]; then
  pass "fails when the config has no Time Sync source"
else
  fail "should fail with no Time Sync source, got ${STATUS}: ${OUTPUT}"
fi
if cmp -s "${SANDBOX}/none.before" "${DIR}/sources.d/ubuntu-ntp-pools.sources"; then
  pass "a failed run changes nothing"
else
  fail "a failed run changed the pool sources"
fi

echo
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
