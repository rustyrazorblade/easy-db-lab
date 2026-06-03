# Presto Metrics

All metrics have `job="presto"` and `cluster=<cluster-name>`. Metrics are scraped from the JMX Prometheus exporter on port 9090.

## Key Metrics

| Metric | Labels | Description |
|--------|--------|-------------|
| `com_facebook_presto_execution_QueryManager_RunningQueries` | | Number of queries currently executing |
| `com_facebook_presto_execution_QueryManager_QueuedQueries` | | Number of queries waiting for a slot |
| `com_facebook_presto_execution_QueryManager_FailedQueries_OneMinute_Rate` | | Failed query rate (1-minute EWMA) |
| `com_facebook_presto_execution_QueryManager_FailedQueries_TotalCount` | | Cumulative failed query count |
| `com_facebook_presto_dispatcher_DispatchManager_CompletedQueries_OneMinute_Rate` | | Completed query throughput (1-minute EWMA) |
| `com_facebook_presto_dispatcher_DispatchManager_CompletedQueries_TotalCount` | | Cumulative completed query count |
| `com_facebook_presto_dispatcher_DispatchManager_AbandonedQueries_TotalCount` | | Cumulative abandoned (client disconnected) query count |
| `com_facebook_presto_execution_executor_TaskExecutor_BlockedSplits` | | Splits currently blocked waiting for data |
| `com_facebook_presto_memory_ClusterMemoryManager_ClusterUserMemoryReservation` | | Bytes of user memory reserved cluster-wide |
| `jvm_memory_bytes_used` | `area=heap` | JVM heap memory in use |
| `jvm_memory_bytes_max` | `area=heap` | JVM heap memory maximum |
| `jvm_memory_pool_bytes_used` | `pool=G1 Old Gen` | Old generation pool usage (GC pressure indicator) |
| `jvm_gc_collection_seconds_sum` | `gc=G1 Young Generation` | Cumulative GC pause time |
| `jvm_gc_collection_seconds_count` | `gc=G1 Young Generation` | Cumulative GC collection count |
| `process_cpu_seconds_total` | | Cumulative CPU time consumed by the coordinator process |

## Usage Notes

- `*_OneMinute_Rate` metrics are exponentially weighted moving averages (EWMA) — best for dashboards.
- `*_TotalCount` metrics are monotonically increasing counters — use `rate()` for throughput.
- `BlockedSplits` > 0 sustained means the coordinator is waiting on I/O or network; investigate catalog/connector health.
- Heap approaching `jvm_memory_bytes_max` with high GC time indicates memory pressure; check query complexity or lower `query.max-memory`.
