# memcached Metrics Catalog

Metrics come from the **memcached-exporter** sidecar (`prom/memcached-exporter`), which runs in
the memcached pod and reads `stats` from memcached on `localhost:11211`. It serves Prometheus
metrics on port 9150.

The collector on the pod's own node finds the pod by Kubernetes pod discovery
(`pod-selector: app=memcached`) and scrapes it on the pod IP. Every series carries
`job="memcached"`, `instance=<pod name>`, and `cluster=<cluster name>`.

This list is from the exporter's documented metric set. It is refreshed from a live cluster's
`metrics-catalog.json` (`bin/export-workload-metrics memcached`).

---

## Health

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_up` | | 1 if the exporter could reach memcached on the last scrape, else 0 |
| `memcached_uptime_seconds` | | Seconds since memcached started |
| `memcached_version` | `version` | Constant 1, labelled with the memcached version |

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
| `memcached_current_items` | | Items currently stored |
| `memcached_items_total` | | Items stored since start |
| `memcached_items_evicted_total` | | Valid items evicted to free memory |
| `memcached_items_reclaimed_total` | | Expired items whose memory was reused |
| `memcached_malloced_bytes` | | Bytes allocated for slab pages |

## Connections

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_current_connections` | | Open connections |
| `memcached_connections_total` | | Connections accepted since start |
| `memcached_max_connections` | | Connection limit |
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
| `memcached_threads` | | Worker threads |

## Slabs

| Metric | Labels | Description |
|--------|--------|-------------|
| `memcached_slab_current_items` | `slab` | Items stored in the slab class |
| `memcached_slab_chunk_size_bytes` | `slab` | Chunk size of the slab class |
| `memcached_slab_current_chunks` | `slab` | Chunks allocated to the slab class |
| `memcached_slab_items_evicted_total` | `slab` | Items evicted from the slab class |
| `memcached_slab_mem_requested_bytes` | `slab` | Bytes requested for items in the slab class |
