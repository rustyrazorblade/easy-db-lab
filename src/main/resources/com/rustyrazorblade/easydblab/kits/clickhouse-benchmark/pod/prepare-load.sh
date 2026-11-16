#!/bin/sh
#
# Runs in the prepare pod (POSIX sh): drops hits on --recreate, creates it on every replica, loads it
# on the server from S3, waits for every replica to catch up, and checks that they all hold the same
# number of rows. The DDL comes from create-hits.sql next to it (/edl, where the prepare ConfigMap is
# mounted).
#
# Usage: sh prepare-load.sh <host> <port> <user> <database> <storage-policy> <recreate> <source>
#   <user>    may be empty; <recreate> is true or false; <source> is the table function to read,
#             for example s3('<url>', NOSIGN, 'Parquet').
#
# A failed statement prints the server's error and exits non-zero. Nothing here ever drops hits
# except the --recreate drop, which runs before the table is created.

set -u

# The target's cluster name. It is literal in the clickhouse kit's ClickHouseInstallation, and
# system.clusters also lists the operator's own clusters, so it is not read from the server.
CH_CLUSTER=clickhouse

host="$1"
port="$2"
user="$3"
database="$4"
policy="$5"
recreate="$6"
source="$7"

# clickhouse-client with no client-side limit: the load and the ON CLUSTER statements can run for
# a long time. A negative distributed_ddl_task_timeout waits for ON CLUSTER statements without limit.
client() {
  if [ -n "${user}" ]; then
    set -- --user "${user}" "$@"
  fi
  clickhouse-client --host "${host}" --port "${port}" --database "${database}" \
    --receive_timeout=86400 --send_timeout=86400 --max_execution_time=0 \
    --distributed_ddl_task_timeout=-1 "$@"
}

if [ "${recreate}" = true ]; then
  echo "Dropping hits on every replica of ${CH_CLUSTER}..."
  client --query "DROP TABLE IF EXISTS hits ON CLUSTER ${CH_CLUSTER} SYNC" || {
    echo "Could not drop hits." >&2
    exit 1
  }
fi

echo "Creating hits on every replica of ${CH_CLUSTER} with storage policy ${policy}..."
{
  sed "s/^CREATE TABLE hits\$/CREATE TABLE hits ON CLUSTER ${CH_CLUSTER}/" "$(dirname "$0")/create-hits.sql"
  echo "SETTINGS storage_policy = '${policy}'"
} | client || {
  echo "Could not create hits." >&2
  exit 1
}

# A --source is read with the server's own credentials, the cluster's IAM role. Since 26.x the
# server refuses that in a user query unless s3_allow_server_credentials_in_user_queries is on;
# older servers lack the setting and reject an unknown one, so it is set only where it exists. The
# anonymous (NOSIGN) default uses no credentials and never needs it. A --source cannot hold a
# quote, so only the default holds ', NOSIGN, '.
set --
case "${source}" in
  *"', NOSIGN, '"*) ;;
  *)
    present="$(client --format TSV --query "SELECT count() FROM system.settings WHERE name = 's3_allow_server_credentials_in_user_queries'")" || {
      echo "Could not read the server's settings." >&2
      exit 1
    }
    [ "${present}" = 0 ] || set -- --s3_allow_server_credentials_in_user_queries=1
    ;;
esac

echo "Loading hits from ${source}..."
client "$@" --query "INSERT INTO hits SELECT * FROM ${source}" || {
  echo "The load failed. hits may be partly loaded; run prepare --recreate to reload it." >&2
  exit 1
}

echo "Waiting for every replica to hold the loaded data..."
client --query "SYSTEM SYNC REPLICA ON CLUSTER ${CH_CLUSTER} hits" || {
  echo "The replicas did not finish syncing hits." >&2
  exit 1
}

counts="$(client --format TSV --query "SELECT hostName() AS replica, count() FROM clusterAllReplicas('${CH_CLUSTER}', currentDatabase(), 'hits') GROUP BY replica ORDER BY replica")" || {
  echo "Could not count the rows of hits on each replica." >&2
  exit 1
}

if [ "$(printf '%s\n' "${counts}" | awk -F '\t' '{ print $2 }' | sort -u | wc -l)" -ne 1 ]; then
  echo "The replicas hold different row counts for hits:" >&2
  printf '%s\n' "${counts}" | awk -F '\t' '{ printf "  %s: %s rows\n", $1, $2 }' >&2
  exit 1
fi

rows="$(printf '%s\n' "${counts}" | awk -F '\t' 'NR == 1 { print $2 }')"
replicas="$(printf '%s\n' "${counts}" | wc -l | tr -d ' ')"
echo "hits holds ${rows} rows on every replica (${replicas} replicas)."
