#!/usr/bin/env bash
#
# Fail the publish-cassandra-runtime job unless the pushed $IMAGE is a manifest list holding both
# linux/amd64 and linux/arm64. The cassandra-pods kit runs the same tag on amd64 and Graviton db
# nodes, so a tag missing one platform would leave those pods unable to pull it.
#
# Tested by platforms-present.test.sh (./gradlew testCassandraRuntimePlatforms).

set -euo pipefail

: "${IMAGE:?IMAGE must name the image to check}"

raw="$(docker buildx imagetools inspect --raw "$IMAGE")" || {
  echo "Could not inspect $IMAGE" >&2
  exit 1
}

present="$(jq -r '.manifests[]?.platform | "\(.os)/\(.architecture)"' <<< "$raw")"
missing=""
for platform in linux/amd64 linux/arm64; do
  grep -qx "$platform" <<< "$present" || missing="$missing $platform"
done

if [ -n "$missing" ]; then
  echo "$IMAGE is missing platform(s):$missing" >&2
  exit 1
fi
echo "$IMAGE has linux/amd64 and linux/arm64."
