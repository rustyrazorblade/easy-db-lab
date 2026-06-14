# Kafka Metrics Catalog

Metrics come from two sources:

- **JMX Exporter** (per broker, port 9404 → NodePort 32404 via kafka-broker-jmx-nodeport service)
  — scraped from each broker pod's JMX exporter agent
- **Kafka Exporter** (cluster-wide, port 9308 → NodePort 32309 via kafka-exporter-nodeport service)
  — Strimzi's kafka-exporter deployment; consumer lag, topic offsets, partition health

---

## Throughput (JMX Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_server_brokertopicmetrics_bytesinpersec_rate` | `topic` (optional) | Bytes received per second (1-min rate) |
| `kafka_server_brokertopicmetrics_bytesoutpersec_rate` | `topic` (optional) | Bytes sent per second (1-min rate) |
| `kafka_server_brokertopicmetrics_messagesinpersec_rate` | `topic` (optional) | Messages received per second (1-min rate) |

## Request Latency (JMX Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_network_requestmetrics_totaltimems_mean` | `request` | Mean total request time in ms |
| `kafka_network_requestmetrics_totaltimems_99thpercentile` | `request` | p99 total request time in ms |

Key `request` values: `Produce`, `Fetch`, `FetchConsumer`, `Metadata`

## Partition / Replica Health (JMX Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_server_replicamanager_underreplicatedpartitions` | — | Under-replicated partitions (alert if > 0) |
| `kafka_server_replicamanager_partitioncount` | — | Total partitions on this broker |
| `kafka_server_replicamanager_leadercount` | — | Partitions for which this broker is leader |
| `kafka_controller_kafkacontroller_activecontrollercount` | — | Active controller count (should be 1 cluster-wide) |
| `kafka_controller_kafkacontroller_offlinepartitionscount` | — | Offline partitions (alert if > 0) |

## Consumer Lag (Kafka Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_consumergroup_lag` | `consumergroup`, `topic`, `partition` | Per-partition consumer lag |
| `kafka_consumergroup_lag_sum` | `consumergroup`, `topic` | Total lag for this consumer group + topic |
| `kafka_consumergroup_current_offset` | `consumergroup`, `topic`, `partition` | Last committed offset per partition |
| `kafka_consumergroup_current_offset_sum` | `consumergroup`, `topic` | Sum of committed offsets |
| `kafka_consumergroup_members` | `consumergroup` | Number of active members |

## Topic Health (Kafka Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_topic_partition_current_offset` | `topic`, `partition` | Latest offset (log end) |
| `kafka_topic_partition_oldest_offset` | `topic`, `partition` | Earliest available offset |
| `kafka_topic_partition_under_replicated_partition` | `topic`, `partition` | 1 if under-replicated |
| `kafka_topic_partition_in_sync_replica` | `topic`, `partition` | ISR count |
| `kafka_topic_partition_replicas` | `topic`, `partition` | Replica count |
| `kafka_topic_partition_leader` | `topic`, `partition` | Broker ID of the leader |
| `kafka_topic_partition_leader_is_preferred` | `topic`, `partition` | 1 if leader is preferred |
| `kafka_topic_partitions` | `topic` | Partition count for this topic |
| `kafka_brokers` | — | Number of brokers |
| `kafka_broker_info` | `id`, `address` | Broker presence (1 per broker) |

## JVM Metrics (JMX Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `jvm_memory_heap_used_bytes` | — | Heap memory in use |
| `jvm_memory_heap_committed_bytes` | — | Committed heap |
| `jvm_gc_collection_seconds_count` | `gc` | GC count |
| `jvm_gc_collection_seconds_sum` | `gc` | Total GC time |
| `jvm_threads_current` | — | Live thread count |

## Log Manager (JMX Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_log_logmanager_offlinelogdirectorycount` | — | Offline log directories (alert if > 0) |

## Coordinator (JMX Exporter)

| Metric | Labels | Description |
|--------|--------|-------------|
| `kafka_coordinator_group_numgroups` | — | Number of consumer groups |
| `kafka_coordinator_group_numoffsets` | — | Total tracked offsets across all groups |
