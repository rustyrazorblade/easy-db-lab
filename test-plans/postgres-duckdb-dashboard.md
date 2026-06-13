# Lab Plan: PostgreSQL + DuckDB Dashboard

## Objective

Install PostgreSQL with the DuckDB extension, load data via sysbench, run analytical queries,
collect the metrics catalog, and author `dashboards/duckdb.json` for the postgres kit.

## Cluster Configuration

| Node type | Count | Role |
|-----------|-------|------|
| control   | 1     | K3s control plane, observability stack |
| db        | 1     | PostgreSQL (CNPG) |
| app       | 1     | sysbench workload |

## Prerequisites

- AWS credentials configured with `sandbox-admin` profile
- Tailscale running on the developer machine
- Project built: `./gradlew installDist`

---

## Steps

### 1. Fix postgres endpoint type (code change)

The postgres kit must expose a `postgresql` wire-protocol endpoint so sysbench can inject
`TARGET_PG_*` env vars. Update `kit.yaml` in the source tree before building:

Change the PostgreSQL endpoint in
`src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/kit.yaml` from:

```yaml
  - name: "PostgreSQL"
    node-type: db
    port: 30432
    type: native
```

to:

```yaml
  - name: "PostgreSQL"
    node-type: db
    port: 30432
    type: postgresql
    database: postgres
```

Then rebuild:

```bash
./gradlew installDist
```

### 2. Set up cluster workspace

```bash
CLUSTER_DIR="clusters/postgres-duckdb-dashboard-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
```

### 3. Initialize and bring up cluster

```bash
$EDB init postgres-duckdb -c 1 -s 1 -i i4i.xlarge
$EDB up
```

Wait for all nodes to be provisioned and K3s running (~5 min).

### 4. Install and start PostgreSQL with DuckDB

```bash
$EDB kit install postgres --version 17 --instances 1 --size 100Gi --extension duckdb
$EDB postgres-duckdb start
```

The duckdb image will be pulled on first use — expect 3–5 min for pod Ready.

### 5. Verify DuckDB is installed

```bash
$EDB postgres-duckdb sql "SELECT extname, extversion FROM pg_extension WHERE extname = 'pg_duckdb';"
$EDB postgres-duckdb sql "SELECT duckdb_version();"
```

Both queries must return results. If `pg_duckdb` is not in `pg_extension`, stop and investigate
pod events: `kubectl describe pod -l cnpg.io/cluster=postgres -n default`.

### 6. Install and run sysbench to load data

```bash
$EDB kit install sysbench --target postgres-duckdb --scale 100 --tables 10 --threads 8 --duration 120
$EDB sysbench-postgres-duckdb start
```

This prepares 10 tables × 100k rows then runs `oltp_read_write` for 120s. Wait for the
sysbench pod to complete (~5 min including prepare).

### 7. Run analytical queries through DuckDB

Demonstrate DuckDB executing analytical queries over the sysbench data:

```bash
$EDB postgres-duckdb sql "SELECT COUNT(*), AVG(k), MIN(k), MAX(k) FROM sbtest1;"
$EDB postgres-duckdb sql "SELECT c, COUNT(*) AS n FROM sbtest1 GROUP BY c ORDER BY n DESC LIMIT 10;"
$EDB postgres-duckdb sql "
SELECT
  duckdb_version() AS duckdb_version,
  (SELECT COUNT(*) FROM sbtest1) AS row_count;
"
```

All queries should return results and execute without error.

### 8. Wait for metrics to stabilize

```bash
CONTROL_IP=$($EDB ip control0 --private)
DEADLINE=$((SECONDS + 180))
until [ $SECONDS -ge $DEADLINE ]; do
    METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
        --data-urlencode 'match[]={job="postgres"}' 2>/dev/null \
        | jq '.data | length' 2>/dev/null || echo 0)
    echo "$(date +%H:%M:%S)  postgres metric series: ${METRIC_COUNT}"
    [ "${METRIC_COUNT:-0}" -ge 10 ] && break
    sleep 15
done
[ "${METRIC_COUNT:-0}" -ge 10 ] || { echo "ERROR: expected ≥10 series, got ${METRIC_COUNT:-0}"; exit 1; }
echo "VictoriaMetrics has ${METRIC_COUNT} postgres metric series ✓"
```

### 9. Export metrics catalog

```bash
(cd "$CLUSTER_DIR" && bin/export-workload-metrics postgres)
cp "$CLUSTER_DIR/postgres/metrics-catalog.json" \
   src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/metrics-catalog-duckdb.json
```

### 10. PAUSE — author DuckDB dashboard

**Stop here.** Claude will read `metrics-catalog-duckdb.json`, compare it against the base
`metrics-catalog.json`, identify any duckdb-specific or analytically-relevant metrics, and
author `src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/dashboards/duckdb.json`.

Review and approve the dashboard in Grafana before proceeding.

### 11. Stop and uninstall

```bash
$EDB sysbench-postgres-duckdb stop
$EDB postgres-duckdb stop
$EDB postgres-duckdb uninstall
```

### 12. Tear down cluster

```bash
$EDB down --auto-approve
```

---

## Notes

- `kit install postgres --extension duckdb` creates the `postgres-duckdb/` directory and bakes
  the pg_duckdb image into `postgres-cluster.yaml` at install time.
- The sysbench kit uses `TARGET_PG_*` vars from the postgres kit's `postgresql` endpoint.
  Step 1 (endpoint fix) is required for sysbench to connect.
- Metrics are scraped from the CNPG exporter (NodePort 30987) with `job="postgres"` regardless
  of extension. The duckdb catalog may have the same series as the base catalog; the dashboard
  focuses on analytically-relevant panels (seq scans, temp files, buffer hit ratio).
- AWS profile: `sandbox-admin`
