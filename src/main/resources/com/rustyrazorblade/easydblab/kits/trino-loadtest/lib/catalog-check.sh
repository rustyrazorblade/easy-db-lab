# shellcheck shell=bash
#
# The trino-loadtest kit's cqlite catalog preflight, sourced by bin/start.sh. The driver queries
# cqlite.<ks>.<tbl>, and the trino kit does not ship a cqlite catalog yet (pmcfadin/cqlite#2869), so
# start asks the target Trino for its catalogs before any pod runs and fails when cqlite is not one.
#
# It runs on this machine. Kit processes have <workspace>/bin first on PATH, so `curl` is the
# workspace wrapper, which routes this call through the SOCKS tunnel to the coordinator. It uses
# Trino's HTTP client protocol: POST /v1/statement, then GET each nextUri until there is none.

# One HTTP call's limit, and the most pages one statement may take. Trino long-polls each nextUri
# for about a second, so a SHOW CATALOGS that is still queued after this many pages is stuck.
TL_CATALOG_HTTP_TIMEOUT_SECONDS=10
TL_CATALOG_MAX_PAGES=60
TL_CATALOG_NAME="cqlite"
TL_CATALOG_ISSUE="pmcfadin/cqlite#2869"

# Prints one catalog name per line, from SHOW CATALOGS on the Trino at $1 (http://<host>:<port>) as
# user $2. Fails, naming the Trino, when it cannot be reached or the statement fails.
tl_trino_catalogs() {
  local base="$1" user="$2" page next pages=0
  page="$(curl -sS --fail-with-body --max-time "${TL_CATALOG_HTTP_TIMEOUT_SECONDS}" -X POST \
    "${base}/v1/statement" -H "X-Trino-User: ${user}" --data-binary "SHOW CATALOGS")" ||
    edl_fail "cqlite catalog preflight: could not ask the target Trino (${base}) for its catalogs: ${page:-no response}"
  while :; do
    jq -e . > /dev/null 2>&1 <<< "${page}" ||
      edl_fail "cqlite catalog preflight: the target Trino (${base}) answered with something that is not JSON: ${page}"
    if jq -e '.error' > /dev/null <<< "${page}"; then
      edl_fail "cqlite catalog preflight: SHOW CATALOGS failed on the target Trino (${base}): $(jq -r '.error.message // .error' <<< "${page}")"
    fi
    jq -r '.data[]?[0]' <<< "${page}"
    next="$(jq -r '.nextUri // empty' <<< "${page}")"
    [ -n "${next}" ] || return 0
    pages=$((pages + 1))
    [ "${pages}" -lt "${TL_CATALOG_MAX_PAGES}" ] ||
      edl_fail "cqlite catalog preflight: SHOW CATALOGS on the target Trino (${base}) did not finish within ${TL_CATALOG_MAX_PAGES} pages"
    page="$(curl -sS --fail-with-body --max-time "${TL_CATALOG_HTTP_TIMEOUT_SECONDS}" \
      "${next}" -H "X-Trino-User: ${user}")" ||
      edl_fail "cqlite catalog preflight: could not read SHOW CATALOGS from the target Trino (${base}): ${page:-no response}"
  done
}

# Fails, before any pod runs, unless the Trino at $1 has the cqlite catalog. $2 is the Trino user.
tl_require_cqlite_catalog() {
  local base="$1" user="$2" catalogs
  catalogs="$(tl_trino_catalogs "${base}" "${user}")" || exit 1
  if ! grep -Fqx "${TL_CATALOG_NAME}" <<< "${catalogs}"; then
    edl_fail "the target Trino (${base}) has no ${TL_CATALOG_NAME} catalog (it has: $(tr '\n' ' ' <<< "${catalogs}")). The trino kit does not ship one yet (${TL_CATALOG_ISSUE}); add the cqlite connector and catalog to the target Trino by hand before running trino-loadtest."
  fi
  echo "${TL_CATALOG_NAME} catalog: found on the target Trino (${base})"
}
