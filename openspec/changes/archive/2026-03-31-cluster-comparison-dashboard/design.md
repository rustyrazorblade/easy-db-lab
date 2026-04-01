## Context

The existing Cassandra dashboards (cassandra-overview, cassandra-condensed) are single-cluster views: variables cascade from cluster → datacenter → rack → node, scoping every panel to one cluster at a time. With the `cluster` variable now supporting multi-select, a purpose-built comparison dashboard can show all selected clusters simultaneously using `sum by (cluster)` and `histogram_quantile(..., sum by (le, cluster) ...)` aggregations.

All Cassandra latency metrics are proper Prometheus histograms exposed by the MAAC exporter. The metric name pattern is:
```
{__name__=~"org_apache_cassandra_metrics_client_request_latency_(read|write)_.+_bucket"}
```
This means `histogram_quantile` at any grouping level (per-node, per-cluster, fleet) is mathematically valid. Throughput uses the `_count` suffix of the same family; errors use separate `_failures_` and `_timeouts_` metrics.

System metrics (`system_cpu_time_seconds_total`, `system_memory_usage_bytes`, `system_network_io_bytes_total`) carry the `cluster` label from OTel relabeling, enabling resource comparison across clusters.

## Goals / Non-Goals

**Goals:**
- One dashboard that exercises every distinct visualization approach for cross-cluster comparison
- All panels scope to `cluster=~"$cluster"` so the existing multi-select variable controls the view
- Each section is self-contained; users can identify which panel types suit their workflow
- Dashboard registered as optional in the `GrafanaDashboard` enum

**Non-Goals:**
- ClickHouse or OpenSearch comparison (separate concern, separate dashboards if needed)
- Drill-down into individual nodes (the per-database dashboards cover this)
- Alerting rules

## Decisions

### Decision: Seven sections, one panel type per purpose

Each section demonstrates a distinct visualization approach. Labels in the dashboard describe what each panel type is best for.

| Section | Panel Types | Purpose |
|---|---|---|
| 1. Fleet Snapshot | Polystat, Table | Current health at a glance across many clusters |
| 2. Throughput | Time Series, Bar Chart | Trends and current-state ranking |
| 3. Errors | Time Series, Status History | Error trends and windowed incident history |
| 4. Latency | Time Series (p99), Time Series (p50+p99), Range Band, Heatmap | Full latency representation spectrum |
| 5. Activity Matrix | Status History (throughput), Status History (errors) | Which cluster was doing what, when |
| 6. Percentile Ladder | Table with multiple queries | Full distribution shape per cluster |
| 7. System Resources | Time Series (CPU, memory, network) | Infrastructure correlation |

### Decision: Range band via two queries with fill-between transform

Grafana's time series panel supports "fill between" two named series. The range band uses:
- Query A (legend `max {{cluster}}`): `max by (cluster) (histogram_quantile(0.99, sum by (le, cluster, host_name) (rate(...))))`
- Query B (legend `min {{cluster}}`): `min by (cluster) (...)`
- Transform: "Fill below to" referencing the min series

This shows the spread of per-node p99 within each cluster without requiring a plugin.

### Decision: Latency heatmap uses `sum by (le)` scoped to one cluster

Heatmaps require the `le` label to remain in the result so Grafana can map buckets to Y-axis values. The query groups by `le` only, scoped to a single cluster value:
```promql
sum by (le) (rate({__name__=~"...write.+_bucket", cluster="$cluster"}[$__rate_interval]))
```
Since a heatmap can only meaningfully show one distribution at a time, the section includes two side-by-side heatmap panels that repeat the pattern for a second cluster variable (using a separate variable `$cluster2` defaulting to the same set). This lets users compare distribution shapes between two specific clusters.

### Decision: Percentile ladder via multiple queries + Merge transform

Five separate `histogram_quantile` queries (p50, p75, p95, p99, p999) each return one series per cluster. Grafana's "Merge" transformation joins them into a table where each cluster is a row and each quantile is a column. Threshold-based cell coloring is applied per column.

### Decision: Status History for activity matrix

The Status History panel (not Heatmap) is used for the activity matrix because:
- Renders cluster names on the Y axis directly from series labels
- Supports threshold-based color coding (green/yellow/red)
- Handles gaps in data gracefully
- Works cleanly with `sum by (cluster) (rate(...))` multi-series queries

### Decision: `GrafanaDashboard.CLUSTER_COMPARISON` with `optional = true`

Follows the existing pattern for all non-system dashboards. The JSON file name is `cluster-comparison.json`.

## Risks / Trade-offs

- **Heatmap for two clusters is fixed** — only two comparison slots. More flexible repeat-by-variable heatmaps require Grafana panel repeat which complicates the static JSON. Two panels side by side is the practical limit for readable heatmaps.
- **Percentile ladder is compute-heavy** — five `histogram_quantile` queries per refresh. Acceptable at dashboard load time; not suitable for very short refresh intervals.
- **Status History thresholds are hardcoded** — latency thresholds (green <5ms, yellow <20ms, red >20ms) are baked into the dashboard JSON. Different workloads have different acceptable ranges. Users can override via Grafana UI but cannot parameterize thresholds.
