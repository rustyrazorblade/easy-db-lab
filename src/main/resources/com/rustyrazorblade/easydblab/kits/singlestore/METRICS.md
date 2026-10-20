# SingleStore Metrics Catalog

Metrics come from the **exporter sidecar** the SingleStore Operator runs in the master aggregator
pod. It listens on port 9104 and serves `/cluster-metrics`, which reports every node of the
cluster, not just the master: one scrape covers the master aggregator, the child aggregators and
every leaf. The kit runs no `SET GLOBAL exporter_port`; the sidecar needs no setup, and reaches the
cluster with its own operator-made credentials, so the series arrive with any `--password`.

The collector on the master pod's own node finds the pod by Kubernetes pod discovery
(`pod-selector: easydblab/kit=singlestore,easydblab/role=master`) and scrapes it on the pod IP.
The kit registers the scrape on `start` and removes it on `stop`.

## Labels

Every series carries:

| Label | Values | Meaning |
|-------|--------|---------|
| `job` | `singlestore` | The kit's scrape job |
| `instance` | the master pod's name | Set by pod discovery |
| `cluster` | `<name>-<id>` | The easy-db-lab cluster, added by the collector |
| `host` | a SingleStore node's host name | The node the series describes |
| `node_id` | a number | SingleStore's node id |
| `role` | `MA`, `CA`, `LEAF` | Master aggregator, child aggregator, leaf |
| `cluster_name` | `singlestore` | The exporter's name for the SingleStore cluster |

The exporter's `cluster_name` is the SingleStore cluster, not the easy-db-lab cluster. The kit's
dashboards aggregate it away and write their own `cluster_name`, the short easy-db-lab cluster
name, for legends.

---

> **Provisional.** The spike confirmed the endpoint (HTTP 200, about 720 metric names) and the
> labels above. The metric names below follow the exporter's naming (`memsql_status_<variable>` for
> each `SHOW GLOBAL STATUS EXTENDED` variable, lowercased) and are what the kit's dashboards read.
> The real-cluster test (tasks.md 10.7) exports `metrics-catalog.json` with
> `bin/export-workload-metrics singlestore` and corrects this list and the dashboards against it.

## Health

| Metric | Labels | Description |
|--------|--------|-------------|
| `up` | | 1 while the collector reaches the exporter sidecar (from the scrape, not the exporter) |
| `memsql_status_uptime` | `host`, `node_id`, `role` | Seconds since the node started; one series per node, so a count gives the node count |
| `memsql_exporter_last_scrape_error` | | 1 if the exporter's last read of the cluster failed |

## Queries

| Metric | Labels | Description |
|--------|--------|-------------|
| `memsql_status_queries` | `host`, `node_id`, `role` | Statements run, a counter |
| `memsql_status_successful_read_queries` | `host`, `node_id`, `role` | Read queries that succeeded, a counter |
| `memsql_status_successful_write_queries` | `host`, `node_id`, `role` | Write queries that succeeded, a counter |
| `memsql_status_failed_read_queries` | `host`, `node_id`, `role` | Read queries that failed, a counter |
| `memsql_status_failed_write_queries` | `host`, `node_id`, `role` | Write queries that failed, a counter |
| `memsql_status_rows_returned_by_reads` | `host`, `node_id`, `role` | Rows reads returned, a counter |
| `memsql_status_rows_affected_by_writes` | `host`, `node_id`, `role` | Rows writes changed, replication included, a counter |

## Connections

| Metric | Labels | Description |
|--------|--------|-------------|
| `memsql_status_threads_connected` | `host`, `node_id`, `role` | Open connections |
| `memsql_status_threads_running` | `host`, `node_id`, `role` | Threads executing a statement |
| `memsql_status_connections` | `host`, `node_id`, `role` | Connection attempts, a counter |
| `memsql_status_aborted_connects` | `host`, `node_id`, `role` | Failed connection attempts, a counter |

## Memory

| Metric | Labels | Description |
|--------|--------|-------------|
| `memsql_status_total_server_memory` | `host`, `node_id`, `role` | Memory the node process holds, in MB |
| `memsql_status_alloc_table_memory` | `host`, `node_id`, `role` | Memory rowstore tables use, in MB |
| `memsql_status_buffer_manager_memory` | `host`, `node_id`, `role` | Memory the buffer manager holds, in MB |

## Partitions and replication

| Metric | Labels | Description |
|--------|--------|-------------|
| `memsql_distributed_partitions_online` | `database_name`, `role` | Master partitions of the database that are online |
| `memsql_distributed_partitions_offline` | `database_name`, `role` | Master partitions of the database that are offline |
| `memsql_distributed_partitions_total` | `database_name`, `role` | Master partitions of the database |
| `memsql_distributed_partitions_has_unavailable` | `host`, `ws_id`, `ws_name` | 1 while any partition of the cluster is unavailable, 0 otherwise |

The partition counts are per database, not per node: they carry no `host`, and `role` is the
partition's role, `Master`, not the node role (`MA`, `CA`, `LEAF`) the other series carry. The
exporter reports no replica partitions and no per-leaf partition count. `has_unavailable` is one
cluster-wide flag; its `host` names one node only, not the node holding the unavailable
partition, so read it with `max by (cluster)`, never by `host`.

## Disk

SingleStore's data directory, its tracelogs and the pods' logs are on the db node's `/mnt/db1`
mount. The overview dashboard reads its use from the host metrics every node reports:

```promql
sum by (cluster, host_name) (system_filesystem_usage_bytes{state="used", mountpoint="/mnt/db1", host_name=~"db[0-9]+"})
```

## Queries to start from

Queries per second, by role:

```promql
sum by (cluster, role) (rate(memsql_status_queries{job="singlestore"}[1m]))
```

Leaves the exporter reports:

```promql
count by (cluster) (memsql_status_uptime{job="singlestore", role="LEAF"})
```
