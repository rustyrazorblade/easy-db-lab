# Lab Plan: Trino Metrics Discovery

## Goal

Spin up a Trino cluster with a Cassandra backend, run queries to generate execution metrics,
export the full series catalog, and copy it back to the kit source so we can build a Grafana
dashboard.

## Environment

- 3 DB nodes: `i4i.large` (2 vCPU / 16 GB / 468 GB local NVMe — runs Cassandra)
- 1 App node: `m5.xlarge` (4 vCPU / 16 GB — runs Trino coordinator + worker)

## Steps

### 1. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/trino-metrics-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init trino-metrics --db 3 --app 1 --instance i4i.large --stress-instance m5.xlarge --up
```

### 2. Start Cassandra

```bash
$EDB cassandra use 5.0
$EDB cassandra update-config cassandra.patch.yaml
$EDB cassandra start
```

Verify all nodes are up:

```bash
$EDB cassandra nt status
```

All 3 nodes must show `UN`. Retry after 30 seconds if any are down.

### 3. Populate data with stress

```bash
$EDB cassandra stress start -- KeyValue -d 10m --threads 100
```

Wait 30 seconds for the keyspace and table to be created before continuing.

### 4. Scaffold and start Trino

```bash
$EDB kit install trino --workers 1
$EDB trino start
```

Because Cassandra is already running, `update-catalogs.sh` will include the Cassandra catalog
automatically during install. Trino takes 2–3 minutes to become ready.

### 5. Verify Trino can see the Cassandra catalog

```bash
APP_IP=$($EDB ip --private app0)
$EDB exec run --type control -- bash -c "
  RESULT=\$(curl -sf -X POST http://${APP_IP}:8080/v1/statement \
    -H 'X-Trino-User: test' -H 'Content-Type: text/plain' \
    -d 'SHOW CATALOGS')
  while NEXT=\$(echo \"\$RESULT\" | jq -r '.nextUri // empty') && [ -n \"\$NEXT\" ]; do
    sleep 0.5
    RESULT=\$(curl -sf -H 'X-Trino-User: test' \"\$NEXT\")
  done
  echo \"\$RESULT\" | jq -r '.data[][]'
"
```

`cassandra` must appear in the output.

### 6. Run queries to generate execution metrics

```bash
APP_IP=$($EDB ip --private app0)
for i in $(seq 1 20); do
  $EDB exec run --type control -- bash -c "
    RESULT=\$(curl -sf -X POST http://${APP_IP}:8080/v1/statement \
      -H 'X-Trino-User: test' -H 'Content-Type: text/plain' \
      -d 'SELECT count(*) FROM cassandra.baselines.keyvalue')
    while NEXT=\$(echo \"\$RESULT\" | jq -r '.nextUri // empty') && [ -n \"\$NEXT\" ]; do
      sleep 0.5
      RESULT=\$(curl -sf -H 'X-Trino-User: test' \"\$NEXT\")
    done
    echo \"\$RESULT\" | jq -r '.data[][]'
  "
  echo "Query $i complete"
done
```

Running 20 queries ensures query execution, task, and memory metrics are populated.

### 7. Wait for metrics to flow into VictoriaMetrics

```bash
sleep 60
```

### 8. Export metrics catalog

Run from the cluster workspace directory:

```bash
cd "$CLUSTER_DIR"
../../bin/export-workload-metrics trino
```

### 9. Verify the catalog

```bash
cat "$CLUSTER_DIR/trino/metrics-catalog.json" | jq '.series | length'
```

Should be non-zero. If 0, wait another 60 seconds and retry.

Spot-check for key execution metrics:

```bash
cat "$CLUSTER_DIR/trino/metrics-catalog.json" \
  | jq -r '.series[].name' | sort -u | grep -E "query|task|memory|executor|jvm" | head -40
```

### 10. Copy catalog back to kit source

```bash
cp "$CLUSTER_DIR/trino/metrics-catalog.json" \
  src/main/resources/com/rustyrazorblade/easydblab/kits/trino/metrics-catalog.json
```

### 11. Tear down

```bash
$EDB down --auto-approve
```

## After This Plan

Once `metrics-catalog.json` is populated, build the Grafana dashboard:
- Use `jq -r '.series[].name' | sort -u` to identify available metric names
- Model after `dashboards/clickhouse.json` for layout
- Key panels: active queries, queued queries, memory pool usage, GC pause time, CPU
- Save to `dashboards/trino.json`
- Register in `GrafanaDashboard` enum
