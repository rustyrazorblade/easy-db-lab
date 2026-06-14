# Install Kafka

The `kit install kafka` command sets up Apache Kafka in KRaft mode (no ZooKeeper) using the [Strimzi operator](https://strimzi.io/). Installation provisions local persistent volumes on db nodes and installs the Strimzi operator via Helm. `easy-db-lab kafka start` then applies the `Kafka` custom resource and waits for the cluster to become Ready; `easy-db-lab kafka stop` tears it back down.

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- At least one db node is provisioned
- Environment is sourced: `source env.sh`

## Quick Start

```bash
easy-db-lab kit install kafka
easy-db-lab kafka start
```

## Install Flags

| Flag | Default | Description |
|---|---|---|
| `--version` | `4.2.0` | Kafka version |
| `--brokers` | `1` | Number of broker/controller nodes |
| `--storage-size` | `10Ti` | Persistent volume size per broker |

## Managing Kafka

### start

Deploys the Kafka cluster and waits for it to become Ready:

1. Applies the JMX metrics ConfigMap
2. Applies the `Kafka` and `KafkaNodePool` custom resources
3. Waits for the Kafka cluster to report `Ready` (up to 300s)
4. Applies NodePort services for the kafka-exporter and JMX exporter

Grafana dashboards in `kafka/dashboards/` are installed automatically after a successful start.

### stop

Removes the `Kafka` and `KafkaNodePool` resources and NodePort services. Persistent volumes are retained — topic data survives a stop/start cycle.

### uninstall

Removes all Kafka resources including PVCs and PVs, then uninstalls the Strimzi operator.

## Bootstrap Addresses

**Internal** (in-cluster pods, e.g. a Cassandra sidecar or another kit):

```
kafka.default.svc.cluster.local:9092
```

**External** (from outside the cluster via Tailscale):

```
<control-node-private-ip>:32100
```

The external address is printed in the `kafka/README.md` generated at install time.

## Benchmarking

### Producer performance test

```bash
easy-db-lab kafka producer-perf
```

| Flag | Default | Description |
|---|---|---|
| `--num-records` | `1000000` | Number of records to produce |
| `--record-size` | `1024` | Record size in bytes |
| `--throughput` | `1000` | Target msg/sec (-1 for unlimited) |
| `--topic` | `perf-test` | Topic name |

### Consumer performance test

```bash
easy-db-lab kafka consumer-perf
```

| Flag | Default | Description |
|---|---|---|
| `--num-records` | `1000000` | Number of records to consume |
| `--topic` | `perf-test` | Topic name |
| `--group` | `bench-consumer` | Consumer group ID |

### Topic management

```bash
easy-db-lab kafka create-topic --topic my-topic --partitions 3 --replication-factor 3
```

| Flag | Default | Description |
|---|---|---|
| `--topic` | `perf-test` | Topic name |
| `--partitions` | `3` | Number of partitions |
| `--replication-factor` | `1` | Replication factor |

## Metrics

Two Prometheus scrape jobs are registered automatically by `kafka start`:

| Job | NodePort | Description |
|---|---|---|
| `kafka-exporter` | 32309 | Consumer lag, topic offsets, partition health |
| `kafka-jmx` | 32404 | Per-broker throughput, request latency, JVM metrics |

Metric names are lowercase. See `kafka/METRICS.md` for the full catalog.

The Kafka Overview Grafana dashboard is installed automatically and shows broker health, throughput, consumer lag, and topic health panels.

## CDC: Cassandra → Kafka

When using Kafka as a CDC target for Cassandra, configure the sidecar connector with the internal bootstrap address to avoid NodePort overhead:

```
kafka.default.svc.cluster.local:9092
```

## Replication and Multi-Broker Setup

The default install uses a single combined broker/controller node. For replication testing, install with multiple brokers and set the replication factor when creating topics:

```bash
easy-db-lab kit install kafka --brokers 3
easy-db-lab kafka start
easy-db-lab kafka create-topic --topic my-topic --partitions 3 --replication-factor 3
```

`--brokers` must not exceed the number of db nodes in the cluster.

## Cleaning Up

To remove Kafka and free disk space:

```bash
easy-db-lab kafka stop
easy-db-lab kit uninstall kafka
```

`kit uninstall` deletes the Kafka resources, PVCs/PVs, and the Strimzi operator.
