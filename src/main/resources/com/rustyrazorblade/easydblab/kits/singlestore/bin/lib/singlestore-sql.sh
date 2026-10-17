#!/usr/bin/env bash
#
# SQL for the SingleStore kit's start step, run in the master aggregator's node container. The
# builders are pure shell functions, so src/test/shell/singlestore-sql.test.sh can test both
# --unlimited-storage settings with kubectl stubbed.
#
# The root password reaches mysql through MYSQL_PWD, read from stdin inside the pod, so it is on
# no command line on this machine or in the pod. A kit shell step runs under /bin/bash, which is
# bash 3.2 on macOS.

ss_sql_fail() {
  echo "Error: $*" >&2
  return 1
}

# Prints $1 escaped for a single-quoted SQL string literal.
ss_sql_string() {
  printf '%s\n' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/\\\\'/g"
}

# Prints $1 escaped for a double-quoted SQL string literal.
ss_sql_double_quoted() {
  printf '%s\n' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# Fails, naming --blob-cache, when --unlimited-storage is on without a whole number of MB for
# the blob cache. start runs this before it creates anything.
ss_check_unlimited_storage_args() {
  [ "${SINGLESTORE_UNLIMITED_STORAGE:-false}" = "true" ] || return 0
  case "${SINGLESTORE_BLOB_CACHE:-}" in
    "" | *[!0-9]*)
      ss_sql_fail "--unlimited-storage needs --blob-cache, the blob cache size in MB (a whole number). Re-run 'kit install singlestore --force' with it."
      ;;
  esac
}

# Prints the S3 path, under the data bucket, of a database created with unlimited storage at
# start time $3 (UTC, compact ISO 8601). Each start uses a new path; nothing deletes the old ones.
ss_s3_path() {
  echo "$1/singlestore/$2-$3"
}

# Prints the SQL that creates database $1: CREATE DATABASE IF NOT EXISTS, or, with unlimited
# storage ($2 = true), the blob cache setting ($3 MB) and CREATE DATABASE ON S3 at path $4 in
# region $5, with credentials from the node IAM role (CREDENTIALS '{}').
ss_database_sql() {
  local database="$1" unlimited="$2" blob_cache="$3" path="$4" region="$5"
  if [ "${unlimited}" = "true" ]; then
    printf '%s\n' \
      "SET GLOBAL maximum_blob_cache_size_mb = ${blob_cache};" \
      "CREATE DATABASE \`${database}\` ON S3 \"$(ss_sql_double_quoted "${path}")\" CONFIG '{\"region\":\"$(ss_sql_string "${region}")\"}' CREDENTIALS '{}';"
  else
    printf '%s\n' "CREATE DATABASE IF NOT EXISTS \`${database}\`;"
  fi
}

# Prints the name of the master aggregator pod.
ss_master_pod() {
  kubectl get pod -l "easydblab/kit=singlestore,easydblab/role=master" -n default \
    -o jsonpath='{.items[0].metadata.name}'
}

# Runs the SQL on stdin as root in the master pod and prints the rows, tab separated, with no
# header. The password is the first line mysql's shell reads; SingleStore's error goes to stderr.
ss_mysql() {
  local pod
  pod="$(ss_master_pod)"
  [ -n "${pod}" ] || ss_sql_fail "no SingleStore master aggregator pod is running" || return 1
  { printf '%s\n' "${SINGLESTORE_PASSWORD}"; cat; } |
    kubectl exec -i -n default -c node "${pod}" -- \
      sh -c 'IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u root -N -B'
}

# Creates the --database database. With --unlimited-storage it is created ON S3 at a new path,
# which is printed. A refusal fails the step with SingleStore's error, and no local database is
# created in its place.
singlestore_create_database() {
  local unlimited="${SINGLESTORE_UNLIMITED_STORAGE:-false}" path=""
  ss_check_unlimited_storage_args || return 1
  if [ "${unlimited}" = "true" ]; then
    path="$(ss_s3_path "${BUCKET_NAME}" "${SINGLESTORE_DATABASE}" "$(date -u +%Y%m%dT%H%M%SZ)")"
  fi
  if ! ss_database_sql "${SINGLESTORE_DATABASE}" "${unlimited}" "${SINGLESTORE_BLOB_CACHE:-}" "${path}" "${REGION}" | ss_mysql; then
    ss_sql_fail "SingleStore refused to create the database ${SINGLESTORE_DATABASE} (its error is above)."
    return 1
  fi
  if [ "${unlimited}" = "true" ]; then
    echo "SingleStore database ${SINGLESTORE_DATABASE} is stored ON S3 at s3://${path}"
  else
    echo "SingleStore database ${SINGLESTORE_DATABASE} is ready"
  fi
}
