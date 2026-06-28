#!/usr/bin/env bash
#
# Pure, deterministic build-plan logic for the build-cassandra-ref workflow.
#
# Given a resolved Cassandra version and ref (plus optional operator overrides),
# compute_build_plan emits the build plan as key=value lines on stdout:
#   version build_jdk ant_flags base_image image_repo sha_tag ref_tag
#   tarball_name release_tag
#
# This is factored out of the workflow YAML so the version->JDK/ant-flags/base-image
# mapping, the ref-tag sanitization, the `latest` guard, and the artifact naming
# can be unit-tested with no network and no Docker (see resolve-build-plan.test.sh).
# The workflow sources this file and calls compute_build_plan, appending its
# output to $GITHUB_OUTPUT.
#
# Required environment inputs:
#   VERSION              Cassandra base.version (from build.xml)
#   SHORT_SHA            short commit SHA the ref resolved to
#   SOURCE_REF           the operator-supplied ref (used for the moving ref tag)
#   JDK_OVERRIDE         optional build-JDK override (blank = auto-map)
#   BASE_IMAGE_OVERRIDE  optional runtime base-image override (blank = auto-map)
#   REPO_OWNER           GitHub repository owner (for the GHCR image repo)
#   REPO_NAME            GitHub repository name (for the GHCR image repo)

# Emit the build plan as key=value lines. Returns non-zero on invalid input
# (empty version, or a ref that would produce the reserved `latest` tag).
compute_build_plan() {
  local version="${VERSION:-}"
  local short_sha="${SHORT_SHA:-}"
  local source_ref="${SOURCE_REF:-}"
  local jdk_override="${JDK_OVERRIDE:-}"
  local base_image_override="${BASE_IMAGE_OVERRIDE:-}"
  local repo_owner="${REPO_OWNER:-}"
  local repo_name="${REPO_NAME:-}"

  if [[ -z "$version" ]]; then
    echo "::error::could not determine Cassandra version" >&2
    return 1
  fi

  if [[ -z "$source_ref" ]]; then
    echo "::error::no source ref supplied; cannot derive an image tag" >&2
    return 1
  fi

  # Auto-map build JDK, ant flags, and runtime base image from the major version.
  #   4.x  -> JDK 11; Cassandra 4.0/4.1 default to JDK 8 and require
  #           -Duse.jdk11=true to compile under 11 (matches cassandra_versions.yaml).
  #   5.0  -> JDK 17; no extra ant flags.
  #   5.1+/trunk (no tag yet) -> JDK 21; no extra ant flags.
  local auto_jdk auto_base ant_flags
  case "$version" in
    4.*)  auto_jdk=11; auto_base=eclipse-temurin:11-jre; ant_flags="-Duse.jdk11=true" ;;
    5.0*) auto_jdk=17; auto_base=eclipse-temurin:17-jre; ant_flags="" ;;
    *)    auto_jdk=21; auto_base=eclipse-temurin:21-jre; ant_flags="" ;;
  esac

  local build_jdk="${jdk_override:-$auto_jdk}"
  local base_image="${base_image_override:-$auto_base}"

  # Sanitize the ref into a valid image tag using the same transform as
  # publish-container.yml (replace tag-invalid chars with '-', cap 128).
  local ref_tag sha_tag
  ref_tag="$(echo "$source_ref" | sed 's/[^a-zA-Z0-9._-]/-/g' | cut -c1-128)"
  sha_tag="sha-${short_sha}"

  # Guard: this workflow must never produce the reserved `latest` tag.
  if [[ "$ref_tag" == "latest" || "$sha_tag" == "latest" ]]; then
    echo "::error::refusing to produce the reserved 'latest' tag" >&2
    return 1
  fi

  # GHCR image repo: ghcr.io/<owner>/<repo>/cassandra (lowercased).
  local owner_lc repo_lc image_repo
  owner_lc="$(echo "$repo_owner" | tr '[:upper:]' '[:lower:]')"
  repo_lc="$(echo "$repo_name" | tr '[:upper:]' '[:lower:]')"
  image_repo="ghcr.io/${owner_lc}/${repo_lc}/cassandra"

  local tarball_name release_tag
  tarball_name="apache-cassandra-${version}-${short_sha}-bin.tar.gz"
  release_tag="cassandra-${version}-${short_sha}"

  echo "version=${version}"
  echo "build_jdk=${build_jdk}"
  echo "ant_flags=${ant_flags}"
  echo "base_image=${base_image}"
  echo "image_repo=${image_repo}"
  echo "sha_tag=${sha_tag}"
  echo "ref_tag=${ref_tag}"
  echo "tarball_name=${tarball_name}"
  echo "release_tag=${release_tag}"
}
