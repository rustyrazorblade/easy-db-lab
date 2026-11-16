#!/bin/sh
#
# Runs in the benchmark pod (POSIX sh): runs clickhouse-benchmark with the arguments it is given on
# the queries in queries.sql next to it (/edl, where the run's ConfigMap is mounted), and exits with
# the tool's exit code, which `start` reads from the pod status.
#
# The tool reads its queries from stdin, one per line, as TSV, which turns \\ into \. Each backslash
# is doubled first, so the server receives every query exactly as it is written in the file.

sed 's/\\/\\\\/g' "$(dirname "$0")/queries.sql" | clickhouse-benchmark "$@"
code=$?
echo "clickhouse-benchmark exited with code ${code}"
exit "${code}"
