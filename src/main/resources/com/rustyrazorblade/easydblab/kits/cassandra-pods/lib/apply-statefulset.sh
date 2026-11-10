#!/usr/bin/env bash
# Applies statefulset.yaml with the image and build label that check-start.sh resolved.
set -euo pipefail

[ -f resolved-image.env ] || {
  echo "ERROR: resolved-image.env is missing; the start check did not run." >&2
  exit 1
}
. ./resolved-image.env
sed -e "s|@RESOLVED_IMAGE@|${RESOLVED_IMAGE}|g" -e "s|@RESOLVED_BUILD@|${RESOLVED_BUILD}|g" statefulset.yaml |
  kubectl apply -f -
