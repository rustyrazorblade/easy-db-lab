# Cluster Comparison Dashboard

## Purpose

Provides a Grafana "Cluster Comparison" dashboard that renders side-by-side fleet, throughput, error, latency, activity, percentile, and system-resource views across multiple selected clusters.
## Requirements
### Requirement: Cluster comparison dashboard exists and is registered

A Grafana dashboard named "Cluster Comparison" SHALL exist at `dashboards/cassandra/cluster-comparison.json`, where `GrafanaDashboardCatalog` discovers it and files it in the `cassandra` folder.

#### Scenario: Dashboard is deployed with Grafana

- **WHEN** `grafana update-config` is run on a cluster
- **THEN** the `cluster-comparison.json` dashboard SHALL be provisioned as a ConfigMap and mounted into Grafana
- **AND** it SHALL appear in the Grafana dashboard list under the name "Cluster Comparison"

### Requirement: Dashboard uses shared cluster variable pattern

The dashboard SHALL include the standard `cluster` multi-select variable and `filters` adhocfilters variable following the pattern established in other dashboards.  It SHALL also include the `baseline_cluster` and `candidate_cluster` single-select variables, which replace the former `cluster_a` and `cluster_b`.  They select the two runs for the side-by-side heatmap panels and for the run comparison views.

#### Scenario: Cluster variable scopes all panels

- **WHEN** a user selects one or more clusters in the cluster dropdown
- **THEN** all metric panels that are not part of the run comparison views show data only for the selected clusters
- **AND** each cluster appears as a distinct series, bar, row, or cell depending on panel type

#### Scenario: The run variables replace cluster_a and cluster_b

- **WHEN** the Cluster Comparison dashboard file is inspected
- **THEN** it declares `baseline_cluster` and `candidate_cluster`
- **AND** it declares neither `cluster_a` nor `cluster_b`

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

### Requirement: Comparison dashboards select a baseline run and a candidate run

The dashboards `cluster-comparison`, `ab-comparison` and `system-ab-comparison` SHALL each declare a `baseline_cluster` and a `candidate_cluster` variable.  Each is single-select and lists the clusters of the selected metrics datasource that have `up` samples within a `lookback` variable (custom, default `180d`), evaluated at now and not over the dashboard time range.  The installer SHALL default both to the current cluster.  The dashboard time range SHALL stay relative and end at now.  The run comparison views SHALL filter by cluster only.  The existing `baseline` and `candidate` build variables of `ab-comparison` and host variables of `system-ab-comparison` SHALL stay and SHALL apply only to the existing panels.

#### Scenario: An older run is listed

- **WHEN** an operator opens a comparison dashboard at its default time range and a cluster ran 30 days ago in the selected tenant
- **THEN** that cluster is an option of `baseline_cluster` and of `candidate_cluster`

#### Scenario: Both runs default to the current cluster

- **WHEN** an operator opens an installed comparison dashboard on a running cluster
- **THEN** `baseline_cluster` and `candidate_cluster` both show the current cluster

#### Scenario: The run views ignore the build and host filters

- **WHEN** an operator changes the `baseline` build variable on `ab-comparison`
- **THEN** the overlay, side-by-side and summary views do not change

### Requirement: Comparison views sit in three new top rows with a curated set of figures

Each of the three comparison dashboards SHALL have three new rows at the top: Overlay, Side by side, and Summary with documents.  The existing panels SHALL stay below them.  `cluster-comparison` and `ab-comparison` SHALL show throughput, read p99, write p99, error rate, CPU, disk I/O and GC pause.  `system-ab-comparison` SHALL show CPU, memory, disk and network.

#### Scenario: The curated figures appear in each view

- **WHEN** an operator opens `ab-comparison`
- **THEN** the Overlay, Side by side and Summary rows each show throughput, read p99, write p99, error rate, CPU, disk I/O and GC pause

#### Scenario: The system figures appear on system-ab-comparison

- **WHEN** an operator opens `system-ab-comparison`
- **THEN** the three new rows each show CPU, memory, disk and network

### Requirement: The overlay view shows both runs from a common start

Each run's start and end SHALL be the first and last `up` sample of that cluster in the selected metrics datasource, found with a subquery at a step of about 5 minutes.  Each overlay panel SHALL cover the length of the longer run, ending at now.  Each run SHALL be moved in time by a PromQL offset equal to the axis start minus the run's start, where the axis start is now minus the longer run's length.  The offset MAY be negative.  Both runs SHALL then start at the axis start on one time axis.

#### Scenario: Both runs start together

- **WHEN** an operator selects a baseline and a candidate on a comparison dashboard
- **THEN** the overlay shows both runs from a common start on one time axis

#### Scenario: Runs of different lengths

- **WHEN** the baseline ran 24 hours and the candidate ran 6 hours
- **THEN** the overlay covers 24 hours
- **AND** the candidate's series ends 6 hours after the common start

#### Scenario: The current run is the shorter run

- **WHEN** the candidate is the running current cluster and is shorter than the baseline
- **THEN** the candidate's offset is negative and its series ends at its own length

### Requirement: The side-by-side view shows each run on its own time range

The side-by-side view SHALL have one panel column per run.  Each run's panel SHALL cover that run's length and SHALL be shifted backward from now to the run's end, so its time axis shows the run's real times.

#### Scenario: Each run on its own range

- **WHEN** an operator selects a baseline and a candidate
- **THEN** the baseline panels show the baseline's whole window at its real times
- **AND** the candidate panels show the candidate's whole window at its real times

#### Scenario: Runs of different lengths side by side

- **WHEN** the baseline ran 24 hours and the candidate ran 6 hours
- **THEN** the baseline panels cover 24 hours and the candidate panels cover 6 hours

### Requirement: The summary table shows each run's figures over its own window

The summary table SHALL show, for each curated figure, the baseline's value and the candidate's value, each computed over that run's whole window (a range the length of the run, evaluated at the run's end), and the difference in percent, `100 * (candidate - baseline) / baseline`.

#### Scenario: Figures and the difference in percent

- **WHEN** an operator selects a baseline and a candidate
- **THEN** the summary table shows each run's figures over its own whole window
- **AND** it shows the difference in percent for each figure

#### Scenario: Different lengths in the summary

- **WHEN** the baseline ran 24 hours and the candidate ran 6 hours
- **THEN** the baseline's figures cover 24 hours and the candidate's figures cover 6 hours

### Requirement: Comparison dashboards show both runs' documents next to the summary

Each comparison dashboard SHALL show the documents of the baseline and of the candidate side by side, next to the summary table.  Each side SHALL show the run's `index.html` from `reports/${doc_tenant}/<run>/` through the documents web server.  One `doc_tenant` variable serves both sides.

#### Scenario: Both runs' documents side by side

- **WHEN** a comparison dashboard shows a baseline and a candidate that both have documents
- **THEN** it shows the documents of both runs side by side, next to the summary table

#### Scenario: A run without documents

- **WHEN** the candidate has no uploaded documents and `up` wrote its empty index
- **THEN** the candidate side shows "No documents yet"

