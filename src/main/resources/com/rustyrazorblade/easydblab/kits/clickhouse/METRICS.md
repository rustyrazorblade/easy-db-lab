# ClickHouse Metrics

All metrics have `job="clickhouse"` and `cluster=<cluster-name>`. Metrics are scraped from the
ClickHouse built-in Prometheus exporter at port 30936 (NodePort).

ClickHouse exposes three metric namespaces:

- **`ClickHouseMetrics_*`** — instantaneous gauges (current state, no `rate()` needed)
- **`ClickHouseAsyncMetrics_*`** — background async gauges (OS, memory, replication, ZooKeeper)
- **`ClickHouseProfileEvents_*_total`** — monotonically increasing counters; always use `rate()` or `increase()`

## Query Throughput

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseProfileEvents_Query_total` | counter | All queries executed (SELECT + INSERT + other) |
| `ClickHouseProfileEvents_SelectQuery_total` | counter | SELECT queries only |
| `ClickHouseProfileEvents_InsertQuery_total` | counter | INSERT queries only |
| `ClickHouseProfileEvents_FailedQuery_total` | counter | Queries that failed with an error |
| `ClickHouseProfileEvents_QueryMemoryLimitExceeded_total` | counter | Queries killed by memory limit |
| `ClickHouseAsyncMetrics_QueriesMemoryUsage` | gauge | Bytes of memory currently used by running queries |
| `ClickHouseAsyncMetrics_QueriesPeakMemoryUsage` | gauge | Peak memory used across all running queries |

## Insert Throughput & Backpressure

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseProfileEvents_InsertedRows_total` | counter | Rows inserted into MergeTree tables |
| `ClickHouseProfileEvents_InsertedBytes_total` | counter | Uncompressed bytes inserted |
| `ClickHouseProfileEvents_RejectedInserts_total` | counter | Inserts rejected due to merge backlog (parts > max_parts_in_total) |
| `ClickHouseProfileEvents_DelayedInserts_total` | counter | Inserts that were throttled (not rejected) due to merge backlog |
| `ClickHouseMetrics_DelayedInserts` | gauge | Inserts currently being throttled |
| `ClickHouseMetrics_PendingAsyncInsert` | gauge | Async inserts waiting to be flushed |
| `ClickHouseMetrics_AsynchronousInsertQueueSize` | gauge | Number of entries in the async insert queue |

## MergeTree Health

The most important indicator of MergeTree health is `MaxPartCountForPartition`. Values above ~100 indicate merge backlog and will cause insert throttling or rejection.

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseAsyncMetrics_MaxPartCountForPartition` | gauge | Maximum part count across all partitions — the key MergeTree health indicator |
| `ClickHouseAsyncMetrics_LongestRunningMerge` | gauge | Duration in seconds of the longest active merge |
| `ClickHouseMetrics_Merge` | gauge | Number of merges currently in progress |
| `ClickHouseMetrics_DiskSpaceReservedForMerge` | gauge | Bytes of disk space currently reserved for active merges |
| `ClickHouseMetrics_BackgroundMergesAndMutationsPoolTask` | gauge | Background merge/mutation tasks currently executing |
| `ClickHouseMetrics_BackgroundMergesAndMutationsPoolSize` | gauge | Capacity of the merge/mutation background pool |
| `ClickHouseProfileEvents_ReplicatedPartMerges_total` | counter | Replicated part merges completed |
| `ClickHouseProfileEvents_MergedRows_total` | counter | Rows processed by merges |
| `ClickHouseProfileEvents_MergedUncompressedBytes_total` | counter | Uncompressed bytes processed by merges |

## Read Performance

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseProfileEvents_SelectedRows_total` | counter | Rows read by SELECT queries |
| `ClickHouseProfileEvents_SelectedBytes_total` | counter | Uncompressed bytes read by SELECT queries |
| `ClickHouseProfileEvents_SelectedParts_total` | counter | MergeTree parts accessed by SELECT queries |
| `ClickHouseProfileEvents_SelectedMarks_total` | counter | Granule marks read (higher = less index pruning) |
| `ClickHouseProfileEvents_ReadCompressedBytes_total` | counter | Compressed bytes read from storage |
| `ClickHouseProfileEvents_ReadBackoff_total` | counter | Read backoffs due to slow I/O |

## Memory

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseAsyncMetrics_MemoryResident` | gauge | Resident set size (RSS) of the ClickHouse process |
| `ClickHouseAsyncMetrics_MemoryVirtual` | gauge | Virtual memory size of the ClickHouse process |
| `ClickHouseMetrics_MemoryTracking` | gauge | Bytes tracked by the ClickHouse memory accounting system |
| `ClickHouseMetrics_MergesMutationsMemoryTracking` | gauge | Memory used by active merges and mutations |
| `ClickHouseAsyncMetrics_jemalloc.resident` | gauge | jemalloc resident memory (physical pages mapped) |
| `ClickHouseAsyncMetrics_jemalloc.allocated` | gauge | jemalloc bytes allocated by the application |
| `ClickHouseAsyncMetrics_jemalloc.active` | gauge | jemalloc active bytes (allocated rounded to page size) |

## Caches

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseProfileEvents_UncompressedCacheHits_total` | counter | Uncompressed block cache hits |
| `ClickHouseProfileEvents_UncompressedCacheMisses_total` | counter | Uncompressed block cache misses |
| `ClickHouseMetrics_UncompressedCacheBytes` | gauge | Bytes currently in the uncompressed block cache |
| `ClickHouseProfileEvents_QueryCacheHits_total` | counter | Query result cache hits |
| `ClickHouseProfileEvents_QueryCacheMisses_total` | counter | Query result cache misses |
| `ClickHouseMetrics_QueryCacheBytes` | gauge | Bytes currently in the query result cache |
| `ClickHouseMetrics_MarkCacheBytes` | gauge | Bytes currently in the mark (index) cache |
| `ClickHouseMetrics_FilesystemCacheSize` | gauge | S3 disk cache bytes currently used |
| `ClickHouseMetrics_FilesystemCacheSizeLimit` | gauge | S3 disk cache byte limit |

## S3 I/O

Relevant when using S3-tiered storage (the default kit configuration).

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseProfileEvents_S3ReadRequestsCount_total` | counter | S3 GET/HEAD requests per second |
| `ClickHouseProfileEvents_S3WriteRequestsCount_total` | counter | S3 PUT/POST requests per second |
| `ClickHouseProfileEvents_S3ReadRequestsErrors_total` | counter | S3 read request errors |
| `ClickHouseProfileEvents_S3WriteRequestsErrors_total` | counter | S3 write request errors |
| `ClickHouseProfileEvents_S3ReadRequestsThrottling_total` | counter | S3 read requests throttled by AWS |
| `ClickHouseProfileEvents_S3WriteRequestsThrottling_total` | counter | S3 write requests throttled by AWS |
| `ClickHouseProfileEvents_S3ReadMicroseconds_total` | counter | Cumulative S3 read latency in microseconds (divide by count for avg) |
| `ClickHouseProfileEvents_S3WriteMicroseconds_total` | counter | Cumulative S3 write latency in microseconds |

## Replication

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseAsyncMetrics_ReplicasMaxAbsoluteDelay` | gauge | Maximum replication lag in seconds across all replicated tables |
| `ClickHouseAsyncMetrics_ReplicasMaxRelativeDelay` | gauge | Maximum replication lag relative to the most up-to-date replica |
| `ClickHouseAsyncMetrics_ReplicasMaxQueueSize` | gauge | Maximum replication queue depth |
| `ClickHouseAsyncMetrics_ReplicasMaxInsertsInQueue` | gauge | Maximum pending inserts in replication queue |
| `ClickHouseAsyncMetrics_ReplicasMaxMergesInQueue` | gauge | Maximum pending merges in replication queue |
| `ClickHouseProfileEvents_ReplicatedDataLoss_total` | counter | Data loss events — a non-zero rate is a critical alert |
| `ClickHouseProfileEvents_ReplicatedPartFailedFetches_total` | counter | Failed replica part fetch attempts |
| `ClickHouseMetrics_ReadonlyReplica` | gauge | Number of tables currently in read-only state due to ZooKeeper issues |

## Network

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseProfileEvents_NetworkReceiveBytes_total` | counter | Bytes received over the network |
| `ClickHouseProfileEvents_NetworkSendBytes_total` | counter | Bytes sent over the network |
| `ClickHouseMetrics_HTTPConnection` | gauge | Active HTTP connections to ClickHouse |
| `ClickHouseMetrics_TCPConnection` | gauge | Active TCP connections to ClickHouse |
| `ClickHouseMetrics_ZooKeeperSession` | gauge | Active ZooKeeper/Keeper sessions |
| `ClickHouseMetrics_ZooKeeperRequest` | gauge | ZooKeeper/Keeper requests in flight |

## OS / CPU

| Metric | Type | Description |
|--------|------|-------------|
| `ClickHouseAsyncMetrics_OSIdleTimeNormalized` | gauge | Fraction of time CPUs are idle (0–1); use `1 - value` for utilization |
| `ClickHouseAsyncMetrics_OSUserTimeNormalized` | gauge | Fraction of CPU time in user space |
| `ClickHouseAsyncMetrics_OSSystemTimeNormalized` | gauge | Fraction of CPU time in kernel space |
| `ClickHouseAsyncMetrics_OSIOWaitTimeNormalized` | gauge | Fraction of CPU time waiting on I/O — elevated value indicates storage bottleneck |
| `ClickHouseAsyncMetrics_OSContextSwitches` | gauge | Context switches per second |
| `ClickHouseAsyncMetrics_OSMemoryTotal` | gauge | Total physical memory on the host |
| `ClickHouseAsyncMetrics_OSMemoryAvailable` | gauge | Available memory (free + reclaimable) |

## Usage Notes

- **`ClickHouseProfileEvents_*_total` are counters** — always wrap with `rate()` (e.g. `rate(ClickHouseProfileEvents_Query_total[5m])`). Using the raw value is meaningless.
- **`ClickHouseAsyncMetrics_*` and `ClickHouseMetrics_*` are gauges** — query directly with no `rate()`.
- **`MaxPartCountForPartition` > 100** means inserts are being throttled; > 300 means inserts are being rejected. This is the first metric to check when diagnosing write performance issues.
- **`RejectedInserts_total` rate > 0** means ClickHouse is actively dropping writes. Reduce insert frequency or increase `background_pool_size`.
- **`ReplicatedDataLoss_total` rate > 0** is a critical alert requiring immediate investigation.
- **`ReadonlyReplica` > 0** means ZooKeeper/Keeper connectivity is broken for one or more replicated tables.
- **S3 throttling** (`S3ReadRequestsThrottling_total` or `S3WriteRequestsThrottling_total` rate > 0) indicates AWS request limits are being hit; check S3 request rates and consider reducing concurrency.
- **Cache hit ratios**: compute as `rate(Hits[5m]) / (rate(Hits[5m]) + rate(Misses[5m]))`. Low uncompressed cache hit rate on a hot workload suggests the cache is undersized.
