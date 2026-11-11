# memcached Metrics Catalog

Metrics come from the **memcached-exporter** sidecar (`prom/memcached-exporter`), which runs in
the memcached pod and reads `stats` from memcached on `localhost:11211`. It serves Prometheus
metrics on port 9150.

The collector on the pod's own node finds the pod by Kubernetes pod discovery
(`pod-selector: app=memcached`) and scrapes it on the pod IP. Every series carries
`job="memcached"`, `instance=<pod name>`, and `cluster=<cluster name>`.

This list matches the live cluster's `metrics-catalog.json` (`bin/export-workload-metrics memcached`).
The exporter's own Go runtime and process metrics (`go_*`, `process_*`, `promhttp_*`) are also
in the catalog but are not listed here.

Names are as stored in Mimir, which is not always what the exporter documents: some
carry a `_total` suffix the exporter does not. `memcached_slab_mem_requested_bytes_total` is a
gauge despite the suffix; read it directly, not with `rate()`.

---

## Health

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_up` | | 1 if the exporter could reach memcached on the last scrape, else 0 |
| `memcached_uptime_seconds_total` | | Seconds since memcached started |
| `memcached_time_seconds` | | memcached's current wall-clock time, in Unix seconds |
| `memcached_version` | `version` | Constant 1, labelled with the memcached version |
| `memcached_exporter_build_info` | `version`, `revision`, `branch`, `goversion`, `goos`, `goarch`, `tags` | Constant 1, labelled with the exporter's build details |

## Commands and hit ratio

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_commands_total` | `command`, `status` | Commands processed, by command (`get`, `set`, `delete`, `incr`, `decr`, `cas`, `touch`, `flush`) and status (`hit`, `miss`, `badval`) |

Hit ratio: `sum(rate(memcached_commands_total{command="get",status="hit"}[1m])) / sum(rate(memcached_commands_total{command="get"}[1m]))`.

## Memory and items

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_current_bytes` | | Bytes used to store items |
| `memcached_limit_bytes` | | Cache size limit in bytes (the kit's `--memory`, in bytes) |
| `memcached_malloced_bytes` | | Bytes allocated for slab pages |
| `memcached_current_items` | | Items currently stored |
| `memcached_items_total` | | Items stored since start |
| `memcached_items_evicted_total` | | Valid items evicted to free memory |
| `memcached_items_reclaimed_total` | | Expired items whose memory was reused |
| `memcached_direct_reclaims_total` | | Times a worker thread had to reclaim memory itself to store an item |
| `memcached_item_no_memory_total` | | Stores that failed because no memory could be freed |
| `memcached_item_too_large_total` | | Stores rejected because the item exceeded the maximum item size |

## Connections

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_current_connections` | | Open connections |
| `memcached_connections_total` | | Connections accepted since start |
| `memcached_max_connections` | | Connection limit |
| `memcached_accepting_connections` | | 1 if the listener is accepting new connections, else 0 |
| `memcached_connections_rejected_total` | | Connections rejected because the connection limit was hit |
| `memcached_connections_listener_disabled_total` | | Times the listener was disabled because the connection limit was hit |
| `memcached_connections_yielded_total` | | Times a connection yielded after reaching the per-event request limit |

## Network

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_read_bytes_total` | | Bytes read from clients |
| `memcached_written_bytes_total` | | Bytes written to clients |

## Process

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_process_user_cpu_seconds_total` | | User CPU seconds used by memcached |
| `memcached_process_system_cpu_seconds_total` | | System CPU seconds used by memcached |

## LRU crawler

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_lru_crawler_enabled` | | 1 if the LRU crawler is enabled |
| `memcached_lru_crawler_maintainer_thread` | | 1 if the LRU maintainer thread is running |
| `memcached_lru_crawler_sleep` | | Microseconds the crawler sleeps between items |
| `memcached_lru_crawler_to_crawl` | | Maximum items to crawl per slab class per run |
| `memcached_lru_crawler_hot_percent` | | Percent of a slab class's memory reserved for the HOT LRU |
| `memcached_lru_crawler_warm_percent` | | Percent of a slab class's memory reserved for the WARM LRU |
| `memcached_lru_crawler_hot_max_factor` | | HOT items older than this factor of the COLD tail age move to COLD |
| `memcached_lru_crawler_warm_max_factor` | | WARM items older than this factor of the COLD tail age move to COLD |
| `memcached_lru_crawler_starts_total` | | Crawler runs started |
| `memcached_lru_crawler_items_checked_total` | | Items examined by the crawler |
| `memcached_lru_crawler_reclaimed_total` | | Expired items the crawler reclaimed |
| `memcached_lru_crawler_moves_to_cold_total` | | Items moved to the COLD LRU |
| `memcached_lru_crawler_moves_to_warm_total` | | Items moved to the WARM LRU |
| `memcached_lru_crawler_moves_within_lru_total` | | Items bumped within their LRU |

## Slabs

Every slab metric carries a `slab` label with the slab class ID.

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_slab_chunk_size_bytes` | `slab` | Chunk size of the slab class |
| `memcached_slab_chunks_per_page` | `slab` | Chunks per page in the slab class |
| `memcached_slab_current_pages` | `slab` | Pages allocated to the slab class |
| `memcached_slab_current_chunks` | `slab` | Chunks allocated to the slab class |
| `memcached_slab_chunks_used` | `slab` | Chunks holding items |
| `memcached_slab_chunks_free` | `slab` | Chunks free for reuse |
| `memcached_slab_chunks_free_end` | `slab` | Free chunks at the end of the last allocated page |
| `memcached_slab_mem_requested_bytes_total` | `slab` | Bytes requested for items in the slab class (gauge, despite the `_total` suffix) |
| `memcached_slab_current_items` | `slab` | Items stored in the slab class |
| `memcached_slab_hot_items` | `slab` | Items in the HOT LRU |
| `memcached_slab_warm_items` | `slab` | Items in the WARM LRU |
| `memcached_slab_cold_items` | `slab` | Items in the COLD LRU |
| `memcached_slab_items_age_seconds` | `slab` | Age of the oldest item in the slab class |
| `memcached_slab_hot_age_seconds` | `slab` | Age of the oldest item in the HOT LRU |
| `memcached_slab_warm_age_seconds` | `slab` | Age of the oldest item in the WARM LRU |
| `memcached_slab_commands_total` | `slab`, `command`, `status` | Commands processed against the slab class, by command and status |
| `memcached_slab_lru_hits_total` | `slab`, `lru` | Hits, by LRU (`hot`, `warm`, `cold`, `temporary`) |
| `memcached_slab_items_evicted_total` | `slab` | Items evicted from the slab class |
| `memcached_slab_items_evicted_nonzero_total` | `slab` | Evicted items that had an explicit expiry |
| `memcached_slab_items_evicted_unfetched_total` | `slab` | Evicted items that were never fetched |
| `memcached_slab_items_evicted_time_seconds_total` | `slab` | Seconds since the last access of the most recently evicted item |
| `memcached_slab_items_expired_unfetched_total` | `slab` | Expired items that were never fetched |
| `memcached_slab_items_reclaimed_total` | `slab` | Expired items whose memory was reused |
| `memcached_slab_items_crawler_reclaimed_total` | `slab` | Expired items the LRU crawler reclaimed |
| `memcached_slab_items_outofmemory_total` | `slab` | Stores that failed for lack of memory in the slab class |
| `memcached_slab_items_tailrepairs_total` | `slab` | Times an item with a leaked reference was freed from the LRU tail |
| `memcached_slab_items_moves_to_cold_total` | `slab` | Items moved to the COLD LRU |
| `memcached_slab_items_moves_to_warm_total` | `slab` | Items moved to the WARM LRU |
| `memcached_slab_items_moves_within_lru_total` | `slab` | Items bumped within their LRU |

## extstore

With extstore on (`--extstore-size`), the exporter also emits `memcached_extstore_*` series for
the flash tier. They are absent when extstore is off.

The list below comes from a live cluster's catalog (`bin/export-workload-metrics memcached` with
extstore enabled). `memcached_extstore_bytes_used_total` is a gauge despite the `_total` suffix
(the exporter types it as a counter, so the suffix is added on ingest); read it directly, not with
`rate()`.

The exporter does not report memcached's `get_extstore` or `miss_from_extstore` stats. Gets served
from flash are counted by `memcached_extstore_objects_read_total`; the share of get hits served from
flash is `sum(rate(memcached_extstore_objects_read_total[1m])) / sum(rate(memcached_commands_total{command="get",status="hit"}[1m]))`.

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_extstore_bytes_limit` | | Bytes of flash allocated to extstore (the `--extstore-size`) |
| `memcached_extstore_bytes_used_total` | | Bytes holding items in extstore (gauge, despite the `_total` suffix) |
| `memcached_extstore_bytes_fragmented` | | Bytes in allocated extstore pages that do not hold an item |
| `memcached_extstore_bytes_written_total` | | Bytes written to extstore |
| `memcached_extstore_bytes_read_total` | | Bytes read from extstore |
| `memcached_extstore_bytes_evicted_total` | | Bytes evicted from extstore to free space |
| `memcached_extstore_objects_used` | | Items stored in extstore |
| `memcached_extstore_objects_written_total` | | Items written to extstore |
| `memcached_extstore_objects_read_total` | | Items read from extstore (gets served from flash) |
| `memcached_extstore_objects_evicted_total` | | Items evicted from extstore to free space |
| `memcached_extstore_pages_used` | | Extstore pages holding at least one item |
| `memcached_extstore_pages_free` | | Extstore pages not yet holding any item |
| `memcached_extstore_pages_allocated_total` | | Times an extstore page was allocated |
| `memcached_extstore_pages_evicted_total` | | Times an extstore page was evicted |
| `memcached_extstore_pages_reclaimed_total` | | Times an empty extstore page was freed |
| `memcached_extstore_compact_rescued_total` | | Items moved to a new page during compaction |
| `memcached_extstore_compact_skipped_total` | | Items dropped during compaction for inactivity |
| `memcached_extstore_compact_lost_total` | | Items lost during compaction because they were locked |
| `memcached_extstore_io_queue_depth` | | Items waiting in the extstore IO queue |
