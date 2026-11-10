## MODIFIED Requirements

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

## ADDED Requirements

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
