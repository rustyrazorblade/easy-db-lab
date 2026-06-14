# Lab Plan: Kafka Kit Validation

## Objective

Validate the kafka kit end-to-end on the `691-support-kafka` branch: install Kafka via
Strimzi in KRaft mode on a 3-broker cluster, generate load with `producer-perf` and
`consumer-perf`, confirm both metrics endpoints (kafka-exporter on NodePort 32309 and JMX
on NodePort 32404) are reachable and returning data, and verify the Grafana dashboard
auto-installs on `start` and panels populate with real metrics.

Success: all three scripts run without error, `curl` returns metrics from both endpoints,
and the Grafana kafka dashboard is visible with non-empty panel data.

## Cluster Name

kafka-validation

## Datacenters

single

## Environment

3 db nodes (`i4i.xlarge`, local NVMe) — Kafka brokers run on db nodes.
No app nodes needed — perf scripts run inside the Kafka pod via `kubectl exec`.

## Steps

### 1. Provision the cluster

Spin up 3 db nodes with local NVMe. AWS profile: `sandbox-admin`.

```bash
easy-db-lab init --db 3 --instance i4i.xlarge --up --tag branch=691-support-kafka
```

### 2. Install the Kafka kit with 3 brokers

```bash
easy-db-lab kit install kafka --brokers 3
```

### 3. Start Kafka

This deploys the Strimzi operator, the Kafka CR, both NodePort services, and the
metrics ConfigMap. On success the Grafana kafka dashboard is auto-installed.

```bash
easy-db-lab kafka start
```

### 4. Verify Kafka status and bootstrap endpoint

```bash
easy-db-lab kafka status
```

Look for the external bootstrap address (`<control-ip>:32100`) in the output.

### 5. Create a test topic

```bash
easy-db-lab kafka create-topic --topic perf-test --partitions 3 --replication-factor 3
```

### 6. Run producer-perf

Generates write load and throughput metrics.

```bash
easy-db-lab kafka producer-perf
```

### 7. Run consumer-perf

Generates consumer lag and throughput metrics.

```bash
easy-db-lab kafka consumer-perf
```

### 8. Verify kafka-exporter metrics endpoint (NodePort 32309)

kafka-exporter exposes consumer lag, topic, and partition metrics.

```bash
CONTROL_IP=$(easy-db-lab status --json | jq -r '.hosts.control[0].privateIp')
curl -s "http://${CONTROL_IP}:32309/metrics" | grep -E '^kafka_' | head -20
```

Confirm output contains `kafka_brokers`, `kafka_topic_partitions`, and consumer lag metrics.

### 9. Verify JMX metrics endpoint (NodePort 32404)

Strimzi JMX exporter exposes broker internals (throughput, request latency, GC).

```bash
curl -s "http://${CONTROL_IP}:32404/metrics" | grep -E '^kafka_|^jvm_' | head -20
```

Confirm output contains `kafka_server_brokertopicmetrics` and `jvm_memory_heap` metrics.

### 10. Open Grafana and verify dashboard

```bash
easy-db-lab status
```

Open Grafana at `http://<control-ip>:3000`. Navigate to Dashboards → kafka.
Confirm panels for broker throughput, request latency, partition health, heap memory,
and GC time all show data (not "No data").

### 11. Tear down

```bash
easy-db-lab down --auto-approve
```

## Notes

- The `producer-perf` and `consumer-perf` scripts run inside the Kafka pod via `kubectl exec` — no app node is needed.
- If `kafka start` times out waiting for the Strimzi operator, check `kubectl get pods -n default` on the control node for pending pods.
- The `$cluster` template variable in Grafana populates from `kafka_brokers` — it will be empty until at least one scrape cycle completes (~30s after start).
- Both metrics endpoints scrape via OTel on the same interval; allow 60s after `start` before checking panels.
