# TiDB HTAP Metrics

TiDB exposes Prometheus metrics across four components, each with its own scrape target:

| Component | Job label   | NodePort | Description                              |
|-----------|-------------|----------|------------------------------------------|
| TiDB SQL  | `tidb-sql`  | 31080    | SQL layer — connections, queries, errors |
| PD        | `pd`        | 32379    | Placement Driver — TSO, region scheduling |
| TiKV      | `tikv`      | 32180    | Row storage engine (RocksDB + Raft)      |
| TiFlash   | `tiflash`   | 32234    | Columnar storage engine (MPP)            |

All metrics also carry `cluster=<cluster-name>`.

---

## TiDB SQL Layer (`job="tidb-sql"`)

### Connections & Sessions

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_server_connections` | gauge | Active client connections |
| `tidb_server_internal_sessions` | gauge | Internal sessions (background tasks) |
| `tidb_server_prepared_stmts` | gauge | Prepared statement handles in use |
| `tidb_server_disconnection_total` | counter | Total client disconnections (rate for churn) |
| `tidb_server_handshake_error_total` | counter | Authentication/handshake failures |

### Query Throughput

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_executor_statement_total` | counter | Statements executed, labelled by `type` (Select, Insert, Update, …) |
| `tidb_server_query_total` | counter | Total queries received |
| `tidb_server_execute_error_total` | counter | Query execution errors |
| `tidb_server_critical_error_total` | counter | Critical errors — non-zero rate is an alert |
| `tidb_executor_expensive_total` | counter | Expensive queries (exceeding memory/time threshold) |

### TiFlash Offload (from SQL perspective)

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_server_tiflash_query_total` | counter | Queries that used TiFlash (MPP path) |
| `tidb_server_tiflash_failed_store` | gauge | TiFlash stores the SQL layer considers unhealthy |
| `tidb_executor_mpp_coordinator_stats` | gauge | MPP coordinator state (active tasks, failed tasks) |

### Plan Cache

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_server_plan_cache_total` | counter | Plan cache hits by type |
| `tidb_server_plan_cache_miss_total` | counter | Plan cache misses |
| `tidb_server_plan_cache_instance_plan_num_total` | gauge | Cached plans in memory |

### DDL

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_ddl_waiting_jobs` | gauge | DDL jobs queued; elevated value indicates DDL backlog |
| `tidb_ddl_worker_operation_total` | counter | DDL worker operations completed |

### Memory

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_server_memory_quota_bytes` | gauge | Per-session memory quota |
| `tidb_server_gogc` | gauge | Go GC target percentage |

### TiKV Client (from SQL perspective)

| Metric | Type | Description |
|--------|------|-------------|
| `tidb_tikvclient_region_err_total` | counter | Region errors returned to the SQL layer (retried automatically) |
| `tidb_tikvclient_grpc_connection_state` | gauge | gRPC connection states to TiKV stores |
| `tidb_tikvclient_batch_wait_overload_total` | counter | Batch wait queue overloads (TiKV backpressure) |

---

## PD — Placement Driver (`job="pd"`)

### Cluster Health

| Metric | Type | Description |
|--------|------|-------------|
| `pd_cluster_status{type="store_up_count"}` | gauge | TiKV stores currently up |
| `pd_cluster_status{type="store_down_count"}` | gauge | TiKV stores currently down — non-zero is an alert |
| `pd_cluster_status{type="region_count"}` | gauge | Total Raft regions |
| `pd_cluster_status{type="leader_count"}` | gauge | Raft leaders (should equal region_count for a healthy cluster) |
| `pd_cluster_status{type="storage_capacity"}` | gauge | Total storage capacity across all TiKV stores (bytes) |
| `pd_cluster_status{type="storage_size"}` | gauge | Storage currently used (bytes) |
| `pd_cluster_health_status` | gauge | Per-store health status (1=healthy) |

### TSO (Timestamp Oracle)

| Metric | Type | Description |
|--------|------|-------------|
| `pd_cluster_tso` | counter | TSO allocations — `rate()` for ops/s |
| `pd_cluster_tso_gap_millionseconds` | gauge | Clock skew between PD and the current time (ms); large values indicate NTP issues. Note: "millionseconds" is TiDB's own metric name — the typo is in TiDB's source, not here. |

### Scheduling

| Metric | Type | Description |
|--------|------|-------------|
| `pd_schedule_operators_count_total` | counter | Scheduling operators created (balance-region, balance-leader, …) |
| `pd_schedule_filter_total` | counter | Operators filtered out during scheduling |
| `pd_hotspot_status` | gauge | Hot region/store counts by type |
| `pd_scheduler_hot_region_total` | counter | Hot region scheduling events |

### Region Events

| Metric | Type | Description |
|--------|------|-------------|
| `pd_cluster_region_event_total` | counter | Region heartbeat events received |
| `pd_regions_status` | gauge | Regions in each health state (normal, missing-peer, extra-peer, …) |
| `pd_checker_region_list` | gauge | Regions pending each checker (replica, merge, split, …) |

---

## TiKV — Row Storage Engine (`job="tikv"`)

### Raft & Replication

| Metric | Type | Description |
|--------|------|-------------|
| `tikv_raftstore_leader_missing` | gauge | Regions with no elected leader — non-zero is an alert |
| `tikv_raftstore_hibernated_peer_state` | gauge | Regions in hibernated state (normal — reduces heartbeat load) |
| `tikv_raftstore_local_read_executed_requests_total` | counter | Reads served locally without going through Raft consensus |
| `tikv_raftstore_admin_cmd_total` | counter | Raft admin commands (split, merge, transfer-leader) |

### RocksDB Storage

| Metric | Type | Description |
|--------|------|-------------|
| `tikv_engine_size_bytes` | gauge | Data size by column family (default, write, lock) |
| `tikv_engine_num_files_at_level` | gauge | SST file count per level — high L0 count means compaction lag |
| `tikv_engine_pending_compaction_bytes` | gauge | Bytes waiting for compaction — elevated value indicates I/O backpressure |
| `tikv_engine_estimate_num_keys` | gauge | Estimated key count in the engine |
| `tikv_engine_block_cache_size_bytes` | gauge | RocksDB block cache size currently in use |
| `tikv_engine_stall_micro_seconds_total` | counter | Write stall time — rate > 0 means compaction is blocking writes |
| `tikv_engine_flow_bytes_total` | counter | Bytes read/written through the storage engine (rate for throughput) |

### Coprocessor (Push-down Queries)

| Metric | Type | Description |
|--------|------|-------------|
| `tikv_coprocessor_response_bytes_total` | counter | Bytes returned by coprocessor push-down |
| `tikv_coprocessor_request_error_total` | counter | Coprocessor errors (deadline exceeded, region not found, …) |
| `tikv_coprocessor_scan_details_total` | counter | Rows/keys scanned by coprocessor; high values relative to returned rows indicate full scans |

### Memory & GC

| Metric | Type | Description |
|--------|------|-------------|
| `tikv_engine_memory_bytes` | gauge | Memory used by RocksDB allocations |
| `tikv_allocator_stats` | gauge | jemalloc allocator stats |

---

## TiFlash — Columnar Storage Engine (`job="tiflash"`)

### MPP (Massively Parallel Processing)

| Metric | Type | Description |
|--------|------|-------------|
| `tiflash_mpp_task_monitor` | gauge | Active MPP tasks by state |
| `tiflash_mpp_task_manager` | gauge | MPP task manager state (pending/running tasks) |
| `tiflash_compute_request_unit_total` | counter | Request units consumed by MPP queries — `rate()` for RU/s |
| `tiflash_executor_mpp_coordinator_stats` | gauge | Coordinator-side MPP task stats |

### Data Exchange

| Metric | Type | Description |
|--------|------|-------------|
| `tiflash_exchange_data_bytes_total` | counter | Bytes exchanged between TiFlash nodes during MPP shuffles |
| `tiflash_exchange_queueing_data_bytes` | gauge | Bytes currently queued in exchange pipelines |
| `tiflash_coprocessor_response_bytes_total` | counter | Bytes returned to TiDB for coprocessor requests |
| `tiflash_coprocessor_request_error_total` | counter | Coprocessor errors from TiFlash |

### Storage Throughput

| Metric | Type | Description |
|--------|------|-------------|
| `tiflash_storage_throughput_bytes` | gauge | Bytes read/written at the storage layer |
| `tiflash_storage_throughput_rows` | gauge | Rows read/written at the storage layer |
| `tiflash_raft_throughput_bytes_total` | counter | Bytes replicated from TiKV via Raft |
| `tiflash_raft_process_keys_total` | counter | Keys processed during Raft replication |

### Memory

| Metric | Type | Description |
|--------|------|-------------|
| `tiflash_process_rss_by_type_bytes` | gauge | RSS memory broken down by allocation type |
| `tiflash_memory_usage_by_class` | gauge | Memory by subsystem (storage, executor, etc.) |
| `tiflash_storages_thread_memory_usage` | gauge | Per-thread memory usage |
| `tiflash_system_asynchronous_metric_jemalloc_resident` | gauge | jemalloc resident pages (physical memory) |
| `tiflash_system_asynchronous_metric_jemalloc_allocated` | gauge | jemalloc application-allocated bytes |

### Raft Snapshot Replication

| Metric | Type | Description |
|--------|------|-------------|
| `tiflash_raft_ongoing_snapshot_total_bytes` | gauge | Bytes being replicated in active snapshots from TiKV |
| `tiflash_sync_schema_applying` | gauge | Schema sync state (1 = syncing) |
| `tiflash_fap_task_result_total` | counter | Fast-path (FAP) snapshot task results |

---

---

## Traces

### Why TiDB uses OpenTracing (not OTel)

TiDB v8.x has **no native OpenTelemetry export support**. The `opentelemetry.*` config keys do not exist — TiDB rejects them at startup with "config file contained invalid configuration options". This is not a configuration mistake; the feature simply does not exist in this version.

TiDB v8.x ships the OpenTracing API with a **Jaeger UDP reporter** (`[opentracing]` config section). To get traces into Tempo, the OTel Collector acts as a bridge:

```
TiDB pod
  └─ [opentracing] reporter (Jaeger Thrift Compact, UDP)
       └─ otel-collector.default.svc.cluster.local:6831
            └─ OTel Collector jaeger receiver
                 └─ OTLP → Tempo
```

### TiDB Trace Configuration

The `tidbcluster.yaml.template` configures TiDB's opentracing reporter:

```toml
[opentracing]
  enable = true
[opentracing.sampler]
  type = "const"
  param = 1.0
[opentracing.reporter]
  local-agent-host-port = "otel-collector.default.svc.cluster.local:6831"
```

`type = "const"` with `param = 1.0` means 100% sampling — all SQL requests produce a trace. Reduce `param` (e.g. `0.1` for 10%) on high-throughput clusters to limit Tempo ingestion volume.

### OTel Collector Jaeger Receiver

The OTel Collector DaemonSet listens on UDP port 6831 (Jaeger Thrift Compact) on all nodes. The K8s Service `otel-collector.default.svc.cluster.local` exposes this port as UDP so TiDB pods can reach it via DNS without knowing the node IP.

The Jaeger receiver is included in the `traces` pipeline alongside `otlp`, so TiDB traces flow through the same `spanmetrics` and `servicegraph` connectors as all other traces.

### Tempo Service Name

TiDB registers itself as **`TiDB`** (capital T) in Tempo. Tag search is case-sensitive — queries must use `service.name=TiDB`, not `tidb`.

### In Grafana

Once traces are flowing, TiDB log rows in any VictoriaLogs panel automatically show a **"View Trace in Tempo"** button when a log entry contains a `trace_id` field. This is configured globally on the VictoriaLogs datasource via `derivedFields` in `GrafanaDatasourceConfig.kt` — no per-dashboard configuration needed.

---

## Usage Notes

- **Counters** (`_total` suffix) must be wrapped in `rate()` or `increase()` — raw values are meaningless.
- **Gauges** are queried directly; no `rate()` needed.
- **`pd_cluster_status{type="store_down_count"} > 0`** — critical alert; TiKV data is at risk.
- **`tikv_raftstore_leader_missing > 0`** — critical alert; affected regions are unavailable.
- **`tikv_engine_stall_micro_seconds_total` rate > 0** — writes are being blocked by compaction; check `pending_compaction_bytes`.
- **`tidb_server_critical_error_total` rate > 0** — internal TiDB error, requires immediate investigation.
- **MPP query health**: watch `tiflash_mpp_task_monitor` for stuck tasks and `tiflash_coprocessor_request_error_total` rate for errors.
- **TSO skew** (`pd_cluster_tso_gap_millionseconds`): values above a few hundred ms indicate NTP drift on the PD node.
- **Storage usage**: `pd_cluster_status{type="storage_size"} / pd_cluster_status{type="storage_capacity"}` gives fill percentage; alert above 70%.
