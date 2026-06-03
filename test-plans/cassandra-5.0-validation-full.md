# Lab Plan: Cassandra 5.0 3-Node Cluster Full Validation

## Goal

Provision a 3-node Cassandra 5.0 cluster and run a comprehensive validation: stress testing with
job lifecycle management, metrics verification in VictoriaMetrics, stop/start/restart cycles, and
a version switch to Cassandra 4.1.

## Environment

- 3 DB nodes: `i4i.xlarge` (local NVMe, no EBS)
- 1 App node: `i4i.xlarge` (for stress workloads)
- Cassandra versions tested: 5.0 and 4.1

## Steps

### 1. Create cluster workspace and provision

```bash
CLUSTER_DIR="clusters/cassandra-5.0-full-$(date +%Y%m%d-%H%M%S)"
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

### 4. Configure and start Cassandra 5.0

```bash
$EDB cassandra use 5.0
$EDB cassandra update-config cassandra.patch.yaml
$EDB cassandra start
```

### 5. Verify Cassandra is up

```bash
$EDB cassandra nt status
```

All 3 nodes should show `UN`.

### 6. Run stress test (30s KeyValue)

```bash
$EDB cassandra stress start --name cassandra-test -- KeyValue -d 30s
$EDB cassandra stress status
```

### 7. Wait for stress job to complete

```bash
for i in $(seq 1 60); do
    STATUS=$(kubectl get jobs -l app.kubernetes.io/name=cassandra-easy-stress \
        -o jsonpath='{.items[0].status.succeeded}' 2>/dev/null || echo "")
    if [ "$STATUS" = "1" ]; then
        echo "Stress job completed ✓"
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "ERROR: stress job did not complete within 600s"
        $EDB cassandra stress status
        exit 1
    fi
    sleep 10
done
```

### 8. View stress job logs

```bash
JOB_NAME=$(kubectl get jobs -l app.kubernetes.io/name=cassandra-easy-stress \
    --sort-by=.metadata.creationTimestamp \
    -o jsonpath='{.items[-1].metadata.name}')
[ -n "$JOB_NAME" ] && $EDB cassandra stress logs "$JOB_NAME" --tail 30
```

### 9. Stop all stress jobs

```bash
$EDB cassandra stress stop --all
$EDB cassandra stress status
```

### 10. Verify Cassandra metrics in VictoriaMetrics

Wait 30 seconds for metrics to flow, then check:

```bash
sleep 30
CONTROL_IP=$($EDB ip control0 --private)
METRIC_COUNT=$(curl -sf "http://${CONTROL_IP}:8428/api/v1/series" \
    --data-urlencode 'match[]={job="cassandra"}' | jq '.data | length')
if [ "${METRIC_COUNT:-0}" -lt 10 ]; then
    echo "ERROR: expected ≥10 Cassandra metric series in VictoriaMetrics, got ${METRIC_COUNT:-0}"
    exit 1
fi
echo "VictoriaMetrics has ${METRIC_COUNT} Cassandra metric series ✓"
```

### 11. Stop and start Cassandra

```bash
$EDB cassandra stop
$EDB cassandra start
$EDB cassandra nt status
```

### 12. Restart Cassandra via restart command

```bash
$EDB cassandra restart
$EDB cassandra nt status
```

### 13. Stop Cassandra before version switch

```bash
$EDB cassandra stop
```

### 14. Switch to Cassandra 4.1 and verify

```bash
$EDB cassandra use 4.1
$EDB cassandra update-config cassandra.patch.yaml
$EDB cassandra start
$EDB cassandra nt status
```

### 15. Stop Cassandra 4.1

```bash
$EDB cassandra stop
```

### 16. Tear down the cluster

```bash
$EDB down --auto-approve
```

## Notes

- The stress wait in step 7 polls the Kubernetes job directly — more reliable than polling `stress status` for short-duration jobs that may complete before the first poll.
- Version switching (step 14) requires stopping Cassandra first — starting 4.1 on top of 5.0 data will fail.
- Metrics take ~30 seconds to appear in VictoriaMetrics after Cassandra starts. The sleep in step 10 is intentional.
