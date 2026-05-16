# Cluster Operations Skill

Use this skill when the user asks to spin up, configure, test, or tear down an easy-db-lab cluster. It covers the full lifecycle: build → init → up → configure Cassandra → install workloads → tear down.

---

## Prerequisites

- `AWS_PROFILE` must be set in the environment.
- Run all commands from the cluster **working directory** (not the repo root). The working directory is where `state.json`, `kubeconfig`, and `env.sh` live.
- Build the project from the repo before running commands:
  ```bash
  ./gradlew installDist
  ```
  The built binary is at `build/install/easy-db-lab/bin/easy-db-lab`, but `bin/easy-db-lab` in the repo also works.

---

## Step 1 — Init

```bash
easy-db-lab init [OPTIONS] [CLUSTER_NAME]
```

**Key flags:**

| Flag | Default | Meaning |
|------|---------|---------|
| `--db, --cassandra, -c` | 3 | Number of database (Cassandra) nodes |
| `--app, --stress, -s` | 0 | Number of application/stress nodes |
| `--up` | false | Provision infrastructure immediately after init |
| `--instance, -i` | r3.2xlarge | EC2 instance type for db nodes |
| `--stress-instance, -si` | c7i.2xlarge | EC2 instance type for app nodes |
| `--clean` | false | Wipe existing local config before init |
| `--cidr` | 10.0.0.0/16 | VPC CIDR (must be /20 or larger) |
| `--tag key=value` | — | Tag EC2 instances |
| `--ebs.type` | NONE | EBS volume type (NONE, gp2, gp3, io1, io2) |
| `--ebs.size` | 256 | EBS volume size in GB |

**Examples:**
```bash
# 3 db nodes, 1 app node, instance type c5d.2xlarge, start immediately
easy-db-lab init -c 3 -s 1 -i c5d.2xlarge mytest --clean --up

# 1 db node, 1 app node, minimal for Presto testing
easy-db-lab init -c 1 -s 1 -i c5d.2xlarge presto-test --clean --up
```

`--up` is equivalent to running `easy-db-lab up` separately after init.

---

## Step 2 — Up (if not using --up in init)

```bash
easy-db-lab up
```

Provisions EC2 instances, sets up K3s, labels nodes, creates StorageClasses. Takes several minutes.

After `up` completes, activate the environment:
```bash
source env.sh
```

---

## Step 3 — Configure and Start Cassandra

### Set Cassandra version

```bash
easy-db-lab cassandra use <version>
```

Common versions: `4.0`, `4.1`, `5.0`

"Cassandra five" = version `5.0`:
```bash
easy-db-lab cassandra use 5.0
```

### Optional: patch config (Cassandra 5 requires this for compatibility)

```bash
# Add storage_compatibility_mode: NONE to cassandra.patch.yaml
easy-db-lab cassandra update-config
```

### Start Cassandra

```bash
easy-db-lab cassandra start
```

Options:
- `--sleep 120` — seconds to wait between node starts (default 120)
- `--sidecar-image <image>` — custom sidecar image

### Verify

```bash
ssh db0 nodetool status
easy-db-lab status
```

### Full Cassandra 5 sequence (from e2e test):
```bash
easy-db-lab cassandra use 5.0
# Edit cassandra.patch.yaml to add: storage_compatibility_mode: NONE
easy-db-lab cassandra update-config
easy-db-lab cassandra start
```

---

## Step 4 — Install and Start Workloads

### ClickHouse

```bash
easy-db-lab install clickhouse --size 100Gi
easy-db-lab clickhouse start
easy-db-lab clickhouse status
kubectl wait --for=condition=Ready pods -l clickhouse.altinity.com/chi=clickhouse --namespace default --timeout=300s
```

Stop: `easy-db-lab clickhouse stop --force`

### Presto

```bash
easy-db-lab install presto            # scaffold files; populates Cassandra catalog automatically
easy-db-lab presto start              # deploys via helm, waits for coordinator ready
kubectl get pods -n default -l app=presto
```

Stop: `easy-db-lab presto stop`

**Presto catalog auto-population:**
- Cassandra: always included in `values.yaml` via `__DB_NODE_IPS__` (populated at install time)
- ClickHouse: included automatically if `clickhouse/presto-catalog.properties` exists (written when ClickHouse is installed)

### OpenSearch

```bash
easy-db-lab opensearch start --wait
easy-db-lab opensearch status
```

Stop: `easy-db-lab opensearch stop --force`

---

## Step 5 — Run Stress Tests

```bash
# SSH-based stress (requires app node)
ssh app0 "bash -l -c 'cassandra-easy-stress run KeyValue -d 60s'"

# K8s-based stress job
easy-db-lab cassandra stress run --name my-test -- KeyValue -d 30s
easy-db-lab cassandra stress status
easy-db-lab cassandra stress stop --all
```

---

## Step 6 — Useful Status Commands

```bash
easy-db-lab status          # cluster overview
easy-db-lab hosts           # list all hosts with IPs
easy-db-lab cassandra list  # list Cassandra nodes
kubectl get nodes           # K3s node status
kubectl get pods -A         # all pods across namespaces
source env.sh               # re-activate SSH aliases (ssh db0, ssh app0, etc.)
```

---

## Step 7 — Tear Down

```bash
easy-db-lab down --yes                    # destroy current cluster
easy-db-lab down --all --yes              # destroy ALL easy-db-lab VPCs
easy-db-lab down --dry-run                # preview what would be deleted
easy-db-lab down vpc-xxxxxxxx --yes       # destroy specific VPC
```

Options:
- `--clickhouse.backup <name>` — back up ClickHouse before teardown
- `--retention-days 1` — days to keep S3 data (default 1)

---

## Common Patterns

### Minimal Presto test cluster
```bash
easy-db-lab init -c 1 -s 1 -i c5d.2xlarge presto-test --clean --up
source env.sh
easy-db-lab cassandra use 5.0
easy-db-lab cassandra update-config
easy-db-lab cassandra start
easy-db-lab install presto
easy-db-lab presto start
kubectl get pods -n default -l app=presto
```

### Standard 3-node Cassandra test cluster
```bash
easy-db-lab init -c 3 -s 1 -i c5d.2xlarge -i c5d.2xlarge mytest --clean --up --cidr 10.14.0.0/20
source env.sh
easy-db-lab cassandra use 5.0
easy-db-lab cassandra update-config
easy-db-lab cassandra start
```

### Run a subset of e2e steps
```bash
bin/end-to-end-test --list-steps          # see all steps with numbers
bin/end-to-end-test --start-step 13       # resume from step 13
bin/end-to-end-test --cassandra --presto  # only Cassandra and Presto steps
bin/end-to-end-test --no-teardown         # keep cluster alive after run
```

---

## Working Directory Files

| File | Purpose |
|------|---------|
| `state.json` | Cluster state (VPC, hosts, buckets) |
| `kubeconfig` | K3s kubeconfig (also at `~/.kube/config` after `source env.sh`) |
| `env.sh` | Shell env: SSH aliases (`ssh db0`, `ssh app0`), KUBECONFIG export |
| `cassandra_versions.yaml` | Available Cassandra versions on the nodes |
| `cassandra.patch.yaml` | Config patches applied before `cassandra update-config` |

---

## Cassandra Version Reference

| User says | Command |
|-----------|---------|
| "Cassandra 4" | `easy-db-lab cassandra use 4.0` |
| "Cassandra 4.1" | `easy-db-lab cassandra use 4.1` |
| "Cassandra 5" / "Cassandra five" | `easy-db-lab cassandra use 5.0` |

After `cassandra use <version>`, always run `cassandra update-config` then `cassandra start`.
