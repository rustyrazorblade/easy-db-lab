# Lab Plan: ClickHouse + Presto Full Validation

## Goal

Provision a 3-db / 1-app cluster, install ClickHouse and Presto, and run a comprehensive
validation: scaffold verification, start/stop lifecycle, Presto querying ClickHouse, metrics
in VictoriaMetrics, backup/restore to S3 (verified via Presto), and full uninstall/reinstall
of both kits.

## Environment

- 3 DB nodes: `i4i.xlarge` (local NVMe — ClickHouse pods run here via node affinity)
- 1 App node: `i4i.xlarge` (Presto coordinator + worker run here)
- ClickHouse version: latest (default)
- Storage per ClickHouse node: `100Gi`

## Steps

### 1. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/clickhouse-full-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init --db 3 --app 1 --instance i4i.xlarge --stress-instance i4i.xlarge --up
```

### 2. Configure kubectl access

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
```

### 3. Wait for K3s nodes to be Ready

```bash
kubectl wait --for=condition=Ready nodes --all --timeout=120s
kubectl get nodes
```

All 4 nodes (3 db + 1 app) should appear.

### 4. Install the ClickHouse kit

```bash
$EDB kit install clickhouse --size 100Gi
```

Scaffolds template files into `$CLUSTER_DIR/clickhouse/` and installs the Altinity
ClickHouse operator into the K8s cluster via Helm.

### 5. Verify ClickHouse install generated expected files

```bash
for f in README.md clickhouseinstallation.yaml nodeport-service.yaml clickhouse-keeper.yaml \
          dashboards/clickhouse.json dashboards/clickhouse-logs.json; do
    [ -f "$CLUSTER_DIR/clickhouse/$f" ] || { echo "ERROR: missing $CLUSTER_DIR/clickhouse/$f"; exit 1; }
done
echo "ClickHouse install files verified ✓"
```

### 6. Install the Presto kit

```bash
$EDB kit install presto
```

Scaffolds presto files into `$CLUSTER_DIR/presto/`, including `catalogs/clickhouse.properties`
configured to connect via the ClickHouse K8s service DNS.

### 7. Verify Presto install generated expected files

```bash
for f in values.yaml catalogs/clickhouse.properties; do
    [ -f "$CLUSTER_DIR/presto/$f" ] || { echo "ERROR: missing $CLUSTER_DIR/presto/$f"; exit 1; }
done
echo "Presto install files verified ✓"
```

### 8. Start ClickHouse

```bash
$EDB clickhouse start
```

Creates local PersistentVolumes on each db node, applies the ClickHouseKeeperInstallation
manifest and waits for Keeper pods to be Ready, then applies the ClickHouseInstallation and
waits for all ClickHouse pods, then applies the NodePort service.

### 9. Verify ClickHouse pods are Ready

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
kubectl get pods -n default -l clickhouse.altinity.com/chi=clickhouse
kubectl get pods -n default -l clickhouse-keeper.altinity.com/chk=clickhouse-keeper
```

All replicas and all Keeper pods should show `Running` / `Ready`.

### 10. Verify NodePort service exists

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
kubectl get service clickhouse-nodeport -n default
```

Ports 30123 (HTTP), 30900 (native), and 30936 (metrics) should be listed.

### 11. Verify ClickHouse HTTP endpoint is reachable

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
DB0_IP=$($EDB ip db0 --private)
CH_URL="http://${DB0_IP}:30123"
HTTP_RESPONSE=$(curl -sf --retry 10 --retry-delay 5 --retry-all-errors "${CH_URL}/ping" || true)
[ "$HTTP_RESPONSE" = "Ok." ] || { echo "ERROR: /ping returned: '${HTTP_RESPONSE}'"; exit 1; }
echo "ClickHouse HTTP endpoint reachable ✓"
```

### 12. Start Presto

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB presto start
```

Installs Presto via Helm, adds the ClickHouse catalog (from `catalogs/clickhouse.properties`,
which is picked up because ClickHouse is in `RUNNING_KITS`), patches the coordinator and
worker deployments with Pyroscope profiling, and waits for both rollouts to complete.

### 13. Verify Presto status

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB presto status
```

Confirm coordinator and worker pods are shown as running with the Presto UI endpoint.

### 14. Verify ClickHouse metrics in VictoriaMetrics

Poll until at least 10 metric series appear (up to 3 minutes):

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
CONTROL_IP=$($EDB ip control0 --private)
DEADLINE=$((SECONDS + 180))
until [ $SECONDS -ge $DEADLINE ]; do
    METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
        --data-urlencode 'match[]={job="clickhouse"}' 2>/dev/null | jq '.data | length' 2>/dev/null || echo 0)
    echo "$(date +%H:%M:%S) metric series: ${METRIC_COUNT}"
    [ "${METRIC_COUNT:-0}" -ge 10 ] && break
    sleep 15
done
[ "${METRIC_COUNT:-0}" -ge 10 ] || { echo "ERROR: expected ≥10 metric series, got ${METRIC_COUNT:-0}"; exit 1; }
echo "VictoriaMetrics has ${METRIC_COUNT} ClickHouse metric series ✓"
```

### 15. Write test data to ClickHouse

Creates the table `ON CLUSTER` with `ReplicatedMergeTree` so it exists on all 3 replicas —
queries via the headless service will succeed regardless of which replica they land on.

```bash
EDB="$CLUSTER_DIR/easy-db-lab"

$EDB clickhouse sql "CREATE TABLE IF NOT EXISTS default.lab_test ON CLUSTER clickhouse (id UInt32, label String) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/lab_test', '{replica}') ORDER BY id"
$EDB clickhouse sql "INSERT INTO default.lab_test VALUES (1, 'hello')"

COUNT=$($EDB clickhouse sql "SELECT count() FROM default.lab_test" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
[ "$COUNT" = "1" ] || { echo "ERROR: expected 1 row, got $COUNT"; exit 1; }
echo "Test data written to ClickHouse ✓"
```

### 16. Query ClickHouse through Presto

The Presto JDBC connection has no default catalog, so table names must be fully qualified
as `<catalog>.<schema>.<table>`. The `clickhouse` catalog connects via the ClickHouse K8s
service DNS.

```bash
EDB="$CLUSTER_DIR/easy-db-lab"

COUNT=$($EDB presto sql "SELECT count(*) FROM clickhouse.default.lab_test" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
[ "$COUNT" = "1" ] || { echo "ERROR: Presto returned '$COUNT' rows, expected 1"; exit 1; }
echo "Presto → ClickHouse query verified ✓"
```

### 17. Stop ClickHouse (Presto keeps running)

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB clickhouse stop
```

Deletes the ClickHouseInstallation and NodePort service. The ClickHouseKeeperInstallation
is not deleted — Keeper persists so it is immediately available on the next start.
PVs are **not** deleted. Presto remains running.

**Note:** The Altinity operator deletes ClickHouse data when the CHI is deleted. Data written
before this stop will not survive the restart. This is a known issue (stop should scale down
pods rather than delete the CHI).

### 18. Verify pod and NodePort service are removed

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
kubectl get pods -n default -l clickhouse.altinity.com/chi=clickhouse
kubectl get service clickhouse-nodeport -n default 2>&1 || echo "(service gone as expected)"
```

### 19. Verify PVs survive stop

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
PV_COUNT=$(kubectl get pv --no-headers 2>/dev/null | grep -c "clickhouse" || true)
[ "${PV_COUNT:-0}" -ge 1 ] || { echo "ERROR: expected PVs to persist after stop, none found"; exit 1; }
echo "PVs still present after stop: ${PV_COUNT} ✓"
```

### 20. Restart ClickHouse — verify it starts cleanly and Presto can reconnect

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB clickhouse start

# Verify ClickHouse is healthy
VERSION=$($EDB clickhouse sql "SELECT version()" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
echo "ClickHouse version after restart: $VERSION ✓"

# Write fresh test data (data does not survive stop/start — see step 17 note)
$EDB clickhouse sql "CREATE TABLE IF NOT EXISTS default.lab_test ON CLUSTER clickhouse (id UInt32, label String) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/lab_test', '{replica}') ORDER BY id"
$EDB clickhouse sql "INSERT INTO default.lab_test VALUES (1, 'hello')"

# Verify Presto can reach ClickHouse again
COUNT=$($EDB presto sql "SELECT count(*) FROM clickhouse.default.lab_test" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
[ "$COUNT" = "1" ] || { echo "ERROR: Presto post-restart returned '$COUNT'"; exit 1; }
echo "Presto → ClickHouse query works after ClickHouse restart ✓"
```

### 21. Take a backup to S3

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB clickhouse backup --name lab-validation
```

### 22. Drop the table to simulate data loss

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB clickhouse sql "DROP TABLE IF EXISTS default.lab_test ON CLUSTER clickhouse"
echo "Table dropped ✓"
```

### 23. Restore from backup

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB clickhouse restore --name lab-validation
```

### 24. Verify data restored — query via Presto

```bash
EDB="$CLUSTER_DIR/easy-db-lab"

COUNT=$($EDB presto sql "SELECT count(*) FROM clickhouse.default.lab_test" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
[ "$COUNT" = "1" ] || { echo "ERROR: expected 1 row after restore, got '$COUNT' via Presto"; exit 1; }
echo "Backup/restore verified via Presto query ✓"
```

### 25. Stop both workloads

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB presto stop
$EDB clickhouse stop
```

### 26. Uninstall ClickHouse

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB clickhouse uninstall
```

Deletes the ClickHouseKeeperInstallation, removes PVs (`platform-pvs-delete`), and uninstalls
the Altinity operator Helm chart. The local `$CLUSTER_DIR/clickhouse/` directory is removed.

### 27. Uninstall Presto

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB presto uninstall
```

### 28. Verify uninstall cleaned up completely

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
[ ! -d "$CLUSTER_DIR/clickhouse" ] || { echo "ERROR: clickhouse/ dir still exists"; exit 1; }
[ ! -d "$CLUSTER_DIR/presto" ] || { echo "ERROR: presto/ dir still exists"; exit 1; }
PV_COUNT=$(kubectl get pv --no-headers 2>/dev/null | grep -c "clickhouse" || true)
[ "${PV_COUNT:-0}" -eq 0 ] || { echo "ERROR: ${PV_COUNT} PVs remain after uninstall"; exit 1; }
echo "Uninstall fully cleaned up ✓"
```

### 29. Reinstall both kits and verify a clean start

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB kit install clickhouse --size 100Gi
$EDB kit install presto
$EDB clickhouse start
$EDB presto start

VERSION=$($EDB clickhouse sql "SELECT version()" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
echo "ClickHouse version after reinstall: $VERSION ✓"

COUNT=$($EDB presto sql "SELECT count(*) FROM clickhouse.information_schema.tables" \
    | awk 'NR==3{gsub(/[[:space:]|]/,""); print}')
[ "${COUNT:-0}" -gt 0 ] || { echo "ERROR: Presto → ClickHouse returned no tables, expected > 0"; exit 1; }
echo "Fresh reinstall of both kits starts cleanly ✓"
```

### 30. Stop and uninstall after reinstall test

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB presto stop
$EDB clickhouse stop
$EDB clickhouse uninstall
$EDB presto uninstall
```

### 31. Tear down the cluster

```bash
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB down --auto-approve
```

## Notes

- ClickHouse pods run on db nodes via `nodeAffinity` — always provision with `--db N`.
- Presto runs on app nodes — always provision with `--app N` alongside ClickHouse.
- PVs are created on `clickhouse start` (via `platform-pvs`) and destroyed on `clickhouse uninstall` (via `platform-pvs-delete`). They survive `stop`.
- ClickHouse Keeper (`ClickHouseKeeperInstallation`) is applied on `start` and deleted on `uninstall`. Keeper persists across stop/start cycles — it is not deleted by `clickhouse stop`. The operator resolves Keeper endpoints automatically, enabling `ReplicatedMergeTree` tables and `ON CLUSTER` DDL across all replicas.
- **Data does not persist across stop/start.** `clickhouse stop` deletes the CHI, which causes the Altinity operator to wipe the data directory. Test steps re-create test data after each restart.
- ClickHouse queries use `$EDB clickhouse sql` (JDBC via NodePort 30123). Output is a formatted table; `awk 'NR==3{gsub(/[[:space:]|]/,""); print}'` extracts the scalar value from the first data row.
- Presto queries use `$EDB presto sql` (JDBC via port 8080). The JDBC connection has no default catalog, so table names must be fully qualified: `clickhouse.default.lab_test`.
- Tables are created with `ReplicatedMergeTree ON CLUSTER clickhouse` so all 3 replicas have the table. Queries routed by the headless service to any replica succeed.
- Backup/restore targets the `s3_backup` disk in the CHI manifest, pointing to the cluster S3 bucket under `clickhouse-backups/`.
- `$EDB` must be re-assigned in each step (`EDB="$CLUSTER_DIR/easy-db-lab"`) since steps run in separate shell sessions.
