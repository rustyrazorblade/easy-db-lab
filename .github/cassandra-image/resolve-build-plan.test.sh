#!/usr/bin/env bash
#
# Unit tests for resolve-build-plan.sh — the pure, deterministic logic the
# build-cassandra-ref workflow uses to map a resolved Cassandra version + ref
# into a build plan (build JDK, ant flags, runtime base image, image tags,
# tarball/release names).
#
# These run with no network and no Docker: they exercise compute_build_plan in
# isolation so the workflow's "thinking" is covered by an automated test rather
# than only by a manual workflow_dispatch run.
#
# Run directly:  ./resolve-build-plan.test.sh
# Or via gradle: ./gradlew testCassandraBuildPlan

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.github/cassandra-image/resolve-build-plan.sh
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/resolve-build-plan.sh"

tests_run=0
tests_failed=0

# field <key> <plan-output> -> value for key=... line
field() {
  local key="$1" plan="$2"
  echo "$plan" | grep "^${key}=" | head -n1 | cut -d= -f2-
}

# Assert that compute_build_plan with the given env emits key=expected.
# Usage: assert_field <desc> <key> <expected> [VAR=val ...]
assert_field() {
  local desc="$1" key="$2" expected="$3"
  shift 3
  tests_run=$((tests_run + 1))
  local plan
  plan="$(env "$@" bash -c "source '${SCRIPT_DIR}/resolve-build-plan.sh'; compute_build_plan")"
  local actual
  actual="$(field "$key" "$plan")"
  if [[ "$actual" == "$expected" ]]; then
    echo "ok   - ${desc} (${key}=${actual})"
  else
    echo "FAIL - ${desc}: expected ${key}=${expected}, got ${key}=${actual}"
    tests_failed=$((tests_failed + 1))
  fi
}

# Assert that compute_build_plan exits non-zero with the given env.
# Usage: assert_fails <desc> [VAR=val ...]
assert_fails() {
  local desc="$1"
  shift
  tests_run=$((tests_run + 1))
  if env "$@" bash -c "source '${SCRIPT_DIR}/resolve-build-plan.sh'; compute_build_plan" >/dev/null 2>&1; then
    echo "FAIL - ${desc}: expected non-zero exit, got success"
    tests_failed=$((tests_failed + 1))
  else
    echo "ok   - ${desc} (failed as expected)"
  fi
}

BASE_ENV=(VERSION=5.0.3 SHORT_SHA=abc123def456 SOURCE_REF=cassandra-5.0
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=Owner REPO_NAME=Easy-DB-Lab)

# --- F8: auto-mapped JDK and base image per major version ---------------------
assert_field "4.x auto-maps to JDK 11" build_jdk 11 \
  VERSION=4.0.12 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-4.0 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "4.x auto-maps to pinned temurin 11 jammy base" base_image eclipse-temurin:11-jre-jammy \
  VERSION=4.1.5 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-4.1 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "5.0 auto-maps to JDK 17" build_jdk 17 \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-5.0 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "5.0 auto-maps to pinned temurin 17 noble base" base_image eclipse-temurin:17-jre-noble \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-5.0 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "trunk (5.1) auto-maps to JDK 21" build_jdk 21 \
  VERSION=5.1 SHORT_SHA=deadbeef0000 SOURCE_REF=trunk \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "trunk auto-maps to pinned temurin 21 noble base" base_image eclipse-temurin:21-jre-noble \
  VERSION=5.1 SHORT_SHA=deadbeef0000 SOURCE_REF=trunk \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r

# --- F1: 4.x must build with -Duse.jdk11=true under the auto-mapped JDK 11 ----
assert_field "4.0 emits use.jdk11 ant flag" ant_flags -Duse.jdk11=true \
  VERSION=4.0.12 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-4.0 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "4.1 emits use.jdk11 ant flag" ant_flags -Duse.jdk11=true \
  VERSION=4.1.5 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-4.1 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "5.0 emits no extra ant flags" ant_flags "" \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-5.0 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "trunk emits no extra ant flags" ant_flags "" \
  VERSION=5.1 SHORT_SHA=deadbeef0000 SOURCE_REF=trunk \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r

# --- F9/F10: JDK override wins over auto-map ----------------------------------
assert_field "jdk override wins over auto-map" build_jdk 21 \
  VERSION=4.0.12 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-4.0 \
  JDK_OVERRIDE=21 BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r

# --- F7: base_image override wins over auto-map -------------------------------
assert_field "base_image override wins over auto-map" base_image my-registry/jre:custom \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF=cassandra-5.0 \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE=my-registry/jre:custom REPO_OWNER=o REPO_NAME=r

# --- F11: ref sanitized into a valid image tag -------------------------------
assert_field "feature/foo ref is sanitized" ref_tag feature-foo \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF=feature/foo \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r
assert_field "clean ref tag passes through" ref_tag cassandra-5.0 "${BASE_ENV[@]}"

# --- F11: latest tag is guarded against ----------------------------------
assert_fails "ref resolving to latest is rejected" \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF=latest \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r

# --- F10: deterministic, non-colliding tags & names --------------------------
assert_field "sha tag from short sha" sha_tag sha-abc123def456 "${BASE_ENV[@]}"
assert_field "tarball name embeds version + sha" tarball_name \
  apache-cassandra-5.0.3-abc123def456-bin.tar.gz "${BASE_ENV[@]}"
assert_field "release tag embeds version + sha" release_tag \
  cassandra-5.0.3-abc123def456 "${BASE_ENV[@]}"
# Distinct short SHAs must produce distinct artifacts (no silent overwrite).
plan_a="$(env VERSION=5.0.3 SHORT_SHA=aaaaaaaaaaaa SOURCE_REF=trunk JDK_OVERRIDE= \
  BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r \
  bash -c "source '${SCRIPT_DIR}/resolve-build-plan.sh'; compute_build_plan")"
plan_b="$(env VERSION=5.0.3 SHORT_SHA=bbbbbbbbbbbb SOURCE_REF=trunk JDK_OVERRIDE= \
  BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r \
  bash -c "source '${SCRIPT_DIR}/resolve-build-plan.sh'; compute_build_plan")"
tests_run=$((tests_run + 1))
if [[ "$(field release_tag "$plan_a")" != "$(field release_tag "$plan_b")" \
  && "$(field tarball_name "$plan_a")" != "$(field tarball_name "$plan_b")" \
  && "$(field sha_tag "$plan_a")" != "$(field sha_tag "$plan_b")" ]]; then
  echo "ok   - distinct short SHAs yield distinct artifacts"
else
  echo "FAIL - distinct short SHAs collided"
  tests_failed=$((tests_failed + 1))
fi

# --- image repo is lowercased ------------------------------------------------
assert_field "image repo lowercases owner/name" image_repo \
  ghcr.io/owner/easy-db-lab/cassandra "${BASE_ENV[@]}"

# --- unreadable version fails fast -------------------------------------------
assert_fails "empty version fails fast" \
  VERSION= SHORT_SHA=deadbeef0000 SOURCE_REF=trunk \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r

# --- missing ref fails fast --------------------------------------------------
# A blank SOURCE_REF must not produce a plan with an empty ref_tag (which would
# yield an invalid `IMAGE_REPO:` Docker reference deep in the publish job). Fail
# fast in resolve instead, mirroring the empty-version guard.
assert_fails "empty source ref fails fast" \
  VERSION=5.0.3 SHORT_SHA=deadbeef0000 SOURCE_REF= \
  JDK_OVERRIDE= BASE_IMAGE_OVERRIDE= REPO_OWNER=o REPO_NAME=r

echo ""
echo "${tests_run} tests, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
