# TiDB + Sysbench Benchmark

End-to-end OLTP benchmark of TiDB using sysbench `oltp_read_write`. Validates the full
sysbench kit lifecycle: install → prepare → start (with metrics push) → stop.

## Cluster Configuration

| Node type | Count | Role |
|-----------|-------|------|
| control   | 1     | K3s control plane, observability stack |
| db        | 1     | TiKV (row store) + TiFlash (columnar) |
| app       | 1     | TiDB SQL layer + PD (placement driver) |

## Prerequisites

- AWS credentials configured with `sandbox-admin` profile
- Tailscale running on the developer machine (required for Grafana access and metrics push)
- Project built: `./gradlew installDist`

---

## Steps

### 1. Set up cluster workspace

```bash
CLUSTER_DIR="clusters/tidb-sysbench-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
```

### 2. Initialize cluster

```bash
$EDB init --name tidb-sysbench --db-nodes 1 --app-nodes 1
```

### 3. Build the binary

```bash
./gradlew installDist
```

### 4. Bring up cluster

```bash
$EDB up
```

Wait for all nodes to be provisioned and K3s to be running (~5 min).

### 5. Install and start TiDB

```bash
$EDB kit install tidb
$EDB tidb start
```

All four components must reach Ready: PD → TiKV → TiDB → TiFlash. Expect 10–15 min.

### 6. Install sysbench targeting TiDB

```bash
$EDB kit install sysbench --target tidb --threads 8 --duration 300 --tables 10 --scale 10
```

This installs into `sysbench-tidb/` in the cluster workspace.

### 7. Prepare test data

Creates the `sbtest` database and loads 10 tables × 10,000 rows:

```bash
$EDB sysbench-tidb prepare
```

Expect ~3–5 min. Watch pod logs for progress:

```bash
kubectl --kubeconfig="$CLUSTER_DIR/kubeconfig" logs -f sysbench-prepare-sysbench-tidb
```

### 8. Run the benchmark

```bash
$EDB sysbench-tidb start
```

Sysbench reports TPS/QPS/P95 latency every 10 seconds. Metrics are pushed live to
VictoriaMetrics over Tailscale. The command streams output until the benchmark completes
(`--duration 300` = 5 min run).

### 9. View metrics in Grafana

Open Grafana at `http://<CONTROL_HOST_PRIVATE>:3000` (check `$CLUSTER_DIR/state.json` for
the control node's private IP):

- **Sysbench Benchmark** dashboard — client-side TPS, QPS, P95 latency, errors
- Select `Kit = sysbench-tidb` from the dropdown

### 10. Stop and clean up

```bash
$EDB sysbench-tidb stop     # drops sbtest tables, deletes pod
$EDB tidb stop               # tears down TiDB cluster resources
$EDB down --auto-approve     # terminates EC2 instances
```

---

## Validation Checklist

- [ ] `tidb start` completes — all four components (pd, tikv, tidb, tiflash) are Ready
- [ ] `sysbench-tidb prepare` creates the `sbtest` database and exits with code 0
- [ ] `sysbench-tidb start` streams interval stats (TPS/QPS/latency every 10s)
- [ ] Metrics appear in Grafana **Sysbench Benchmark** dashboard within 30s of start
- [ ] `sysbench-tidb stop` exits cleanly; no orphaned pods remain
- [ ] `tidb stop` removes TiDB cluster resources cleanly

## Notes

- `--scale 10` = 10,000 rows per table. Use `--scale 100` (100k rows) for heavier load.
- For parallel multi-database comparison, install sysbench twice:
  ```bash
  $EDB kit install sysbench --target tidb     --threads 8 --duration 300
  $EDB kit install sysbench --target clickhouse --threads 8 --duration 300
  $EDB sysbench-tidb start &
  $EDB sysbench-clickhouse start &
  ```
  Both appear as separate series in the Sysbench Benchmark dashboard.
- The `mysql:8.0` image used in the create-db pod connects with no password, matching
  TiDB's default root account.
