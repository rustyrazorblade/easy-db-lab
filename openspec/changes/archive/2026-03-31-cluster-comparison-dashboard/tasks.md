## 1. Dashboard JSON — scaffold and variables

- [x] 1.1 Create `dashboards/cluster-comparison.json` with title "Cluster Comparison", schemaVersion 38, and standard variable block: `datasource` (prometheus type), `cluster` (multi-select, `label_values(up, cluster)`), `filters` (adhocfilters, uid=VictoriaMetrics, applyMode=auto, baseFilters=[])
- [x] 1.2 Add a second cluster variable `cluster2` (single-select, same query) for the side-by-side heatmap panels

## 2. Section 1 — Fleet Snapshot

- [x] 2.1 Add Polystat panel "Write p99 per Cluster" — query: `histogram_quantile(0.99, sum by (le, cluster) (rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_bucket", cluster=~"$cluster"}[$__rate_interval])))` — thresholds: green <5ms, yellow <20ms, red ≥20ms
- [x] 2.2 Add Polystat panel "Read p99 per Cluster" — same pattern for read bucket metrics
- [x] 2.3 Add Table panel "Cluster Summary" — four queries (write ops/s, write p99, read p99, error rate), one column each, merged by cluster label, threshold cell coloring

## 3. Section 2 — Throughput

- [x] 3.1 Add Time Series panel "Write Throughput" — `sum by (cluster) (rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_write_.+_count", cluster=~"$cluster"}[$__rate_interval]))` — one line per cluster
- [x] 3.2 Add Time Series panel "Read Throughput" — same for read count metrics
- [x] 3.3 Add Bar Chart panel "Current Write Throughput" — same query as 3.1, visualization type `barchart`, last value only

## 4. Section 3 — Errors

- [x] 4.1 Add Time Series panel "Write Failures + Timeouts" — two queries: failures (`_failures_`) and timeouts (`_timeouts_`) both `sum by (cluster) (rate(...))`
- [x] 4.2 Add Status History panel "Error Events by Cluster" — query: `sum by (cluster) (rate({__name__=~"org_apache_cassandra_metrics_client_request_failures_(read|write)_.+_total", cluster=~"$cluster"}[$__rate_interval]))` — thresholds: green=0, red>0

## 5. Section 4 — Latency representations

- [x] 5.1 Add Time Series panel "Write p99 per Cluster" — `histogram_quantile(0.99, sum by (le, cluster) (rate({__name__=~"...write.+_bucket"...})))` — one line per cluster
- [x] 5.2 Add Time Series panel "Write p50 + p99 per Cluster" — two queries (p50 and p99), legend includes quantile and cluster, `sum by (le, cluster)`
- [x] 5.3 Add Time Series panel "Write p99 Range (per-node spread)" — two queries:
  - Query A `max {{cluster}}`: `max by (cluster) (histogram_quantile(0.99, sum by (le, cluster, host_name) (rate(...))))`
  - Query B `min {{cluster}}`: same with `min by (cluster)`
  - Add "Fill below to" transform linking max series to min series
- [x] 5.4 Add Heatmap panel "Write Latency Distribution — $cluster" — query: `sum by (le) (rate({__name__=~"...write.+_bucket", cluster="$cluster"}[$__rate_interval]))` — set format to "heatmap", Y axis log scale optional
- [x] 5.5 Add Heatmap panel "Write Latency Distribution — $cluster2" — same query with `cluster="$cluster2"`, placed side by side with 5.4

## 6. Section 5 — Activity Matrix

- [x] 6.1 Add Status History panel "Throughput Activity by Cluster" — query: `sum by (cluster) (rate({__name__=~"org_apache_cassandra_metrics_client_request_latency_(read|write)_.+_count", cluster=~"$cluster"}[$__rate_interval]))` — thresholds: green=low, yellow=medium, blue=high (customize by ops/s range)
- [x] 6.2 Add Status History panel "Error State by Cluster" — query: `sum by (cluster) (rate({__name__=~"org_apache_cassandra_metrics_client_request_failures_(read|write)_.+_total", cluster=~"$cluster"}[$__rate_interval]))` — thresholds: green=0, red>0

## 7. Section 6 — Percentile Ladder

- [x] 7.1 Add Table panel "Latency Percentile Ladder" — five queries (p50, p75, p95, p99, p999) each returning `histogram_quantile(Q, sum by (le, cluster) (rate(...)))`, apply "Merge" transformation, rename columns to "p50"/"p75"/"p95"/"p99"/"p999", add threshold-based cell coloring per column

## 8. Section 7 — System Resources

- [x] 8.1 Add Time Series panel "CPU Usage by Cluster" — `avg by (cluster) (1 - rate(system_cpu_time_seconds_total{state="idle", cluster=~"$cluster"}[$__rate_interval]))` — unit: percent (0-1)
- [x] 8.2 Add Time Series panel "Memory Usage by Cluster" — `avg by (cluster) (system_memory_usage_bytes{state="used", cluster=~"$cluster"})` — unit: bytes
- [x] 8.3 Add Time Series panel "Network Throughput by Cluster" — `sum by (cluster, direction) (rate(system_network_io_bytes_total{cluster=~"$cluster"}[$__rate_interval]))` — unit: bytes/sec

## 9. Register in GrafanaDashboard enum

- [x] 9.1 Add `CLUSTER_COMPARISON` entry to `GrafanaDashboard.kt`:
  ```kotlin
  CLUSTER_COMPARISON(
      configMapName = "grafana-dashboard-cluster-comparison",
      volumeName = "dashboard-cluster-comparison",
      mountPath = "/var/lib/grafana/dashboards/cluster-comparison",
      jsonFileName = "cluster-comparison.json",
      optional = true,
  ),
  ```

## 10. Spec consolidation

- [x] 10.1 Create `openspec/specs/cluster-comparison-dashboard/spec.md` from this change's new capability spec
- [x] 10.2 Update `openspec/specs/observability/spec.md` with the new CLUSTER_COMPARISON enum requirement
