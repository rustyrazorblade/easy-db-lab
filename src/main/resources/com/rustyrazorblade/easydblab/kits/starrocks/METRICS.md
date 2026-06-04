# StarRocks Metrics

All metrics have `job="starrocks"` and `cluster=<cluster-name>`. Metrics are scraped from
the StarRocks FE Prometheus endpoint at port 30830 (NodePort → FE port 8030).

**Note:** Only FE-side metrics are collected. BE-specific metrics (compaction bytes,
scan rows, memory per BE, etc.) are not available without a separate BE scrape target.
The FE does expose a cluster-wide view of some BE state (compaction score, disk capacity,
tablet counts) via its own `/metrics` endpoint.

## Query Throughput

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_fe_query_total` | counter | Total queries executed; use `rate()` |
| `starrocks_fe_request_total` | counter | Total FE requests (includes non-query); use `rate()` |
| `starrocks_fe_query_err` | counter | Failed queries; use `rate()` |
| `starrocks_fe_connection_total` | counter | Cumulative FE connections |
| `starrocks_fe_unfinished_query` | gauge | Currently running queries |

## Query Latency

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_fe_query_latency_ms` | histogram | Query latency with quantile label (0.75, 0.95, 0.99, 0.999) |

**Example:** `starrocks_fe_query_latency_ms{job="starrocks", cluster="$cluster", quantile="0.99"}`

## Transactions

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_fe_txn_begin` | counter | Transaction initiations |
| `starrocks_fe_txn_success` | counter | Successful transactions |
| `starrocks_fe_txn_failed` | counter | Failed transactions |
| `starrocks_fe_txn_reject` | counter | Rejected transactions |
| `starrocks_fe_txn_total_latency_ms` | histogram | End-to-end transaction latency |
| `starrocks_fe_txn_write_latency_ms` | histogram | Write phase latency (prepare→commit) |
| `starrocks_fe_txn_publish_latency_ms` | histogram | Publish phase latency (commit→finish) |

## Load Jobs (FE view)

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_fe_job` | gauge | Job counts by type and state (label: `job`, `type`, `state`) |
| `starrocks_fe_load_add` | counter | Load job submissions |
| `starrocks_fe_load_finished` | counter | Completed loads |
| `starrocks_fe_routine_load_jobs` | gauge | Routine Load jobs by state |
| `starrocks_fe_routine_load_rows` | counter | Total rows loaded by Routine Load |
| `starrocks_fe_routine_load_error_rows` | counter | Error rows in Routine Load |

## Tablet & BE Health (FE view)

These metrics represent the FE's cluster-wide view of BE state — useful for monitoring
compaction backlog, replica health, and data distribution without scraping BE directly.

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_fe_tablet_max_compaction_score` | gauge | Highest compaction score on any BE — the key health indicator |
| `starrocks_fe_tablet_num` | gauge | Tablet count per BE (label: `backend_id`) |
| `starrocks_fe_scheduled_tablet_num` | gauge | Tablets being cloned/rebalanced |
| `starrocks_fe_scheduled_pending_tablet_num` | gauge | Clone tasks in Pending state |
| `starrocks_fe_scheduled_running_tablet_num` | gauge | Clone tasks in Running state |
| `starrocks_fe_report_queue_size` | gauge | Backend report queue depth |

## Storage (FE view of BE disks)

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_be_disks_total_capacity` | gauge | Total disk capacity per BE path (exposed via FE) |
| `starrocks_be_disks_data_used_capacity` | gauge | Disk used by StarRocks data per path |
| `starrocks_be_disks_state` | gauge | Disk health per path (1=active, 0=inactive) |

## FE Metadata & Edit Log

| Metric | Type | Description |
|--------|------|-------------|
| `starrocks_fe_meta_log_count` | gauge | Edit log entries since last checkpoint — high values indicate metadata pressure |
| `starrocks_fe_max_journal_id` | gauge | Highest journal ID (per FE instance) |
| `starrocks_fe_editlog_write_latency_ms` | histogram | Edit log write latency |
| `starrocks_fe_image_write` | counter | Metadata image writes (checkpoints) |

## FE JVM

| Metric | Type | Description |
|--------|------|-------------|
| `jvm_heap_size_bytes` | gauge | JVM heap by state (label: `type=used\|max\|committed`) |
| `jvm_non_heap_size_bytes` | gauge | JVM non-heap by state |
| `jvm_young_gc` | counter | Young GC events (label: `type=count\|time`) |
| `jvm_old_gc` | counter | Old GC events |
| `jvm_thread` | gauge | JVM thread count |

## Usage Notes

- **All `*_total` and event counters**: always wrap with `rate()` (e.g. `rate(starrocks_fe_query_total[5m])`).
- **`starrocks_fe_query_latency_ms`** is a histogram; query specific quantiles via the `quantile` label, e.g. `{quantile="0.99"}`.
- **`starrocks_fe_tablet_max_compaction_score`**: a sustained value above 100 indicates compaction backlog on at least one BE. Values above 200 will start affecting write performance.
- **`starrocks_fe_meta_log_count`** growing without bound means metadata checkpoints are not completing. Normal range is 0–10,000 between checkpoints.
- **`starrocks_be_disks_state` = 0** on any disk path means that disk is offline and tablets on it are unreachable.
