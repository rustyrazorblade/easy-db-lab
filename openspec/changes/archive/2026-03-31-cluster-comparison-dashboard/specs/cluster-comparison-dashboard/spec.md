## ADDED Requirements

### Requirement: Cluster comparison dashboard exists and is registered

A Grafana dashboard named "Cluster Comparison" SHALL exist at `dashboards/cluster-comparison.json` and SHALL be registered in the `GrafanaDashboard` enum as an optional entry named `CLUSTER_COMPARISON`.

#### Scenario: Dashboard is deployed with Grafana

- **WHEN** `grafana update-config` is run on a cluster
- **THEN** the `cluster-comparison.json` dashboard SHALL be provisioned as a ConfigMap and mounted into Grafana
- **AND** it SHALL appear in the Grafana dashboard list under the name "Cluster Comparison"

### Requirement: Dashboard uses shared cluster variable pattern

The dashboard SHALL include the standard `cluster` multi-select variable and `filters` adhocfilters variable following the pattern established in other dashboards.

#### Scenario: Cluster variable scopes all panels

- **WHEN** a user selects one or more clusters in the cluster dropdown
- **THEN** all metric panels SHALL display data only for the selected clusters
- **AND** each cluster SHALL appear as a distinct series, bar, row, or cell depending on panel type

### Requirement: Fleet snapshot section

The dashboard SHALL include a fleet snapshot section with a Polystat panel and a summary Table panel showing current-state metrics per cluster.

#### Scenario: Polystat shows per-cluster write latency p99

- **WHEN** the fleet snapshot section is viewed
- **THEN** the Polystat panel SHALL show one cell per cluster colored by write latency p99 threshold
- **AND** the Table panel SHALL show columns for write throughput, write p99, read p99, and error rate per cluster

### Requirement: Throughput section

The dashboard SHALL include a throughput section with a Time Series panel (one line per cluster) and a Bar Chart panel (current snapshot).

#### Scenario: Time series shows one line per cluster

- **WHEN** the throughput time series panel is viewed
- **THEN** each selected cluster SHALL appear as a distinct line
- **AND** the query SHALL use `sum by (cluster) (rate(...))` to aggregate across nodes within each cluster

### Requirement: Error section

The dashboard SHALL include an error section with a Time Series panel and a Status History panel showing error events per cluster over time.

#### Scenario: Status history shows error windows

- **WHEN** the error status history panel is viewed
- **THEN** each cluster SHALL occupy one row
- **AND** time segments with non-zero errors SHALL be colored differently from zero-error segments

### Requirement: Latency section with multiple representations

The dashboard SHALL include four latency panels demonstrating distinct approaches:
- Time Series with one p99 line per cluster (histogram aggregate)
- Time Series with p50 and p99 overlaid per cluster
- Range Band showing min/max per-node p99 spread per cluster
- Latency Distribution Heatmap (one per cluster, side by side for two clusters)

#### Scenario: Histogram aggregate p99 is mathematically correct

- **WHEN** the p99 time series panel is viewed
- **THEN** each cluster's p99 line SHALL be computed via `histogram_quantile(0.99, sum by (le, cluster) (rate(...)))` which re-aggregates histogram buckets before computing the quantile

#### Scenario: Range band shows per-node spread

- **WHEN** the range band panel is viewed
- **THEN** the filled area for each cluster SHALL represent the spread between the highest and lowest per-node p99 within that cluster

#### Scenario: Latency heatmap shows distribution shape

- **WHEN** the latency heatmap panel is viewed
- **THEN** the Y axis SHALL represent latency values (from histogram bucket boundaries)
- **AND** color intensity SHALL represent request density at that latency bucket over time
- **AND** GC pause events SHALL be visible as a transient band of high-latency requests

### Requirement: Activity matrix section

The dashboard SHALL include a Status History panel showing throughput level per cluster and a Status History panel showing error state per cluster, both using X=time, Y=cluster layout.

#### Scenario: Activity matrix shows all clusters simultaneously

- **WHEN** the activity matrix section is viewed
- **THEN** each row SHALL correspond to one cluster
- **AND** time ranges with different throughput levels SHALL be colored by threshold

### Requirement: Percentile ladder section

The dashboard SHALL include a Table panel showing p50, p75, p95, p99, and p999 as columns with one row per cluster, with threshold-based cell coloring.

#### Scenario: Percentile ladder shows full distribution

- **WHEN** the percentile ladder panel is viewed
- **THEN** each cluster SHALL have exactly one row
- **AND** each quantile column SHALL show the current value computed via `histogram_quantile`
- **AND** cells SHALL be colored green/yellow/red by latency threshold

### Requirement: System resources section

The dashboard SHALL include Time Series panels for average CPU usage, memory usage, and network I/O per cluster.

#### Scenario: System panels aggregate across nodes within each cluster

- **WHEN** a system resource panel is viewed
- **THEN** each cluster SHALL appear as one line representing the average across all nodes in that cluster
