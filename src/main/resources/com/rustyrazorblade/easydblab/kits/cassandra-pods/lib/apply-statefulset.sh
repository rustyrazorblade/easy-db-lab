#!/usr/bin/env bash
# Applies the kit's StatefulSet, filled in from what this start (or upgrade) resolved:
#   - with --image: statefulset-image.yaml, from resolved-image.env (written by check-start.sh);
#   - otherwise: statefulset.yaml, the tarball path, from the cassandra-artifact contributor's
#     EDL_* variables (the artifact, the runtime image and the config hash).
# Both take their CPU and memory from the contributor's EDL_CASSANDRA_RESOURCES and
# EDL_INIT_RESOURCES (JSON, which is YAML flow syntax).
#
# Every value is substituted literally: a presigned URL holds '&', and a value can never be read as
# sed syntax (backslash, '&' and the '|' delimiter are escaped) or end the YAML string it sits in.
set -euo pipefail

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

# Escapes a replacement for `sed s|...|<here>|g`.
sed_escape() {
  printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'
}

# Escapes a value placed inside a double-quoted YAML string.
yaml_escape() {
  printf '%s' "$1" | sed -e 's/[\\"]/\\&/g'
}

# A value for a double-quoted YAML string, and one placed as YAML itself (the JSON resources).
quoted=()
raw=()

# require <name>: fails naming the variable when it is unset or empty.
require() {
  [ -n "${!1:-}" ] || fail "ERROR: $1 is not set; run start through easy-db-lab, whose cassandra-artifact contributor sets it."
}

for name in EDL_CASSANDRA_RESOURCES EDL_INIT_RESOURCES; do
  require "${name}"
  raw+=("${name}")
done

if [ -n "${CASSANDRA_IMAGE:-}" ]; then
  [ -f resolved-image.env ] || fail "ERROR: resolved-image.env is missing; the start check did not run."
  . ./resolved-image.env
  template=statefulset-image.yaml
  quoted+=(RESOLVED_IMAGE RESOLVED_BUILD)
else
  template=statefulset.yaml
  for name in EDL_TARBALL_URL EDL_TARBALL_SHA256 EDL_CASSANDRA_BUILD EDL_RUNTIME_IMAGE EDL_CONFIG_HASH; do
    require "${name}"
  done
  # The fallback is empty for a cassandra-builds build, which has one URL.
  quoted+=(EDL_TARBALL_URL EDL_TARBALL_FALLBACK_URL EDL_TARBALL_SHA256 EDL_CASSANDRA_BUILD EDL_RUNTIME_IMAGE EDL_CONFIG_HASH)
fi

script=()
for name in "${quoted[@]}"; do
  script+=(-e "s|@${name}@|$(sed_escape "$(yaml_escape "${!name:-}")")|g")
done
# The template holds `resources: {} # @NAME@`, valid YAML before the fill-in; the `{}` and the comment go.
for name in "${raw[@]}"; do
  script+=(-e "s|{} # @${name}@|$(sed_escape "${!name}")|g")
done

sed "${script[@]}" "${template}" | kubectl apply -f -
