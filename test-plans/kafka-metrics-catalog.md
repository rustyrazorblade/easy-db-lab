# Lab Plan: Kafka Metrics Catalog and Dashboard

## Objective

Deploy a 3-node Kafka cluster in KRaft mode, generate realistic producer/consumer load to activate all JMX metrics, scrape VictoriaMetrics to populate the metrics catalog, write METRICS.md, and build a Grafana dashboard — all while the cluster is live for iterative verification.

## Cluster Name

kafka-metrics

## Datacenters

single

## Environment

3 db nodes, `i4i.xlarge` (local NVMe, no EBS). No app nodes needed — all load generation runs via `kubectl exec` inside the cluster. No Cassandra is started; the db nodes serve purely as K8s workers for Kafka.

## Steps

### 1. Provision the cluster

Spin up 3 db nodes. The observability stack (OTel, VictoriaMetrics, Grafana) comes up automatically.

```bash
$EDB init kafka-metrics --db 3 --instance i4i.xlarge --up
```

### 2. Install the Kafka kit

Install the Strimzi operator and pre-provision local PVs for 3 brokers.

```bash
$EDB kit install kafka --brokers 3
```

### 3. Start Kafka

Deploy Kafka in KRaft mode via the Strimzi operator and wait for all pods to be Ready. The kit also registers the OTel scrape jobs for the kafka-exporter (port 32309) and broker JMX (port 32404).

```bash
$EDB kafka start
```

### 4. Create a test topic

Create a topic with 3 partitions and replication factor 3 to exercise per-partition and cross-broker metrics.

```bash
$EDB kafka create-topic --topic perf-test --partitions 3 --replication-factor 3
```

### 5. Run producer load (5 minutes)

Produce 1 million messages at 1k msg/s with 1 KiB records. This activates byte-in rates, request latency, and leader metrics.

```bash
$EDB kafka producer-perf
```

Defaults: 1 MM records, 1 KB each, 1 000 msg/s, topic `perf-test`. Override with flags:
```bash
$EDB kafka producer-perf --num-records 2000000 --throughput 5000
```

### 6. Run consumer load

Consume all messages from the beginning to activate consumer lag and fetch metrics. Run in a second terminal while the producer is still running, or immediately after.

```bash
$EDB kafka consumer-perf
```

Defaults: 1 MM messages, topic `perf-test`, group `bench-consumer`, reads from earliest offset.

### 7. Verify OTel is scraping Kafka metrics

Wait ~2 minutes after `kafka start` for the OTel collector to scrape and forward metrics to VictoriaMetrics. Verify with a quick count.

```bash
CONTROL_IP=$(jq -r '.hosts.Control[0].privateIp' state.json)
curl -s "http://${CONTROL_IP}:8428/api/v1/label/__name__/values" | \
  jq '[.data[] | select(startswith("kafka_"))] | length'
```

Expected: at least 25 metric names. If 0, wait another minute and retry.

### 8. Dump the full metrics catalog from VictoriaMetrics

Query VictoriaMetrics for every series from both kafka scrape jobs and write to the catalog file.

```bash
CONTROL_IP=$(jq -r '.hosts.Control[0].privateIp' state.json)
curl -s "http://${CONTROL_IP}:8428/api/v1/series?match[]={job=~\"kafka.*\"}" | \
  jq '{
    workload: "kafka",
    exported_at: (now | todate),
    series: [
      .data[] | {
        name: .["__name__"],
        labels: (del(.["__name__"]))
      }
    ]
  }' \
  > kafka-metrics-catalog-raw.json

echo "Captured $(jq '.series | length' kafka-metrics-catalog-raw.json) series"
```

### 9. Verify the Kafka Overview dashboard in Grafana

The dashboard ships inside the kit and is installed automatically when `kafka start` completes. Open Grafana and confirm the "Kafka Overview" dashboard appears under the `kafka` folder with live data in all panels.

The Grafana URL is: `http://<CONTROL_IP>:3000` (default credentials: admin/admin)

```bash
CONTROL_IP=$(jq -r '.hosts.Control[0].privateIp' state.json)
echo "Grafana: http://${CONTROL_IP}:3000"
```

Expected panels with data:
- Cluster Health: Brokers Online = 3, Active Controller = 1, Under-Replicated = 0
- Throughput: Bytes In/Out and Messages In showing activity from producer-perf
- Consumer Lag: lag for `bench-consumer` / `perf-test`
- Topic Health: offset growth visible for `perf-test` partitions

### 10. Tear down

```bash
$EDB down --auto-approve
```

## Notes

- **Two OTel scrape jobs**: `kafka-exporter` (NodePort 32309) provides consumer lag and topic health; `kafka-jmx` (NodePort 32404) provides per-broker throughput and request latency. Both are registered automatically by `kafka start`.
- **Metrics lag**: OTel scrapes on a 15s interval. After `kafka start`, wait at least 2 minutes before checking VictoriaMetrics to ensure both scrape jobs are live.
- **JMX metrics are approximate**: The JMX NodePort (32404) load-balances across 3 broker pods. Each OTel instance hits a random broker, so per-broker metrics use `avg` in the dashboard rather than exact per-pod attribution.
- **Kafka version**: Strimzi 1.0.0 supports Kafka 4.1.0, 4.1.1, 4.1.2, 4.2.0. The default is 4.2.0. Override with `kit install kafka --version 4.1.2`.
- **Keep load running during dashboard work**: The producer/consumer perf tests finish quickly. For steps 11-13, re-run them periodically so Grafana panels have live data to display.
