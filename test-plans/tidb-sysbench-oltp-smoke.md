# TiDB × sysbench — OLTP smoke run

## Goal

First hands-on run of the **sysbench** kit against the **tidb** kit — "see how it works."
Prove the kit-ref wiring (`sysbench --target tidb` resolving `TARGET_MYSQL_*` over the MySQL
wire endpoint on 30400), load a small dataset, run the standard `oltp_read_write` workload, and
read throughput/latency off the auto-installed `sysbench.json` Grafana dashboard.

This is **OLTP only** — sysbench exercises TiKV (row store); it does **not** touch TiFlash. HTAP
is a separate follow-up (go-tpc / CH-benCHmark).

## Cluster Name

tidb-sysbench-oltp

## Datacenters

single

## Topology

- 1 control node (implicit — observability + k3s server)
- **3 db nodes** — TiKV + TiFlash (real 3-replica distribution)
- **1 app node** — TiDB SQL layer + PD, and the sysbench pod
- Instance type: **i4i.xlarge** for both roles
- AMI: pin to the fresh main-built db AMI `ami-03d47747b3a7ce3b1`

## sysbench parameters (smoke defaults)

- workload: `oltp_read_write`
- prepare: `--scale 10 --tables 10` (10k rows × 10 tables = 100k rows — loads in seconds)
- run: `--threads 4 --duration 60`

## Steps

### Step 1 — Scaffold the workspace wrapper

```bash
CLUSTER_DIR="clusters/tidb-sysbench-oltp-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB commands   # authoritative flag/command surface — verify before proceeding
```

### Step 2 — Provision the cluster (1 control + 3 db + 1 app)

```bash
$EDB init --name tidb-sysbench-oltp \
  --db.count 3 --app.count 1 \
  --db.instance-type i4i.xlarge --app.instance-type i4i.xlarge \
  --ami ami-03d47747b3a7ce3b1 \
  --up
```

Verify: `$EDB status` shows all nodes reachable.

### Step 3 — Start TiDB

```bash
$EDB kit install tidb
$EDB tidb start
```

Verify: the kit's readiness gates (pd → tikv → tidb → tiflash all Ready), then a round-trip
`$EDB tidb sql "SELECT 1"` returns `1`.

### Step 4 — Install sysbench, targeting tidb

```bash
$EDB kit install sysbench --target tidb
```

Verify: install resolves `TARGET_MYSQL_HOST/PORT` from the tidb `sbtest` endpoint (30400).
Confirm the `sysbench` subcommands (`prepare`, `start`, `stop`) now appear in `$EDB commands`.

### Step 5 — Prepare data (load sbtest)

```bash
$EDB sysbench prepare --workload oltp_read_write --scale 10 --tables 10
```

Verify: 10 sbtest tables created in tidb; `$EDB tidb sql "SHOW TABLES FROM sbtest"` lists them.

### Step 6 — Run the benchmark

```bash
$EDB sysbench start --workload oltp_read_write --threads 4 --duration 60
```

Verify: sysbench prints TPS / p95 latency at the end; the same series appear on the
**sysbench** Grafana dashboard (cluster=tidb-sysbench-oltp). Screenshot the dashboard.

### Step 7 — (Optional) second workload for contrast

```bash
$EDB sysbench start --workload oltp_write_only --threads 4 --duration 60
```

### Step 8 — Tear down

```bash
$EDB sysbench stop
$EDB tidb stop
$EDB down --auto-approve
```

Verify: `aws ec2 describe-instances --profile sandbox-admin ... running,pending` → 0 instances;
0 Elastic IPs. Confirm $0 residual billing.

## What we're evaluating (not perf tuning — mechanics)

- Does the kit-ref `--target` wiring resolve the MySQL-wire endpoint correctly? (the one
  integration risk — the resolver was written Postgres-first)
- Do `prepare` / `start` / `stop` behave and clean up their pods by label selector?
- Does the sysbench dashboard populate with real data from the run?
- Baseline OLTP TPS/latency on a 3-node TiKV at i4i.xlarge — a reference point, not a tuned number.

## Out of scope

- HTAP / TiFlash (needs go-tpc `tpch` or `ch`) — separate plan
- Performance tuning, larger scale, thread sweeps — this is a mechanics smoke
