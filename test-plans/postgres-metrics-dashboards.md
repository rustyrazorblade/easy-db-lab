# Lab Plan: postgres metrics and dashboard authoring

## Objective

Collect postgres metrics and author Grafana dashboards for the base postgres kit and each
extension (duckdb, postgis, timescaledb). Each run uses a clean database (fresh PVs via
uninstall between runs) to avoid data compatibility issues between images.

Order: base postgres → duckdb (highest priority) → postgis → timescaledb.

## Environment

1 db node (i4i.xlarge). PostgreSQL v17, single instance. No app node needed.

## Steps

### 1. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/postgres-dashboards-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init --db 1 --instance i4i.xlarge --up
```

---

## Run 1: Base postgres (no extensions)

### 2. Install and start

```bash
$EDB kit install postgres --version 17 --instances 1 --size 100Gi
$EDB postgres start
```

### 3. Verify metrics are flowing

```bash
CONTROL_IP=$($EDB ip control0 --private)
DEADLINE=$((SECONDS + 180))
until [ $SECONDS -ge $DEADLINE ]; do
    METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
        --data-urlencode 'match[]={job="postgres"}' 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    echo "$(date +%H:%M:%S) metric series: ${METRIC_COUNT}"
    [ "${METRIC_COUNT:-0}" -ge 10 ] && break
    sleep 15
done
[ "${METRIC_COUNT:-0}" -ge 10 ] || { echo "ERROR: expected ≥10 metric series, got ${METRIC_COUNT:-0}"; exit 1; }
echo "VictoriaMetrics has ${METRIC_COUNT} PostgreSQL metric series ✓"
```

### 4. Export metrics catalog

```bash
(cd "$CLUSTER_DIR" && bin/export-workload-metrics postgres)
cp "$CLUSTER_DIR/postgres/metrics-catalog.json" \
   src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/metrics-catalog.json
```

### 5. PAUSE — author base postgres dashboard

**Stop here.** Claude will author `src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/dashboards/postgres.json`
from the exported catalog. Review and approve the dashboard before continuing.

### 6. Stop and uninstall

```bash
$EDB postgres stop
$EDB postgres uninstall
```

---

## Run 2: DuckDB extension

### 7. Install and start with duckdb

```bash
$EDB kit install postgres --version 17 --instances 1 --size 100Gi
$EDB postgres start --extension duckdb
```

### 8. Verify metrics are flowing

```bash
CONTROL_IP=$($EDB ip control0 --private)
DEADLINE=$((SECONDS + 180))
until [ $SECONDS -ge $DEADLINE ]; do
    METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
        --data-urlencode 'match[]={job="postgres"}' 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    echo "$(date +%H:%M:%S) metric series: ${METRIC_COUNT}"
    [ "${METRIC_COUNT:-0}" -ge 10 ] && break
    sleep 15
done
[ "${METRIC_COUNT:-0}" -ge 10 ] || { echo "ERROR: expected ≥10 metric series, got ${METRIC_COUNT:-0}"; exit 1; }
echo "VictoriaMetrics has ${METRIC_COUNT} PostgreSQL metric series ✓"
```

### 9. Export metrics catalog

```bash
(cd "$CLUSTER_DIR" && bin/export-workload-metrics postgres)
cp "$CLUSTER_DIR/postgres/metrics-catalog.json" \
   src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/metrics-catalog-duckdb.json
```

### 10. PAUSE — author duckdb dashboard

**Stop here.** Claude will author `src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/dashboards/duckdb.json`
from the exported catalog. Review and approve the dashboard before continuing.

### 11. Stop and uninstall

```bash
$EDB postgres stop
$EDB postgres uninstall
```

---

## Run 3: PostGIS extension

### 12. Install and start with postgis

```bash
$EDB kit install postgres --version 17 --instances 1 --size 100Gi
$EDB postgres start --extension postgis
```

### 13. Verify metrics are flowing

```bash
CONTROL_IP=$($EDB ip control0 --private)
DEADLINE=$((SECONDS + 180))
until [ $SECONDS -ge $DEADLINE ]; do
    METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
        --data-urlencode 'match[]={job="postgres"}' 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    echo "$(date +%H:%M:%S) metric series: ${METRIC_COUNT}"
    [ "${METRIC_COUNT:-0}" -ge 10 ] && break
    sleep 15
done
[ "${METRIC_COUNT:-0}" -ge 10 ] || { echo "ERROR: expected ≥10 metric series, got ${METRIC_COUNT:-0}"; exit 1; }
echo "VictoriaMetrics has ${METRIC_COUNT} PostgreSQL metric series ✓"
```

### 14. Export metrics catalog

```bash
(cd "$CLUSTER_DIR" && bin/export-workload-metrics postgres)
cp "$CLUSTER_DIR/postgres/metrics-catalog.json" \
   src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/metrics-catalog-postgis.json
```

### 15. PAUSE — author postgis dashboard

**Stop here.** Claude will author `src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/dashboards/postgis.json`
from the exported catalog. Review and approve the dashboard before continuing.

### 16. Stop and uninstall

```bash
$EDB postgres stop
$EDB postgres uninstall
```

---

## Run 4: TimescaleDB extension

### 17. Install and start with timescaledb

```bash
$EDB kit install postgres --version 17 --instances 1 --size 100Gi
$EDB postgres start --extension timescaledb
```

### 18. Verify metrics are flowing

```bash
CONTROL_IP=$($EDB ip control0 --private)
DEADLINE=$((SECONDS + 180))
until [ $SECONDS -ge $DEADLINE ]; do
    METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
        --data-urlencode 'match[]={job="postgres"}' 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    echo "$(date +%H:%M:%S) metric series: ${METRIC_COUNT}"
    [ "${METRIC_COUNT:-0}" -ge 10 ] && break
    sleep 15
done
[ "${METRIC_COUNT:-0}" -ge 10 ] || { echo "ERROR: expected ≥10 metric series, got ${METRIC_COUNT:-0}"; exit 1; }
echo "VictoriaMetrics has ${METRIC_COUNT} PostgreSQL metric series ✓"
```

### 19. Export metrics catalog

```bash
(cd "$CLUSTER_DIR" && bin/export-workload-metrics postgres)
cp "$CLUSTER_DIR/postgres/metrics-catalog.json" \
   src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/metrics-catalog-timescaledb.json
```

### 20. PAUSE — author timescaledb dashboard

**Stop here.** Claude will author `src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/dashboards/timescaledb.json`
from the exported catalog. Review and approve the dashboard before continuing.

### 21. Stop and uninstall

```bash
$EDB postgres stop
$EDB postgres uninstall
```

---

## Tear down

### 22. Destroy cluster

```bash
$EDB down --auto-approve
```

## Notes

- CNPG pods may take 2–3 minutes to reach Ready after `postgres start`. The kit waits automatically.
- Extension images (duckdb, postgis, timescaledb) are pulled on first use — may add a few minutes to pod startup.
- Each uninstall removes PVs and the CNPG operator Helm release, giving the next run a clean slate.
- Dashboard authoring (pause steps) happens after catalog export. Claude reads the catalog and writes the dashboard JSON; user approves before proceeding.
- AWS profile: `sandbox-admin`
