#!/usr/bin/env bash
#
# Ref resolution for the build-cassandra-ref workflow.
#
# resolve_ref turns an operator-supplied ref (branch, tag, or commit SHA) into
# an immutable commit SHA, failing fast (naming the bad ref) when it cannot be
# resolved. This is the most testable of the workflow's end-to-end behaviors
# (Scenario: Nonexistent ref fails fast), so it is factored out of the workflow
# YAML and unit-tested in resolve-ref.test.sh.
#
# git ls-remote is a network call, so the ls-remote operation is injected via
# LS_REMOTE_CMD (default: the real `git ls-remote`). Tests stub it to simulate
# resolvable / unresolvable refs with no network access. The raw-SHA acceptance
# and fail-fast paths are pure logic and need no remote at all.
#
# On success, resolve_ref emits the resolved SHA + short SHA as key=value lines
# on stdout (the workflow appends them to $GITHUB_OUTPUT):
#   sha=<40-hex>
#   short_sha=<first 12>
#
# Required environment inputs:
#   SOURCE_REPO   source repo (owner/name), e.g. apache/cassandra or a fork
#   SOURCE_REF    operator-supplied ref (branch, tag, or commit SHA)
# Optional:
#   LS_REMOTE_CMD command used to query the remote (default: "git ls-remote")

# Resolve SOURCE_REF in SOURCE_REPO to an immutable SHA. Returns non-zero,
# naming the ref, when it cannot be resolved.
resolve_ref() {
  local source_repo="${SOURCE_REPO:-}"
  local source_ref="${SOURCE_REF:-}"
  local ls_remote_cmd="${LS_REMOTE_CMD:-git ls-remote}"

  if [[ -z "$source_ref" ]]; then
    echo "::error::no ref supplied" >&2
    return 1
  fi

  local repo_url="https://github.com/${source_repo}.git"
  echo "Resolving '${source_ref}' in ${repo_url}" >&2

  # Try the ref as given first, then explicitly as a tag and a branch.
  #
  # git ls-remote is a network call, so a non-zero exit (unreachable repo,
  # transient failure) is expected and recoverable: a directly-supplied raw
  # SHA must still resolve, and a genuinely missing ref must reach the
  # fail-fast naming branch below. The workflow runs under `set -euo
  # pipefail`, so each invocation is guarded with `|| true` to keep an
  # ls-remote error from aborting the script at the assignment line.
  local sha
  sha="$($ls_remote_cmd "$repo_url" "$source_ref" 2>/dev/null \
    | awk '{print $1}' | head -n1 || true)"
  if [[ -z "$sha" ]]; then
    sha="$($ls_remote_cmd "$repo_url" \
      "refs/tags/${source_ref}" "refs/heads/${source_ref}" 2>/dev/null \
      | awk '{print $1}' | head -n1 || true)"
  fi

  # Fall back to accepting a full (40-hex) commit SHA passed directly.
  if [[ -z "$sha" && "$source_ref" =~ ^[0-9a-f]{40}$ ]]; then
    sha="$source_ref"
  fi

  if [[ -z "$sha" ]]; then
    echo "::error::ref '${source_ref}' does not exist in ${source_repo}" >&2
    return 1
  fi

  echo "Resolved to ${sha}" >&2
  echo "sha=${sha}"
  echo "short_sha=${sha:0:12}"
}
