# Lab Plan: postgres kit validation

## Objective

Validate the new `postgres` kit end-to-end: deploy via CNPG, execute SQL via `postgres sql`,
verify metrics are flowing to VictoriaMetrics, export the metrics catalog, verify Presto sees
the `postgres` catalog and can query it, and confirm that `stop` preserves data across a restart.

## Environment

1 db node (i4i.xlarge) + 1 app node (i4i.xlarge). PostgreSQL v17, single instance. Presto on the app node.

## Steps

### 1. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/postgres-test-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init --db 1 --app 1 --instance i4i.xlarge --stress-instance i4i.xlarge --up
```

### 2. Configure kubectl access

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
```

### 3. Install kits

```bash
$EDB kit install postgres --version 17 --instances 1 --size 100Gi
$EDB kit install presto
```

### 4. Start PostgreSQL

```bash
$EDB postgres start
```

### 5. Verify SQL works

```bash
$EDB postgres sql "SELECT version()"
```

### 6. Create test data

```bash
$EDB postgres sql "CREATE TABLE test (id serial PRIMARY KEY, msg text)"
$EDB postgres sql "INSERT INTO test (msg) VALUES ('hello'), ('world')"
$EDB postgres sql "SELECT count(*) FROM test"
```

Expected: returns `2`.

### 7. Verify PostgreSQL metrics in VictoriaMetrics

Wait for metrics to flow, then assert ≥10 series are present:

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
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

### 8. Export metrics catalog

Run from the cluster workspace directory so `env.sh` is found:

```bash
(cd "$CLUSTER_DIR" && bin/export-workload-metrics postgres)
```

Then copy the output into the source tree and commit:

```bash
cp "$CLUSTER_DIR/postgres/metrics-catalog.json" \
   src/main/resources/com/rustyrazorblade/easydblab/kits/postgres/metrics-catalog.json
```

### 9. Start Presto (triggers postgres catalog injection)

```bash
$EDB presto start
```

### 10. Verify Presto sees postgres catalog

```bash
$EDB presto sql "SHOW CATALOGS"
$EDB presto sql "SHOW TABLES FROM postgres.public"
$EDB presto sql "SELECT * FROM postgres.public.test"
```

### 11. Test stop/start data persistence

```bash
$EDB postgres stop
$EDB postgres start
$EDB postgres sql "SELECT count(*) FROM test"
```

Expected: returns `2`.

### 12. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- CNPG pods may take 2–3 minutes to reach Ready after `postgres start`. The kit waits automatically.
- The `presto start` hook calls `update-catalogs.sh` which injects the postgres catalog — verify with `SHOW CATALOGS` before querying.
- If Presto can't reach postgres, check that `postgres-rw.default.svc.cluster.local` resolves inside the cluster (CNPG creates this service automatically).
- After step 8, author `METRICS.md` and `dashboards/postgres.json` from the exported catalog before committing.
- AWS profile: `sandbox-admin`
