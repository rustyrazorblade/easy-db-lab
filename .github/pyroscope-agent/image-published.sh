#!/usr/bin/env bash
#
# Decide whether the publish-pyroscope-agent job pushes $IMAGE, writing exists=true or
# exists=false to $GITHUB_OUTPUT.
#
# A tag that inspects is published and is never overwritten (exists=true). Only the registry's
# definite answer that the tag is missing (`ERROR: <image>: not found`) leads to a push
# (exists=false). Any other inspect failure - authentication, network, rate limit - fails the job
# and writes nothing, because guessing "not published" there would overwrite a published tag.
#
# Tested by image-published.test.sh (./gradlew testPyroscopeAgentPublished).

set -euo pipefail

: "${IMAGE:?IMAGE must name the image to check}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT must name the step output file}"

if errors="$(docker buildx imagetools inspect "$IMAGE" 2>&1 >/dev/null)"; then
  echo "$IMAGE is already published; not overwriting it."
  echo "exists=true" >> "$GITHUB_OUTPUT"
elif [[ "$errors" == *"ERROR: $IMAGE: not found"* ]]; then
  echo "$IMAGE is not published yet; publishing it."
  echo "exists=false" >> "$GITHUB_OUTPUT"
else
  echo "Could not tell whether $IMAGE is published; not pushing:" >&2
  echo "$errors" >&2
  exit 1
fi
