#!/usr/bin/env bash
# Applies statefulset.yaml with the image and build label that check-start.sh resolved.
set -euo pipefail

[ -f resolved-image.env ] || {
  echo "ERROR: resolved-image.env is missing; the start check did not run." >&2
  exit 1
}
. ./resolved-image.env
# check-start.sh already limits the image to [A-Za-z0-9._/:@-]; the replacement is escaped anyway
# (backslash, '&', and the '|' delimiter), so a value can never be read as sed syntax.
sed_escape() {
  printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'
}
sed -e "s|@RESOLVED_IMAGE@|$(sed_escape "${RESOLVED_IMAGE}")|g" \
  -e "s|@RESOLVED_BUILD@|$(sed_escape "${RESOLVED_BUILD}")|g" statefulset.yaml |
  kubectl apply -f -
