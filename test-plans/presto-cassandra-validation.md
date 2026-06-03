# Lab Plan: Presto + Cassandra 5.0 Validation

## Goal

Provision a 3-node Cassandra 5.0 cluster, run a KeyValue stress workload to populate data,
install Presto, verify the Cassandra catalog is registered, and execute a live query against
the stress data.

## Environment

- 3 DB nodes: `i4i.large` (2 vCPU / 16 GB / 468 GB local NVMe — no EBS)
- 1 App node: `m5.xlarge` (4 vCPU / 16 GB — x86, runs Presto coordinator + worker)
- Cassandra version: 5.0

## Steps

### 1. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/presto-cassandra-validation-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init presto-validation --db 3 --app 1 --instance i4i.large --stress-instance m5.xlarge --up
```

### 2. Select Cassandra 5.0

```bash
$EDB cassandra use 5.0
```

This generates `cassandra.patch.yaml` with correct snitch and data directory defaults. Never
overwrite this file — always merge additional keys into it.

### 3. Push config to all DB nodes

```bash
$EDB cassandra update-config cassandra.patch.yaml
```

### 4. Start Cassandra on all nodes

```bash
$EDB cassandra start
```

Cassandra starts sequentially (staggered). Wait for the command to complete before continuing.

### 5. Verify all Cassandra nodes are UP/Normal

```bash
$EDB cassandra nt status
```

All 3 nodes must show `UN` (Up/Normal). If any node is down, wait 30 seconds and retry.

### 6. Start KeyValue stress test

```bash
$EDB cassandra stress start -- KeyValue -d 1h --threads 100
```

This creates the `cassandra_easy_stress.keyvalue` table and begins writing data. The stress job runs in the
background. Wait 30 seconds for the schema and initial rows to be written before continuing.

### 7. Scaffold the Presto kit

```bash
$EDB kit install presto --workers 1
```

This generates the `presto/` directory in the cluster workspace, including:
- `presto/catalogs/cassandra.properties` — contact points set to the actual DB node private IPs
- `presto/values.yaml` — Helm values with 1 worker, app-node selectors

### 8. Start Presto

```bash
$EDB presto start
```

This runs the Helm install, then calls `update-catalogs.sh` which reads `runningKits` from
`state.json`. Because Cassandra was started in step 4, `"cassandra"` is in `runningKits` and the
Cassandra catalog is included automatically. Expect 2–3 minutes for the rollout to complete.

### 9. Verify Presto status

```bash
$EDB presto status
```

Confirm:
- Coordinator pod is Running and Ready
- Worker pod is Running and Ready
- Presto UI endpoint listed at `http://<app-ip>:8080`

### 10. Presto health check via REST API

Verify the coordinator has finished initializing (`"starting":false`):

```bash
APP_IP=$($EDB ip --private app0)
$EDB exec run --type control -- "curl -sf http://${APP_IP}:8080/v1/info"
```

If `"starting":true`, wait 30 seconds and retry.

### 11. Validate Cassandra appears in SHOW CATALOGS

```bash
APP_IP=$($EDB ip --private app0)
$EDB exec run --type control -- bash -c "
  RESULT=\$(curl -sf -X POST http://${APP_IP}:8080/v1/statement \
    -H 'X-Presto-User: test' -H 'Content-Type: text/plain' \
    -d 'SHOW CATALOGS')
  while NEXT=\$(echo \"\$RESULT\" | jq -r '.nextUri // empty') && [ -n \"\$NEXT\" ]; do
    sleep 0.5
    RESULT=\$(curl -sf -H 'X-Presto-User: test' \"\$NEXT\")
  done
  echo \"\$RESULT\" | jq -r '.data[][]'
"
```

`cassandra` must appear in the output.

### 12. Query live stress data

```bash
APP_IP=$($EDB ip --private app0)
$EDB exec run --type control -- bash -c "
  RESULT=\$(curl -sf -X POST http://${APP_IP}:8080/v1/statement \
    -H 'X-Presto-User: test' -H 'Content-Type: text/plain' \
    -d 'SELECT count(*) FROM cassandra.cassandra_easy_stress.keyvalue')
  while NEXT=\$(echo \"\$RESULT\" | jq -r '.nextUri // empty') && [ -n \"\$NEXT\" ]; do
    sleep 0.5
    RESULT=\$(curl -sf -H 'X-Presto-User: test' \"\$NEXT\")
  done
  echo \"\$RESULT\" | jq -r '.data[][]'
"
```

The query must return a count greater than 0, confirming Presto can read live data from Cassandra.

### 13. Tear down the cluster

```bash
$EDB down --auto-approve
```

## Notes

- **Cassandra catalog auto-registration**: `cassandra start` adds `"cassandra"` to `runningKits`
  in `state.json`. `update-catalogs.sh` reads this and includes `presto/catalogs/cassandra.properties`
  automatically during `presto start`. No manual catalog step is needed as long as Cassandra is
  started before Presto.
- **KeyValue schema**: The stress workload creates keyspace `baselines`, table `keyvalue`
  (`key text PRIMARY KEY, value text`). The schema is created on first run — 30 seconds of writes
  is enough before installing Presto.
- **Presto REST API**: The API is asynchronous — POST to `/v1/statement` returns a `nextUri` to
  poll. The loop follows `nextUri` until absent, signaling the query is complete.
- **Never overwrite `cassandra.patch.yaml`** — `cassandra use` generates it with required settings
  (snitch, data dirs). Always merge new keys in.
- **Instance family**: `i4i.large` is the correct AWS instance for local NVMe (there is no
  `i4.large` in AWS).
