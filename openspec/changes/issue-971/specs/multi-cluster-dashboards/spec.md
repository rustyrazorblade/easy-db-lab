## MODIFIED Requirements

### Requirement: All dashboards have a cluster multi-select variable

Every dashboard that has a `cluster` template variable (lowercase) SHALL populate it with `label_values(up, cluster)` on the dashboard's metrics datasource picker, `${metrics_datasource}`, and never on a fixed datasource.  The variable SHALL support multi-select and SHALL include an "All" option.  The Tests dashboard is the one exception: its `cluster` variable is single-select, because it selects one test.  The dashboard file SHALL store no default for the variable.  The installer SHALL set the default to the current cluster, as the requirement "Installed dashboards default to the current cluster" states.  "All" SHALL NOT be the default.  The dashboards that have no `cluster` variable today are tracked by issue #983 and are not covered by this requirement.

#### Scenario: Cluster variable reads the selected metrics tenant

- **WHEN** an operator opens a dashboard that has a `cluster` variable
- **THEN** a `cluster` variable appears in the dashboard header
- **AND** the variable is populated by `label_values(up, cluster)` against the datasource that the metrics picker selects

#### Scenario: Single-cluster deployment shows one option

- **WHEN** the selected metrics datasource holds metrics from exactly one cluster
- **THEN** the `cluster` dropdown shows exactly one value
- **AND** the dashboard shows data for that cluster without a selection by hand

#### Scenario: Multi-cluster deployment shows all clusters

- **WHEN** the selected metrics datasource holds metrics from more than one cluster
- **THEN** the `cluster` dropdown shows every distinct cluster value
- **AND** selecting "All" aggregates metrics across all of those clusters

#### Scenario: Cluster selection is URL-addressable

- **WHEN** a URL includes `?var-cluster=<name>`
- **THEN** Grafana selects that cluster in the dropdown
- **AND** every dashboard panel shows data scoped to that cluster

#### Scenario: All tenants and All clusters show every cluster

- **WHEN** an operator selects the "all tenants" datasource in each picker and "All" in the `cluster` variable
- **THEN** the dashboard shows the data of every cluster of every tenant
- **AND** no query of the dashboard changes

### Requirement: Metric dashboards include an ad hoc filters variable

All metric dashboards SHALL include a Grafana `adhocfilters` variable whose datasource is the metrics datasource picker, `${metrics_datasource}`.  This variable SHALL enable runtime filtering by any label present in the selected metrics datasource without requiring those label names to be hardcoded in the dashboard JSON.

#### Scenario: Ad hoc filter variable is present

- **WHEN** a metric dashboard is opened
- **THEN** an ad hoc filter control appears in the dashboard header

#### Scenario: Ad hoc filters inject into panel queries

- **WHEN** a user adds a label filter through the ad hoc filter control
- **THEN** that filter is applied to every PromQL query in the dashboard
- **AND** the panels update to show the filtered data

#### Scenario: Ad hoc filters follow the metrics picker

- **WHEN** a user selects another tenant in the metrics picker
- **THEN** the ad hoc filter control offers the labels of that tenant's datasource

#### Scenario: No external label names are in dashboard JSON

- **WHEN** the source code of any dashboard JSON file is inspected
- **THEN** it does not contain label names from closed-source or external tooling
- **AND** label discovery happens at runtime through the adhocfilters datasource query

## ADDED Requirements

### Requirement: Every dashboard has a datasource picker for each signal it uses

Every core dashboard (`dashboards/`) and every kit dashboard (`kits/*/dashboards/`) SHALL declare one Grafana datasource variable for each datasource type that the dashboard uses:

- `metrics_datasource`, label "Metrics", type `prometheus`;
- `logs_datasource`, label "Logs", type `loki`;
- `traces_datasource`, label "Traces", type `tempo`.

The former Prometheus variable `datasource` SHALL be renamed to `metrics_datasource`.  A dashboard MAY declare a second picker of one type, such as the ClickHouse `KeeperDatasource`, and it SHALL be defaulted the same way.  Profiles have one datasource only, so no dashboard SHALL declare a Pyroscope picker.  Every datasource reference in a dashboard SHALL name the matching picker: panel and query datasources, variable queries (including the `cluster` variable), ad hoc filter variables, annotation queries, and the uids inside Explore and data-link URLs.  Every reference SHALL keep its `type` field.  The queries SHALL NOT change otherwise.

#### Scenario: Each used signal has a picker that shows the home tenant

- **WHEN** an operator opens an installed core or kit dashboard that uses Prometheus, Loki and Tempo
- **THEN** it has a Metrics, a Logs and a Traces picker
- **AND** each picker shows the cluster's own tenant

#### Scenario: A dashboard shows only the pickers it uses

- **WHEN** an operator opens a dashboard that uses only Prometheus
- **THEN** it has a Metrics picker and no Logs or Traces picker

#### Scenario: Another tenant changes everything on the dashboard

- **WHEN** an operator selects another tenant in the pickers
- **THEN** every panel, variable, annotation and link on the dashboard uses that tenant

#### Scenario: No Pyroscope picker

- **WHEN** a dashboard uses the Pyroscope datasource
- **THEN** it references the `pyroscope` uid and declares no Pyroscope picker

#### Scenario: A used signal without its picker fails the unit test

- **WHEN** a dashboard JSON file references a datasource of type `prometheus`, `loki` or `tempo` and declares no picker of that type
- **THEN** the unit test fails and names the file

### Requirement: No dashboard file names a fixed datasource uid

A unit test SHALL scan every core and kit dashboard JSON file.  It SHALL fail when the file names `mimir`, `loki` or `tempo` as a datasource uid anywhere: a `uid` key, a string `datasource` key, or a `uid` or `datasource` inside an Explore or data-link URL after URL decoding.  The failure SHALL name the file and the JSON path.  `pyroscope` SHALL be allowed.  The pickers' `current` value SHALL be empty in the files, so the test needs no exception.

#### Scenario: A fixed uid fails the test

- **WHEN** a dashboard JSON file names `mimir`, `loki` or `tempo` as a uid in a panel, a variable, an annotation or a link URL
- **THEN** the unit test fails and names the file

#### Scenario: A fixed uid inside an encoded Explore link fails the test

- **WHEN** a dashboard data link holds a URL-encoded `panes=` value whose JSON names the `loki` uid
- **THEN** the unit test fails and names the file

#### Scenario: Pyroscope is allowed

- **WHEN** a dashboard JSON file names `pyroscope` as a uid
- **THEN** the unit test passes for that reference

### Requirement: Dashboard links carry the pickers and the selected runs

Every link from one dashboard to another (`/d/` in a dashboard link or a data link) SHALL pass each carried variable that the source dashboard declares.  The carried variables are `metrics_datasource`, `logs_datasource`, `traces_datasource`, `cluster`, `baseline_cluster`, `candidate_cluster` and `doc_tenant`.  A link SHALL pass each of them either as `${<name>:queryparam}` or as an explicit `var-<name>=` value.  A link SHALL NOT pass `${<name>:queryparam}` for a variable that the source dashboard does not declare.  A unit test SHALL enforce both rules and SHALL name the file and the link.

#### Scenario: A drill-down keeps the tenant and the cluster

- **WHEN** an operator on tenant `acme` follows a link from one dashboard to another
- **THEN** the target dashboard opens with the same pickers and the same `cluster`

#### Scenario: A link that drops a declared variable fails the test

- **WHEN** a `/d/` link omits a carried variable that its source dashboard declares
- **THEN** the unit test fails and names the file

#### Scenario: An explicit value satisfies the rule

- **WHEN** a `/d/` link sets `var-cluster=` to an explicit value in place of `${cluster:queryparam}`
- **THEN** the unit test passes for that variable

#### Scenario: A link that passes an undeclared variable fails the test

- **WHEN** a `/d/` link passes `${cluster:queryparam}` from a dashboard that declares no `cluster` variable
- **THEN** the unit test fails and names the file

### Requirement: Installed dashboards default to the current cluster

Every path that installs a dashboard SHALL apply one install-time pass to it: the core dashboard tree, kit dashboards on `start`, and `grafana install`.  The pass SHALL read the cluster state of the workspace.  It SHALL set only these values and SHALL NOT change any query:

- the `current` value of each datasource picker to the stable datasource of its type (`mimir`, `loki` or `tempo`), which reads the cluster's own tenant;
- the `current` value of `cluster`, `baseline_cluster` and `candidate_cluster` to the current cluster's `<name>-<id>`, as a one-element list when the variable is multi-select;
- the options of `doc_tenant` to the tenant list, and its `current` value to the cluster's own tenant;
- the documents host placeholder to the documents web server's address on the control node.

#### Scenario: Core dashboards default to the current cluster

- **WHEN** easy-db-lab installs the core dashboards on cluster `lab-<id>`
- **THEN** the `cluster` variable of each installed dashboard defaults to `lab-<id>`

#### Scenario: Kit dashboards default to the current cluster

- **WHEN** a kit `start` installs its dashboards on cluster `lab-<id>`
- **THEN** the `cluster` variable of each installed kit dashboard defaults to `lab-<id>`

#### Scenario: The current cluster shows with no selection by hand

- **WHEN** an operator opens a dashboard that has a `cluster` variable on a running cluster
- **THEN** it shows the current cluster with no selection by hand

#### Scenario: A multi-select variable gets a one-element list

- **WHEN** the pass sets the default of a multi-select `cluster` variable
- **THEN** the default is a list that holds only the current cluster

#### Scenario: Nothing else changes

- **WHEN** the pass runs on a dashboard
- **THEN** every query, every variable query and every panel is the same as in the file

#### Scenario: A dashboard without these variables is unchanged

- **WHEN** the pass runs on a dashboard that has no picker, no `cluster`, no run variable and no `doc_tenant`
- **THEN** the installed dashboard has the same content as the file
