## MODIFIED Requirements

### Requirement: Cluster comparison dashboard exists and is registered

A Grafana dashboard named "Experiment: Cassandra" SHALL exist at `dashboards/cassandra/experiment-cassandra.json`, where `GrafanaDashboardCatalog` discovers it and files it in the `cassandra` folder.  It SHALL keep the uid of the former `cluster-comparison.json`, so existing links still open it.  No file `dashboards/cassandra/cluster-comparison.json` SHALL remain.

#### Scenario: Dashboard is deployed with Grafana
- **WHEN** `grafana update-config` is run on a cluster
- **THEN** `experiment-cassandra.json` is copied into the dashboard tree under `cassandra/`
- **AND** it appears in the Grafana dashboard list under the name "Experiment: Cassandra"

#### Scenario: An old link still opens the dashboard
- **WHEN** an operator opens a link with the former Cluster Comparison uid
- **THEN** Experiment: Cassandra opens

### Requirement: Dashboard uses shared cluster variable pattern

Experiment: Cassandra SHALL include the `experiment` picker, the hidden `exp_tenant`, `exp_name` and `home_tenant` variables, a `cluster` multi-select variable, and the `filters` adhocfilters variable.  The `cluster` variable SHALL list only the clusters of the selected experiment that have `up` samples within `lookback`, including a cluster from before the label when the experiment is its experiment of one.  It SHALL include "All", which SHALL mean every cluster of the experiment, and SHALL default to All.  It SHALL NOT declare `cluster2`, `baseline_cluster`, `candidate_cluster`, `cluster_a` or `cluster_b`.

#### Scenario: Cluster variable scopes all panels
- **WHEN** a user selects one or more clusters in the cluster dropdown
- **THEN** all metric panels show data only for the selected clusters
- **AND** each cluster appears as a distinct series, bar, row, or cell depending on panel type

#### Scenario: The cluster picker offers only the experiment's clusters
- **WHEN** an operator selects an experiment on Experiment: Cassandra
- **THEN** the cluster picker offers only that experiment's clusters
- **AND** any number of them can be selected and compared

#### Scenario: No pair variables remain
- **WHEN** the Experiment: Cassandra dashboard file is inspected
- **THEN** it declares none of `cluster2`, `baseline_cluster`, `candidate_cluster`, `cluster_a` and `cluster_b`

### Requirement: Latency section with multiple representations

Experiment: Cassandra SHALL include three latency panels, each with one series or band per selected cluster:
- Time Series with one p99 line per cluster (histogram aggregate)
- Time Series with p50 and p99 overlaid per cluster
- Range Band showing min/max per-node p99 spread per cluster

The side-by-side Latency Distribution Heatmaps for two clusters SHALL be on the cluster pair, one per side.

#### Scenario: Histogram aggregate p99 is mathematically correct
- **WHEN** the p99 time series panel is viewed
- **THEN** each cluster's p99 line is computed via `histogram_quantile(0.99, sum by (le, cluster) (rate(...)))` which re-aggregates histogram buckets before computing the quantile

#### Scenario: Range band shows per-node spread
- **WHEN** the range band panel is viewed
- **THEN** the filled area for each cluster represents the spread between the highest and lowest per-node p99 within that cluster

#### Scenario: Latency heatmaps are on the pair
- **WHEN** an operator opens the cluster pair with two clusters selected
- **THEN** it shows a latency distribution heatmap for A and one for B, side by side
- **AND** each heatmap's Y axis is latency and its colour is request density

### Requirement: Comparison views sit in three new top rows with a curated set of figures

The cluster pair, `ab-comparison` and `system-ab-comparison` SHALL each have three rows at the top: Overlay, Side by side, and Summary with documents.  The existing panels of `ab-comparison` and `system-ab-comparison` SHALL stay below them.  The cluster pair and `ab-comparison` SHALL show throughput, read p99, write p99, error rate, CPU, disk I/O and GC pause.  `system-ab-comparison` SHALL show CPU, memory, disk and network.  Experiment: Cassandra SHALL NOT have these rows.

#### Scenario: The curated figures appear in each view
- **WHEN** an operator opens `ab-comparison` or the cluster pair
- **THEN** the Overlay, Side by side and Summary rows each show throughput, read p99, write p99, error rate, CPU, disk I/O and GC pause

#### Scenario: The system figures appear on system-ab-comparison
- **WHEN** an operator opens `system-ab-comparison`
- **THEN** the three rows each show CPU, memory, disk and network

### Requirement: The overlay view shows both runs from a common start

Each side's start and end SHALL be the first and last `up` sample of that side's cluster (`cluster_a` or `cluster_b`) in the selected metrics datasource, found with a subquery at a step of about 5 minutes.  Each overlay panel SHALL cover the length of the longer side, ending at now.  Each side SHALL be moved in time by a PromQL offset equal to the axis start minus the side's start, where the axis start is now minus the longer side's length.  The offset MAY be negative.  Both sides SHALL then start at the axis start on one time axis.  Legends SHALL name each side as `A <short name>` and `B <short name>`.

#### Scenario: Both runs start together
- **WHEN** an operator selects cluster A and cluster B on a pair dashboard
- **THEN** the overlay shows both clusters from a common start on one time axis

#### Scenario: Runs of different lengths
- **WHEN** A ran 24 hours and B ran 6 hours
- **THEN** the overlay covers 24 hours
- **AND** B's series ends 6 hours after the common start

#### Scenario: The current run is the shorter run
- **WHEN** B is the running current cluster and is shorter than A
- **THEN** B's offset is negative and its series ends at its own length

### Requirement: The side-by-side view shows each run on its own time range

The side-by-side view SHALL have one panel column per side, A then B.  Each side's panel SHALL cover that cluster's length and SHALL be shifted backward from now to the cluster's end, so its time axis shows the cluster's real times.

#### Scenario: Each run on its own range
- **WHEN** an operator selects cluster A and cluster B
- **THEN** the A panels show A's whole window at its real times
- **AND** the B panels show B's whole window at its real times

#### Scenario: Runs of different lengths side by side
- **WHEN** A ran 24 hours and B ran 6 hours
- **THEN** the A panels cover 24 hours and the B panels cover 6 hours

### Requirement: The summary table shows each run's figures over its own window

The summary table SHALL show, for each curated figure, A's value and B's value, each computed over that cluster's whole window (a range the length of the cluster's run, evaluated at its end), and the difference in percent, `100 * (B - A) / A`.  The columns SHALL be labelled "A", "B" and "B vs A %".  The difference SHALL be empty when A is 0.

#### Scenario: Figures and the difference in percent
- **WHEN** an operator selects cluster A and cluster B
- **THEN** the summary table shows each cluster's figures over its own whole window
- **AND** it shows "B vs A %" for each figure

#### Scenario: Different lengths in the summary
- **WHEN** A ran 24 hours and B ran 6 hours
- **THEN** A's figures cover 24 hours and B's figures cover 6 hours

#### Scenario: A zero A value gives no percentage
- **WHEN** a figure of A is 0
- **THEN** its "B vs A %" cell is empty

### Requirement: Comparison dashboards show both runs' documents next to the summary

The cluster pair, `ab-comparison` and `system-ab-comparison` SHALL show the report of cluster A's experiment and of cluster B's experiment side by side, next to the summary table.  Each side SHALL show `reports/<tenant>/<experiment>/index.html` through the documents web server, with that cluster's own tenant and experiment read from its series.  For a cluster from before the label, the side SHALL show `reports/<tenant>/<name>-<id>/index.html`, with the tenant from Mimir's `__tenant_id__` when present and the home tenant otherwise.

#### Scenario: Both runs' documents side by side
- **WHEN** a pair dashboard shows two clusters whose experiments have reports
- **THEN** it shows both reports side by side, next to the summary table

#### Scenario: A cluster of a named experiment shows that experiment's report
- **WHEN** cluster A belongs to experiment `acme/e1`
- **THEN** the A side shows `reports/acme/e1/index.html`

#### Scenario: A pre-label cluster shows its existing documents
- **WHEN** cluster A is from before the label
- **THEN** the A side shows `reports/<tenant>/<name>-<id>/index.html`

#### Scenario: A run without a report
- **WHEN** cluster B's experiment has no uploaded report and `up` wrote its empty pointer
- **THEN** the B side shows "No report yet"

## ADDED Requirements

### Requirement: Pair dashboards select cluster A and cluster B

The cluster pair (`cluster-pair`), `ab-comparison` and `system-ab-comparison` SHALL each declare the `experiment` picker and two single-select variables, `cluster_a` and `cluster_b`.  They SHALL list the clusters of the selected experiment that have `up` samples within `lookback` (custom, default `180d`), evaluated at now; with experiment "All" they SHALL list every cluster of the tenant view, across tenants on the all-tenants datasource.  `cluster_a` SHALL list oldest first and SHALL have no installed default, so it opens on the experiment's oldest cluster.  `cluster_b` SHALL list newest first; the installer SHALL default it to the current cluster, and in the live local stack it opens on the newest cluster.  The dashboard time range SHALL stay relative and end at now.  The comparison views SHALL filter by cluster only.  On `ab-comparison` and `system-ab-comparison`, the `cluster` multi-select SHALL list only the selected experiment's clusters over the dashboard time range, with "All" as its default.

#### Scenario: The pair pickers offer the experiment's clusters
- **WHEN** an experiment is selected on the cluster pair
- **THEN** `cluster_a` and `cluster_b` offer only that experiment's clusters

#### Scenario: All opens the pickers to every cluster
- **WHEN** experiment "All" is selected on the cluster pair
- **THEN** `cluster_a` and `cluster_b` offer every cluster of the tenant view

#### Scenario: An older run is listed
- **WHEN** an operator opens a pair dashboard at its default time range and a cluster of the experiment ran 30 days ago
- **THEN** that cluster is an option of `cluster_a` and of `cluster_b`

#### Scenario: A defaults to the oldest and B to the current cluster
- **WHEN** an operator opens an installed pair dashboard on a running cluster of an experiment
- **THEN** `cluster_a` shows the experiment's oldest cluster
- **AND** `cluster_b` shows the current cluster

#### Scenario: B defaults to the newest cluster locally
- **WHEN** an operator opens a pair dashboard in the live local stack
- **THEN** `cluster_b` shows the experiment's newest cluster

#### Scenario: The kept dashboards narrow their cluster picker
- **WHEN** an operator selects an experiment on `ab-comparison` or `system-ab-comparison`
- **THEN** the `cluster` multi-select offers only that experiment's clusters and defaults to All

### Requirement: The build comparison keeps working with A/B build pickers

`ab-comparison` SHALL keep its uid and its build comparison.  Its build pickers SHALL be `build_a` and `build_b`, replacing `baseline` and `candidate`, and its `host` picker SHALL stay.  The build and host pickers SHALL apply only to the build comparison panels; the Overlay, Side by side and Summary rows SHALL ignore them.

#### Scenario: The build comparison works as before
- **WHEN** an operator opens `ab-comparison` and selects two builds in `build_a` and `build_b`
- **THEN** the build comparison panels compare those builds as they did before

#### Scenario: The run views ignore the build and host filters
- **WHEN** an operator changes `build_a` on `ab-comparison`
- **THEN** the overlay, side-by-side and summary views do not change

#### Scenario: The old build picker names are gone
- **WHEN** the `ab-comparison` file is inspected
- **THEN** it declares `build_a`, `build_b`, `host`, `cluster_a` and `cluster_b`
- **AND** it declares none of `baseline`, `candidate`, `baseline_cluster` and `candidate_cluster`

### Requirement: The host-group comparison keeps working with A/B host pickers

`system-ab-comparison` SHALL keep its uid and its host-group comparison.  Its host pickers SHALL be `hosts_a` and `hosts_b`, replacing `baseline` and `candidate`, and its `role` picker SHALL stay.  Its link to `ab-comparison` SHALL carry `experiment`, `cluster_a` and `cluster_b`.

#### Scenario: The host-group comparison works as before
- **WHEN** an operator opens `system-ab-comparison` and selects two host groups in `hosts_a` and `hosts_b`
- **THEN** the host-group panels compare those groups as they did before

#### Scenario: The link to the build comparison keeps the pair
- **WHEN** an operator follows the link from `system-ab-comparison` to `ab-comparison`
- **THEN** `ab-comparison` opens with the same experiment, `cluster_a` and `cluster_b`

### Requirement: A cluster pair dashboard compares any two clusters exactly

A core dashboard "Cluster pair", uid `cluster-pair`, at `dashboards/cassandra/cluster-pair.json`, SHALL compare `cluster_a` and `cluster_b` with the Overlay, Side by side and Summary with documents rows, the verdict stats, and the side-by-side latency heatmaps.  It SHALL have no build or host picker.

#### Scenario: Two clusters of one experiment are compared exactly
- **WHEN** an operator selects two clusters of an experiment as A and B
- **THEN** the pair shows both at full resolution on one time axis and the "B vs A %" of each figure

#### Scenario: Two clusters of different tenants are compared
- **WHEN** an operator selects the all-tenants datasource, experiment "All", and clusters of two tenants as A and B
- **THEN** the pair shows both clusters on one panel

### Requirement: Experiment dashboards pick an experiment and open on the newest

Experiment: Cassandra, Experiment: System, the cluster pair, `ab-comparison`, `system-ab-comparison` and Tests SHALL declare a single-select `experiment` variable.  Each option SHALL be one experiment of the tenant view within `lookback`, with value and text `<tenant>/<name>`, ordered by last activity, newest first.  On every dashboard but Tests, a last option "All" SHALL follow.  A cluster from before the label SHALL be an option, as `<tenant>/<name>-<id>`.  With no selection, the dashboard SHALL open on the newest experiment.  "All" SHALL stay available and SHALL be picked by hand; it lists every cluster of the tenant view.  On a cluster, the installer SHALL select the current cluster's experiment.

#### Scenario: The newest experiment opens by default
- **WHEN** an Experiment dashboard opens with nothing selected
- **THEN** it shows the experiment with the most recent activity

#### Scenario: All must be picked
- **WHEN** an operator opens an Experiment dashboard
- **THEN** "All" is the last option and is not selected until the operator picks it

#### Scenario: A link selects an experiment
- **WHEN** a URL carries `var-experiment=acme/e1`
- **THEN** the dashboard opens on experiment `e1` of tenant `acme`

#### Scenario: The installed dashboard opens on the current experiment
- **WHEN** an operator opens an installed Experiment dashboard on a running cluster of experiment `acme/e1`
- **THEN** `experiment` shows `acme/e1`

### Requirement: Experiment dashboards show a per-cluster leaderboard

Each Experiment dashboard SHALL have a leaderboard table with one row per selected cluster.  Each figure SHALL be computed over that cluster's own active window: for Cassandra, the time the cluster served client requests; for System, from its first to its last `up` sample.  The Cassandra leaderboard SHALL show the cluster's short name, its start time in UTC, its active time, throughput, read p99, write p99, error rate, CPU busy, disk I/O, GC pause % and throughput CV.  Values SHALL be raw, with no difference to another cluster.  Every column SHALL sort, the default sort SHALL be throughput descending, and each column SHALL be colour-scaled, reversed for figures where lower is better.  Each row SHALL link to System Overview and Cassandra Overview on that cluster's own window, to the cluster pair with only `cluster_a` set to that cluster, and to the report.

#### Scenario: Each cluster gets one row over its own window
- **WHEN** an operator compares three clusters of an experiment that ran on three different days
- **THEN** the leaderboard has three rows, each computed over that cluster's active window

#### Scenario: The leaderboard sorts by any column
- **WHEN** an operator clicks the Read p99 header
- **THEN** the rows sort by read p99

#### Scenario: A row link opens the pair with that cluster as A
- **WHEN** an operator follows a row's pair link
- **THEN** the cluster pair opens with that cluster as A and B at its default

### Requirement: Experiment dashboards show per-cluster distributions

Each Experiment dashboard SHALL show bar charts with one bar per selected cluster, sorted by short name, of the median, 90th and 99th percentile over each cluster's active window, for throughput, read p99, write p99 and CPU on Experiment: Cassandra, and for the system figures on Experiment: System.

#### Scenario: Distributions show every selected cluster
- **WHEN** an operator selects five clusters of an experiment
- **THEN** each distribution chart has five bars per percentile, one per cluster

### Requirement: Experiment dashboards overlay clusters by elapsed time

Each Experiment dashboard SHALL show Trend panels whose x axis is the hours since each cluster's first request, so clusters run on different days line up from their own start.  Each selected cluster SHALL be one series.

#### Scenario: Clusters from different days line up
- **WHEN** an operator compares a cluster that ran last week with one that ran today
- **THEN** the elapsed-time overlay draws both from hour 0 on one axis

### Requirement: Experiment dashboards show a timeline

Each Experiment dashboard SHALL show, over the absolute time span of the selected clusters, an activity row per cluster and a throughput series per cluster.  Every panel on absolute time SHALL cover the selected clusters' span through a panel time override, while the dashboard time range stays relative.

#### Scenario: The timeline covers the experiment's span
- **WHEN** an experiment's clusters ran over three weeks
- **THEN** the timeline covers those three weeks with one activity row per cluster

### Requirement: Experiment dashboards show the experiment's report

Each Experiment dashboard SHALL show the selected experiment's report, `reports/${experiment}/index.html`, in one iframe through the documents web server.  When "All" is selected the panel SHALL say to select one experiment.

#### Scenario: The report of the selected experiment
- **WHEN** experiment `acme/e1` has a report
- **THEN** the Experiment dashboard shows the newest report of `acme/e1`

#### Scenario: All has no report
- **WHEN** "All" is selected
- **THEN** the report panel tells the operator to select one experiment

### Requirement: The existing sections cover the selected clusters' span

The existing sections of Experiment: Cassandra (fleet snapshot, throughput, errors, latency, activity matrix, percentile ladder, JVM, compaction, system resources and stress) SHALL keep their per-cluster panels and SHALL cover the selected clusters' span through the panel time override.

#### Scenario: A past experiment shows in the existing sections
- **WHEN** an operator selects an experiment whose clusters ran 10 days ago
- **THEN** the throughput section shows each cluster's series at its real times

### Requirement: Experiment: System compares system figures across an experiment

A core dashboard "Experiment: System", uid `experiment-system`, at `dashboards/infrastructure/experiment-system.json`, SHALL have the experiment picker, the `cluster` picker of the experiment's clusters, the leaderboard, distributions, elapsed-time overlay, timeline and report panel with system figures (CPU, memory, disk and network), and the `role` picker.  It SHALL have no host pickers.

#### Scenario: System figures per cluster
- **WHEN** an operator opens Experiment: System on an experiment of three clusters
- **THEN** the leaderboard shows CPU, memory, disk and network for each of the three clusters

#### Scenario: The role picker narrows the hosts
- **WHEN** an operator selects role `db` on Experiment: System
- **THEN** every panel shows only db hosts

## REMOVED Requirements

### Requirement: Comparison dashboards select a baseline run and a candidate run

**Reason**: There is no baseline/candidate model (owner decisions 6 and 8). The pair dashboards select `cluster_a` and `cluster_b`, listed from the selected experiment; Experiment: Cassandra has no pair. The new rule is "Pair dashboards select cluster A and cluster B".

**Migration**: Links that set `var-baseline_cluster` or `var-candidate_cluster` set `var-cluster_a` or `var-cluster_b`. Clusters are ephemeral; nothing migrates.
