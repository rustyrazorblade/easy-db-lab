# Lab Plan: Cassandra 5.0 3-Node Cluster Validation

## Goal

Provision a 3-node Cassandra 5.0 cluster and verify that every `easy-db-lab cassandra` subcommand works correctly — provisioning, configuration, nodetool, CQL access, and stress testing.

## Environment

- 3 DB nodes: `i4i.xlarge` (local NVMe, no EBS)
- 1 App node: `i4i.xlarge` (for stress workloads)
- Cassandra version: 5.0

## Steps

### 1. Create cluster workspace and provision

Each run gets a unique timestamped directory. Set `CLUSTER_DIR` once — all subsequent steps reference it:

```bash
CLUSTER_DIR="clusters/cassandra-5.0-3node-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init --db 3 --app 1 --instance i4i.xlarge --stress-instance i4i.xlarge --up
```

### 2. Verify Cassandra 5.0 is available

```bash
$EDB cassandra list
```

Confirm `5.0` appears in the output.

### 3. Select Cassandra 5.0

```bash
$EDB cassandra use 5.0
```

This generates `cassandra.patch.yaml` with correct snitch and data directory settings. Do not overwrite this file in subsequent steps — always merge new keys into it.

### 4. Write a test config patch

```bash
$EDB cassandra write-config test.patch.yaml
```

Inspect the generated file to confirm it is valid YAML.

### 5. Push the config patch to all nodes

```bash
$EDB cassandra update-config cassandra.patch.yaml
```

### 6. Start Cassandra on all nodes

```bash
$EDB cassandra start
```

Cassandra starts sequentially (staggered). Wait for the command to complete.

### 7. Verify all nodes are UP/Normal — nodetool status

```bash
$EDB cassandra nt status
```

All 3 nodes should show `UN` (Up/Normal). If any node is not UN, wait 30 seconds and retry.

### 8. Check the token ring

```bash
$EDB cassandra nt ring
```

Verify all 3 nodes appear in the ring.

### 9. Check compaction stats

```bash
$EDB cassandra nt compactionstats
```

### 10. Check thread pool stats

```bash
$EDB cassandra nt tpstats
```

### 11. Verify Cassandra version via CQL

```bash
$EDB cassandra cql "SELECT release_version FROM system.local"
```

Confirm the `release_version` field shows `5.0.x`.

> **If `No node was available` error:** the sidecar connection may not be ready. Wait 30 seconds and retry.

### 12. List available stress workloads

```bash
$EDB cassandra stress list
```

### 13. List available field generators

```bash
$EDB cassandra stress fields
```

### 14. Show KeyValue workload details

```bash
$EDB cassandra stress info KeyValue
```

### 15. Run a 5-minute stress test

```bash
$EDB cassandra stress start --name cassandra5-validation --tags "cassandra=5.0,nodes=3" -- KeyValue -d 5m --threads 50
```

### 16. Verify stress job is running

```bash
$EDB cassandra stress status
```

Confirm `cassandra5-validation` appears as running.

### 17. View stress logs

```bash
$EDB cassandra stress logs cassandra5-validation
```

Confirm throughput metrics are appearing.

### 18. Wait for stress to complete (or stop it)

```bash
$EDB cassandra stress status
```

Wait for job to finish, or stop early:

```bash
$EDB cassandra stress stop cassandra5-validation --force
```

### 19. Stop all stress jobs

```bash
$EDB cassandra stress stop --all --force
```

### 20. Restart Cassandra on all nodes

```bash
$EDB cassandra restart
```

After restart completes, verify all nodes return to UN:

```bash
$EDB cassandra nt status
```

### 21. Stop Cassandra on all nodes

```bash
$EDB cassandra stop
```

### 22. Tear down the cluster

```bash
$EDB down --auto-approve
```

## Notes

- Never overwrite `cassandra.patch.yaml` — `cassandra use` generates it with required settings (snitch, data dirs). Always merge new keys in.
- Never change the snitch — `Ec2Snitch` is configured automatically by easy-db-lab.
- If CQL returns `No node was available` despite nodetool showing all nodes UN, the sidecar needs more time. Wait 30s and retry.
- Stress runs on app nodes via Kubernetes — the app node must be provisioned for stress subcommands to work.
- `stress start` requires `--` before workload args: `stress start --name foo -- KeyValue -d 5m --threads 50`
