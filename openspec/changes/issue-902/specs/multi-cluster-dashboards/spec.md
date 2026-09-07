# Multi Cluster Dashboards Spec

## MODIFIED Requirements

### Requirement: All dashboards have a cluster multi-select variable

Every Grafana dashboard SHALL include a `cluster` template variable (lowercase) that queries all available cluster values from VictoriaMetrics. The variable SHALL support multi-select and SHALL include an "All" option that defaults to all clusters.

This applies to every dashboard in the repository's top-level `dashboards/` directory and to every
kit dashboard, **with no exclusions**, `dashboards/profiling.json` included. Without the field, two
clusters in one aggregate render as one series. The metrics, logs and profiles already carry the
cluster name; supplying a unique one is the user's responsibility.

Profiles carry `cluster` with the same `clusterLabelName()` value metrics and logs use, so
`dashboards/profiling.json` is in scope on the same terms as every other dashboard. Its variable is
a Pyroscope one rather than a VictoriaMetrics one, so the variable's datasource follows the panels
it scopes.

#### Scenario: Cluster variable present on all dashboards

- **WHEN** any dashboard is opened in Grafana
- **THEN** a `cluster` variable SHALL appear in the dashboard header
- **AND** the variable SHALL be populated by querying `label_values(up, cluster)` against the VictoriaMetrics datasource

#### Scenario: Single-cluster deployment shows one option

- **WHEN** VictoriaMetrics contains metrics from exactly one cluster
- **THEN** the `cluster` dropdown SHALL show exactly one value
- **AND** the dashboard SHALL display data for that cluster without requiring manual selection

#### Scenario: Multi-cluster deployment shows all clusters

- **WHEN** VictoriaMetrics contains metrics from multiple clusters
- **THEN** the `cluster` dropdown SHALL show all distinct cluster values
- **AND** selecting "All" SHALL aggregate metrics across all clusters

#### Scenario: Cluster selection is URL-addressable

- **WHEN** a URL includes `?var-cluster=<name>`
- **THEN** Grafana SHALL pre-select the specified cluster in the dropdown
- **AND** all dashboard panels SHALL display data scoped to that cluster

#### Scenario: Every dashboard in the repository exposes the field

- **WHEN** every dashboard in the top-level `dashboards/` directory and every kit dashboard is checked
- **THEN** each one exposes a cluster-name field
- **AND** no dashboard is excluded, `dashboards/profiling.json` included

#### Scenario: Live-cluster behaviour is unchanged

- **WHEN** these dashboards are deployed to a live cluster by `grafana update-config`
- **THEN** they behave as they did before for a single cluster

### Requirement: All metric panel queries are scoped by cluster

Every PromQL query in a VictoriaMetrics-backed panel SHALL include `{cluster=~"$cluster"}` (or equivalent label selector) to scope results to the selected cluster(s).

Panels backed by other datasources SHALL be scoped by that datasource's own equivalent. In
particular, a Pyroscope panel carries its cluster filter in its `labelSelector`, not in an `expr`.

The enforcement test SHALL walk `labelSelector` fields as well as `expr` fields. A test that walks
only `expr` passes `dashboards/profiling.json` while it silently blends two clusters into one flame
graph — which is also why an earlier survey reported that dashboard as "0 of 0 queries".

#### Scenario: Panel query respects cluster selection

- **WHEN** a user selects a specific cluster in the cluster dropdown
- **THEN** all metric panels SHALL display data only from that cluster
- **AND** no data from other clusters SHALL appear in the panels

#### Scenario: System overview hostname cascade is cluster-scoped

- **WHEN** a user opens the system-overview dashboard
- **THEN** the `hostname` variable query SHALL be scoped to the selected cluster
- **AND** hostnames from other clusters SHALL NOT appear in the hostname dropdown

#### Scenario: The enforcement test walks labelSelector fields

- **WHEN** the enforcement test scans a dashboard for unscoped queries
- **THEN** it walks `labelSelector` fields as well as `expr` fields
- **AND** a Pyroscope panel whose `labelSelector` carries no cluster filter fails the test

#### Scenario: Two clusters in one aggregate do not blend

- **GIVEN** an aggregate holding two clusters
- **WHEN** a dashboard is opened and one cluster is selected
- **THEN** only that cluster's series are shown
- **AND** no series from the other cluster is blended into a panel
