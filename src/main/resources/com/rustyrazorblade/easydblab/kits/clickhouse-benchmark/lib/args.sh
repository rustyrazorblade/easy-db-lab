# shellcheck shell=bash
#
# Checks and maps the clickhouse-benchmark kit's command args. Every check runs on the operator's
# machine before any pod or ConfigMap exists, and fails through edl_fail, naming the value. Needs
# the shared library. Values reach SQL or the tool's command line, so they are validated, never
# escaped.

# The ClickBench dataset in S3, read anonymously (NOSIGN): the cluster's IAM role can read only
# easy-db-lab-* buckets, so a signed request to this public bucket is denied.
CH_CLICKBENCH_SOURCE_URL='https://clickhouse-public-datasets.s3.amazonaws.com/hits_compatible/athena_partitioned/hits_{0..99}.parquet'

# The workloads the kit packages, under workloads/.
CH_WORKLOADS="clickbench"

# The clickhouse-benchmark options start sets itself, or that the kit does not support; a --settings
# entry may not set one.
CH_SETTINGS_DENYLIST="host port user password database secure queries_file query concurrency iterations timelimit delay json
randomize continue_on_errors cumulative reconnect query_id confidence stage max_consecutive_errors help"

# Fails unless $1 is a packaged workload, listing the valid ones.
ch_check_workload() {
  case " ${CH_WORKLOADS} " in
    *" $1 "*) ;;
    *) edl_fail "Unknown workload '$1'; valid workloads: ${CH_WORKLOADS}" ;;
  esac
}

# Chooses the queries `start` runs from --workload ($1) and --queries ($2) and the kit directory
# ($3). Sets CH_QUERIES_FILE to the file to send, and CH_NEEDS_HITS to true for a packaged workload,
# which reads hits, or false for the operator's own file. With neither arg it runs ClickBench.
ch_select_queries() {
  local workload="$1" queries="$2" kit_dir="$3"
  [ -z "${workload}" ] || [ -z "${queries}" ] || edl_fail "Give only one of --workload and --queries"
  if [ -n "${queries}" ]; then
    [ -f "${queries}" ] || edl_fail "Queries file ${queries} does not exist"
    [ -s "${queries}" ] || edl_fail "Queries file ${queries} is empty"
    CH_QUERIES_FILE="${queries}"
    CH_NEEDS_HITS=false
    return 0
  fi
  workload="${workload:-clickbench}"
  ch_check_workload "${workload}"
  CH_QUERIES_FILE="${kit_dir}/workloads/${workload}/queries.sql"
  CH_NEEDS_HITS=true
}

# Prints one clickhouse-benchmark flag per line for the --settings value $1, a comma-separated list
# of key=value entries: max_threads=8 becomes --max_threads=8. Fails, naming the entry, on an entry
# that holds a line break, has no '=', has a key that is not letters, digits and '_', or has a key
# on CH_SETTINGS_DENYLIST.
ch_settings_flags() {
  local settings="$1" entry key rest
  [ -n "${settings}" ] || return 0
  rest="${settings},"
  while [ -n "${rest}" ]; do
    entry="${rest%%,*}"
    rest="${rest#*,}"
    [ -n "${entry}" ] || continue
    # The flags are printed one per line, so a line break would make a second flag that skipped
    # these checks.
    case "${entry}" in
      *$'\n'* | *$'\r'*) edl_fail "--settings entry '${entry}' holds a line break" ;;
    esac
    case "${entry}" in
      *=*) ;;
      *) edl_fail "--settings entry '${entry}' has no '='; give each setting as key=value" ;;
    esac
    key="${entry%%=*}"
    [[ "${key}" =~ ^[A-Za-z0-9_]+$ ]] ||
      edl_fail "--settings entry '${entry}' has a key that is not letters, digits and '_'"
    case " $(printf '%s' "${CH_SETTINGS_DENYLIST}" | tr '\n' ' ') " in
      *" ${key} "*) edl_fail "--settings entry '${entry}' sets an option that start sets itself or does not support" ;;
    esac
    printf -- '--%s\n' "${entry}"
  done
}

# Fails, naming the value, when --source ($1) holds a quote or a backslash: it goes into SQL.
ch_check_source() {
  case "$1" in
    *"'"* | *'\'*) edl_fail "--source '$1' holds a quote or a backslash" ;;
  esac
}

# Prints the table function prepare reads hits from: the pinned ClickBench URL read anonymously
# when --source ($1) is empty, or the given URL read with the cluster's IAM role.
ch_source_expression() {
  if [ -z "$1" ]; then
    printf "s3('%s', NOSIGN, 'Parquet')\n" "${CH_CLICKBENCH_SOURCE_URL}"
  else
    printf "s3('%s', 'Parquet')\n" "$1"
  fi
}

# Fails, naming the value, unless --storage-policy ($1) is letters, digits and '_': it goes into SQL.
ch_check_storage_policy() {
  [[ "$1" =~ ^[A-Za-z0-9_]+$ ]] || edl_fail "--storage-policy '$1' is not letters, digits and '_'"
}
