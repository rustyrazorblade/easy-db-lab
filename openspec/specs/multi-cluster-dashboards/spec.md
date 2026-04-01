## Requirements

### Requirement: All dashboards have a cluster multi-select variable

Every Grafana dashboard SHALL include a `cluster` template variable (lowercase) that queries all available cluster values from VictoriaMetrics. The variable SHALL support multi-select and SHALL include an "All" option that defaults to all clusters.

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

### Requirement: All metric panel queries are scoped by cluster

Every PromQL query in a VictoriaMetrics-backed panel SHALL include `{cluster=~"$cluster"}` (or equivalent label selector) to scope results to the selected cluster(s).

#### Scenario: Panel query respects cluster selection

- **WHEN** a user selects a specific cluster in the cluster dropdown
- **THEN** all metric panels SHALL display data only from that cluster
- **AND** no data from other clusters SHALL appear in the panels

#### Scenario: System overview hostname cascade is cluster-scoped

- **WHEN** a user opens the system-overview dashboard
- **THEN** the `hostname` variable query SHALL be scoped to the selected cluster
- **AND** hostnames from other clusters SHALL NOT appear in the hostname dropdown

### Requirement: Metric dashboards include an ad hoc filters variable

All VictoriaMetrics-backed dashboards SHALL include a Grafana `adhocfilters` variable pointing to the VictoriaMetrics datasource. This variable SHALL enable runtime filtering by any label present in VictoriaMetrics without requiring those label names to be hardcoded in the dashboard JSON.

#### Scenario: Ad hoc filter variable is present

- **WHEN** a metric dashboard is opened
- **THEN** an ad hoc filter control SHALL appear in the dashboard header

#### Scenario: Ad hoc filters inject into panel queries

- **WHEN** a user adds a label filter via the ad hoc filter control
- **THEN** that filter SHALL be applied to all PromQL queries in the dashboard
- **AND** panels SHALL update to reflect the filtered data

#### Scenario: No external label names are in dashboard JSON

- **WHEN** the source code of any dashboard JSON file is inspected
- **THEN** it SHALL NOT contain label names from closed-source or external tooling
- **AND** label discovery SHALL happen at runtime via the adhocfilters datasource query

### Requirement: No native ClickHouse datasource is provisioned

All dashboard panels use the VictoriaMetrics (prometheus) datasource. No native ClickHouse datasource SHALL be provisioned in Grafana.

#### Scenario: ClickHouse native datasource is not provisioned

- **WHEN** Grafana starts and loads its datasource provisioning
- **THEN** no datasource of type `grafana-clickhouse-datasource` SHALL be present
- **AND** all ClickHouse metric panels SHALL continue to function via the VictoriaMetrics datasource
