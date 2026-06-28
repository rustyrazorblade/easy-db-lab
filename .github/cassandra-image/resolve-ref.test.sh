#!/usr/bin/env bash
#
# Unit tests for resolve-ref.sh — the ref-resolution logic the
# build-cassandra-ref workflow uses to turn an operator-supplied ref
# (branch, tag, or commit SHA) into an immutable commit SHA, failing fast
# (naming the bad ref) when it cannot be resolved.
#
# git ls-remote is a network call, so resolve_ref takes the ls-remote
# operation as an injected command (LS_REMOTE_CMD) — tests stub it to
# simulate a resolvable ref, an unresolvable ref, or a raw SHA passed
# directly, with no network access.
#
# Run directly:  ./resolve-ref.test.sh
# Or via gradle: ./gradlew testCassandraResolveRef

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tests_run=0
tests_failed=0

# A stub ls-remote: echoes a fake SHA + refname line only for refs listed in
# RESOLVABLE_REFS (space-separated), otherwise prints nothing (as real
# git ls-remote does for an unknown ref). Args after the repo URL are the
# queried refs.
export RESOLVABLE_REFS=""
stub_ls_remote() {
  shift # drop the repo URL (first arg)
  local queried sha
  for queried in "$@"; do
    for known in $RESOLVABLE_REFS; do
      if [[ "$queried" == "$known" ]]; then
        sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        printf '%s\trefs/heads/%s\n' "$sha" "$queried"
      fi
    done
  done
}
export -f stub_ls_remote

run_resolve() {
  # Usage: run_resolve <source_ref> <resolvable-refs>
  local source_ref="$1" resolvable="$2"
  SOURCE_REPO="apache/cassandra" SOURCE_REF="$source_ref" \
    RESOLVABLE_REFS="$resolvable" LS_REMOTE_CMD="stub_ls_remote" \
    bash -c "source '${SCRIPT_DIR}/resolve-ref.sh'; resolve_ref"
}

assert_resolves_to() {
  local desc="$1" source_ref="$2" resolvable="$3" expected_sha="$4"
  tests_run=$((tests_run + 1))
  local out sha
  out="$(run_resolve "$source_ref" "$resolvable" 2>/dev/null)"
  sha="$(echo "$out" | grep '^sha=' | head -n1 | cut -d= -f2-)"
  if [[ "$sha" == "$expected_sha" ]]; then
    echo "ok   - ${desc} (sha=${sha})"
  else
    echo "FAIL - ${desc}: expected sha=${expected_sha}, got sha=${sha}"
    tests_failed=$((tests_failed + 1))
  fi
}

assert_short_sha() {
  local desc="$1" source_ref="$2" resolvable="$3" expected_short="$4"
  tests_run=$((tests_run + 1))
  local out short
  out="$(run_resolve "$source_ref" "$resolvable" 2>/dev/null)"
  short="$(echo "$out" | grep '^short_sha=' | head -n1 | cut -d= -f2-)"
  if [[ "$short" == "$expected_short" ]]; then
    echo "ok   - ${desc} (short_sha=${short})"
  else
    echo "FAIL - ${desc}: expected short_sha=${expected_short}, got short_sha=${short}"
    tests_failed=$((tests_failed + 1))
  fi
}

# Asserts resolve_ref exits non-zero AND its stderr names the bad ref.
assert_fails_naming_ref() {
  local desc="$1" source_ref="$2" resolvable="$3"
  tests_run=$((tests_run + 1))
  local err rc
  err="$(run_resolve "$source_ref" "$resolvable" 2>&1 >/dev/null)"
  rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "FAIL - ${desc}: expected non-zero exit, got success"
    tests_failed=$((tests_failed + 1))
  elif [[ "$err" != *"$source_ref"* ]]; then
    echo "FAIL - ${desc}: error did not name the bad ref '${source_ref}': ${err}"
    tests_failed=$((tests_failed + 1))
  else
    echo "ok   - ${desc} (failed naming '${source_ref}')"
  fi
}

FORTY_HEX="0123456789abcdef0123456789abcdef01234567"
FAKE_SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

# --- a resolvable branch/tag yields a SHA ------------------------------------
assert_resolves_to "resolvable branch yields its SHA" \
  cassandra-5.0 "cassandra-5.0" "$FAKE_SHA"
assert_short_sha "resolvable branch yields a 12-char short SHA" \
  cassandra-5.0 "cassandra-5.0" "${FAKE_SHA:0:12}"

# --- a 40-hex input is accepted as a raw SHA even with no remote match -------
assert_resolves_to "40-hex input accepted as a raw SHA" \
  "$FORTY_HEX" "" "$FORTY_HEX"

# --- an unresolvable ref fails non-zero, naming the bad ref ------------------
assert_fails_naming_ref "unresolvable ref fails naming the ref" \
  no-such-ref ""
# A too-short hex string is NOT a valid raw SHA and must still fail-fast.
assert_fails_naming_ref "short hex string is not a raw SHA" \
  abc123 ""

echo ""
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
