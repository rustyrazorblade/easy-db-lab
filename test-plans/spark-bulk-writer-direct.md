# Lab Plan: Spark Bulk Writer (Direct/Sidecar Transport)

## Goal

Provision a Cassandra cluster with an EMR Spark cluster, then use the `DirectBulkWriter` Spark job
to bulk-load 10,000 rows via the Cassandra sidecar (direct transport). Success: CQL confirms
exactly 10,000 rows in `bulk_test.data_sidecar` after the Spark job completes.

## Environment

- 3 DB nodes: `i4i.xlarge` (local NVMe, no EBS)
- Spark EMR: 1×`m5.xlarge` master, 2×`m5.xlarge` workers
- Cassandra 5.0, `storage_compatibility_mode: NONE` (required for sidecar bulk import)

## Steps

### 1. Build the bulk-writer-sidecar JAR

```bash
./gradlew :spark:bulk-writer-sidecar:shadowJar
JAR_FILE=$(ls spark/bulk-writer-sidecar/build/libs/bulk-writer-sidecar*.jar | head -1)
echo "Using JAR: $JAR_FILE"
```

### 2. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/spark-bulk-writer-direct-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$CLUSTER_DIR"
bin/create-easy-db-lab-wrapper "$CLUSTER_DIR"
EDB="$CLUSTER_DIR/easy-db-lab"
$EDB init --db 3 --app 0 \
  --instance i4i.xlarge \
  --spark.enable \
  --spark.master.instance.type m5.xlarge \
  --spark.worker.instance.type m5.xlarge \
  --spark.worker.instance.count 2 \
  --up
```

### 3. Configure kubectl access

```bash
export KUBECONFIG="$CLUSTER_DIR/kubeconfig"
```

### 4. Wait for K3s nodes to be Ready

```bash
kubectl wait --for=condition=Ready nodes --all --timeout=120s
kubectl get nodes
```

### 5. Configure Cassandra 5.0 with storage_compatibility_mode: NONE

```bash
$EDB cassandra use 5.0
echo "storage_compatibility_mode: NONE" >> "$CLUSTER_DIR/cassandra.patch.yaml"
$EDB cassandra update-config cassandra.patch.yaml
```

`storage_compatibility_mode: NONE` is required — without it the sidecar marks restore jobs as
removed immediately and no data is imported (symptom: COUNT returns 0 after a successful Spark job).

### 6. Start Cassandra

```bash
$EDB cassandra start
```

### 7. Verify Cassandra is up

```bash
$EDB cassandra nt status
```

All nodes should show `UN` (Up/Normal).

### 8. Submit the bulk writer (direct) Spark job

```bash
HOSTS=$(jq -r '.hosts.Cassandra | map(.privateIp) | join(",")' "$CLUSTER_DIR/state.json")
DC=$($EDB aws region)

$EDB spark submit \
  --jar "$JAR_FILE" \
  --main-class com.rustyrazorblade.easydblab.spark.DirectBulkWriter \
  --conf "spark.easydblab.contactPoints=$HOSTS" \
  --conf "spark.easydblab.keyspace=bulk_test" \
  --conf "spark.easydblab.table=data_sidecar" \
  --conf "spark.easydblab.localDc=$DC" \
  --conf "spark.easydblab.rowCount=10000" \
  --conf "spark.easydblab.parallelism=4" \
  --conf "spark.easydblab.partitionCount=100" \
  --conf "spark.easydblab.replicationFactor=3" \
  --wait
```

### 9. Verify row count via CQL

```bash
$EDB cassandra cql "SELECT COUNT(*) FROM bulk_test.data_sidecar"
```

Expected: `10000`

### 10. View Spark job logs

```bash
$EDB spark logs
```

### 11. Tear down the cluster

```bash
$EDB down --auto-approve
```

## Notes

- `storage_compatibility_mode: NONE` must be appended to `cassandra.patch.yaml` before `cassandra start` — not after.
- The sidecar transport writes directly to Cassandra nodes via the sidecar API (port 9043) — no S3 staging required.
- Build the JAR from the project root before running this plan. The shadowJar task produces a fat JAR with all dependencies.
