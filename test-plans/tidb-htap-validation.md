# Lab Plan: TiDB HTAP Observability Validation

## Objective

Validate end-to-end observability for the TiDB HTAP kit on a live AWS cluster: confirm all four component metrics scrape jobs (tidb-sql, tikv, pd, tiflash) are flowing into VictoriaMetrics, OTLP traces are landing in Tempo, and container logs are visible in VictoriaLogs. Run SQL queries via the MySQL endpoint — including a TiFlash columnar query — to confirm the cluster is functional. Export the metrics catalog and use it to create Grafana dashboards for TiDB.

## Cluster Name

tidb-validation

## Datacenters

single

## Environment

- 1 db node (`i4i.xlarge`) — TiKV, TiFlash
- 1 app node (`i4i.xlarge`) — TiDB SQL, PD
- AWS profile: `sandbox-admin`
- Local NVMe storage, no EBS required

## Steps

### 1. Provision the cluster

```bash
AWS_PROFILE=sandbox-admin $EDB init tidb-validation --db 1 --app 1 --instance i4i.xlarge --up
```

### 2. Install the TiDB kit

```bash
$EDB kit install tidb
```

### 3. Start TiDB

Starts the TiDB Operator, applies the TidbCluster CR, waits for all components (PD, TiKV, TiDB, TiFlash) to reach Ready state, applies NodePort services, and registers 4 metrics scrape jobs.

```bash
$EDB tidb start
```

### 4. Confirm kit status

All 4 component pods (pd-0, tikv-0, tidb-0, tiflash-0) should appear as Ready.

```bash
$EDB tidb status
```

### 5. Verify metrics are flowing — all 4 jobs

SSH to the control node and query VictoriaMetrics directly to confirm each scrape job is active and returning data. Replace `<CONTROL_PRIVATE_IP>` with the value from `$EDB ip --private control0`.

```bash
# Check all 4 scrape targets are up
curl -s "http://<CONTROL_PRIVATE_IP>:8428/api/v1/query?query=up{job=~'tidb-sql|tikv|pd|tiflash'}" | jq '.data.result[] | {job: .metric.job, value: .value[1]}'
```

Expected: 4 results, each with `value: "1"`.

### 6. Verify traces are flowing into Tempo

Check Tempo has received spans from the TiDB SQL layer (OTLP pushed via the ClusterIP service).

```bash
curl -s "http://<CONTROL_PRIVATE_IP>:3200/api/search?tags=service.name%3DTiDB" | jq '.traces | length'
```

Expected: a non-zero trace count.

Note: TiDB reports itself as `TiDB` (capital T). Tempo tag search is case-sensitive — `tidb` returns zero results.

### 7. Verify logs are flowing into VictoriaLogs

```bash
curl -s "http://<CONTROL_PRIVATE_IP>:9428/select/logsql/query?query=k8s.container.name:%22tidb%22&limit=5" | jq '.hits | length'
```

Expected: log entries from the `tidb` container.

### 8. Run SQL validation queries

Connect via the MySQL endpoint on any app node and run a basic query, then a TiFlash columnar query.

```bash
$EDB tidb sql "SELECT tidb_version()"
```

```bash
# Create a test table and insert rows
$EDB tidb sql "CREATE TABLE IF NOT EXISTS test.t (id INT PRIMARY KEY, val VARCHAR(64))"
$EDB tidb sql "INSERT INTO test.t VALUES (1, 'htap'), (2, 'tiflash'), (3, 'tikv')"
```

```bash
# Alter table to use TiFlash replica
$EDB tidb sql "ALTER TABLE test.t SET TIFLASH REPLICA 1"
```

Wait ~30 seconds for TiFlash replication, then run a columnar read:

```bash
$EDB tidb sql "SELECT /*+ read_from_storage(tiflash[t]) */ count(*), val FROM test.t GROUP BY val"
```

Expected: query succeeds and returns rows routed through TiFlash.

### 9. Export the metrics catalog

Dump all metric names currently stored in VictoriaMetrics to a catalog file for dashboard authoring.

```bash
curl -s "http://<CONTROL_PRIVATE_IP>:8428/api/v1/label/__name__/values" \
  | jq -r '.data[]' \
  | grep -E '^(tidb|tikv|pd|tiflash)' \
  | sort > tidb-metrics-catalog.txt

wc -l tidb-metrics-catalog.txt
```

### 10. Create Grafana dashboards

Upload the TiDB dashboards to the running Grafana instance. Run this step after dashboards have been authored using the metrics catalog from step 9.

```bash
$EDB grafana install --folder TiDB dashboards/tidb-overview.json
$EDB grafana install --folder TiDB dashboards/tikv-overview.json
$EDB grafana install --folder TiDB dashboards/pd-overview.json
$EDB grafana install --folder TiDB dashboards/tiflash-overview.json
```

Open Grafana at `http://<CONTROL_PRIVATE_IP>:3000` (default credentials: admin/admin) and confirm all panels load with data.

### 11. Tear down

```bash
$EDB tidb stop
$EDB tidb uninstall
AWS_PROFILE=sandbox-admin $EDB down --auto-approve
```

## Notes

- TiFlash replication (step 8) takes ~30–60 seconds after `SET TIFLASH REPLICA 1`. Run `$EDB tidb sql "SELECT * FROM information_schema.tiflash_replica WHERE TABLE_SCHEMA='test'"` to check `AVAILABLE=1` before querying.
- Dashboard JSON files (step 10) must be authored using metric names from `tidb-metrics-catalog.txt`. The `job` label values are `tidb-sql`, `tikv`, `pd`, and `tiflash` — use these in Prometheus queries to scope per-component panels.
- The OTel ClusterIP service (`otel-collector.default.svc.cluster.local:4317`) is deployed by the observability stack. TiDB pushes traces to it via the `opentelemetry.endpoint` config in the TidbCluster manifest.
- On a 1-db-node cluster, TiFlash has 1 replica and TiKV has 1 replica — sufficient for validation but not a HA configuration.
