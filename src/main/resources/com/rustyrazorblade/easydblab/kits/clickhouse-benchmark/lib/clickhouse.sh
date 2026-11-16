# shellcheck shell=bash
#
# Short control queries against the target over its HTTP endpoint (TARGET_HTTP_URL), from the
# operator's machine through the workspace curl wrapper, so they run before any pod exists. Needs
# the shared library.

# Runs the query $1 in TARGET_NATIVE_DATABASE and prints the result. Fails with the server's error.
ch_http_query() {
  [ -n "${TARGET_HTTP_URL:-}" ] ||
    edl_fail "TARGET_HTTP_URL is empty: the target kit declares no http endpoint, which this kit needs for its checks"
  local out
  out="$(curl -sS --fail-with-body --max-time 30 --data-binary "$1" \
    "${TARGET_HTTP_URL}/?database=${TARGET_NATIVE_DATABASE:-default}" 2>&1)" ||
    edl_fail "The query against ${TARGET_HTTP_URL} failed: ${out}"
  printf '%s\n' "${out}"
}

# Prints the storage policy of the hits table, or nothing when hits does not exist.
ch_hits_storage_policy() {
  ch_http_query "SELECT storage_policy FROM system.tables WHERE database = currentDatabase() AND name = 'hits' FORMAT TSV"
}
