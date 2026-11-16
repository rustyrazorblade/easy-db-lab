## MODIFIED Requirements

### Requirement: All dashboards have a cluster multi-select variable

Every dashboard that has a `cluster` template variable (lowercase) SHALL populate it with `label_values(up, cluster)` on the dashboard's metrics datasource picker, `${metrics_datasource}`, and never on a fixed datasource.  The variable SHALL support multi-select and SHALL include an "All" option.  The Tests dashboard is the one exception: its `cluster` variable is single-select, because it selects one test, and it lists the clusters that have `up` samples within the `lookback` variable, the same window as the listing of tests, not within the dashboard's time range.  The Tests dashboard SHALL open on the last 24 hours.  The dashboard file SHALL store no default for the variable.  The installer SHALL set the default to the current cluster, as the requirement "Installed dashboards default to the current cluster" states.  "All" SHALL NOT be the default.  Every dashboard that queries metrics or logs SHALL have this `cluster` variable, and every metrics and logs query on it, including the queries of other template variables such as a host list, SHALL filter by `cluster=~"$cluster"` (issue #983, folded into this change).  Two exceptions apply: the Tests dashboard's listing of tests, which lists every cluster of the tenant by design; and the variables that list the clusters themselves (`cluster`, `baseline_cluster`, `candidate_cluster`).

#### Scenario: Cluster variable reads the selected metrics tenant

- **WHEN** an operator opens a dashboard that has a `cluster` variable
- **THEN** a `cluster` variable appears in the dashboard header
- **AND** the variable is populated by `label_values(up, cluster)` against the datasource that the metrics picker selects, except on the Tests dashboard, whose variable lists the clusters with `up` samples within `lookback` on that datasource

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

#### Scenario: Two clusters running at once are not mixed

- **WHEN** two clusters of one tenant send metrics and logs at the same time
- **AND** an operator opens any dashboard with the `cluster` variable set to one of them
- **THEN** every panel and every variable list shows only that cluster's data

#### Scenario: A dashboard without a cluster filter fails the unit test

- **WHEN** a dashboard queries metrics or logs and has no `cluster` variable, or has a metrics or logs query that does not filter by `cluster=~"$cluster"`
- **THEN** the unit test fails and names the dashboard and the query

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

### Requirement: System dashboards have a role picker

System Overview and System A/B Comparison SHALL declare `role`, a multi-select custom variable with an "All" option.  A host's role SHALL be read from its host name: `db` is `db[0-9]+`, `app` is `app[0-9]+`, `control` is `control[0-9]+`, and `spark` is `ip-.+` (EMR nodes).  The default SHALL be All, whose value `.*` matches every host.  Queries SHALL read the variable as `host_name=~"${role:pipe}"`, and the CloudWatch selector SHALL read it as `tag_Name=~"${role:pipe}"`.  The host pickers (`hostname` on System Overview; `baseline` and `candidate` on System A/B Comparison) SHALL list only hosts of the selected roles.  Every panel selector and PromQL annotation on these dashboards that has a cluster matcher SHALL filter by `role`.  Every `/d/` link from these dashboards SHALL carry `role`, as `${role:queryparam}` or as an explicit `var-role=` value, in addition to the variables that the requirement "Dashboard links carry the pickers and the selected runs" lists.  A unit test SHALL check the variable, its patterns against sample host names, the host pickers and every panel selector.

#### Scenario: A role shows every host of that role

- **WHEN** an operator selects the role `db` on System Overview
- **THEN** the host picker lists only the db hosts
- **AND** every panel shows only db hosts

#### Scenario: Each role's pattern selects exactly its hosts

- **WHEN** the role patterns are matched against the host names `db0`, `db12`, `app0`, `control0` and `ip-10-28-1-222`
- **THEN** each host matches only the pattern of its own role

#### Scenario: All is the default and shows every host

- **WHEN** an operator opens System Overview or System A/B Comparison with no role selected by hand
- **THEN** `role` is All and every host shows

#### Scenario: A link keeps the selected roles

- **WHEN** an operator with the role `app` selected follows a `/d/` link from System Overview
- **THEN** the target dashboard opens with `role` set to `app`

#### Scenario: A panel selector without the role filter fails the unit test

- **WHEN** a panel selector on System Overview or System A/B Comparison has a cluster matcher and no `role` matcher
- **THEN** the unit test fails and names the file and the selector

### Requirement: Dashboards match hosts on cluster and host name

Every cluster names its hosts the same way, so a PromQL vector match that pairs hosts SHALL use `on (cluster, host_name)`, never `on (host_name)` alone.  No match SHALL use `ignoring (cluster)`.  Every `by (...)` that feeds a host match SHALL keep `cluster`.  A unit test SHALL check every core and kit dashboard, and SHALL fail on a match on `host_name` without `cluster`, on a match that ignores `cluster`, and on an aggregation by `host_name` that drops `cluster` in a query that matches on `host_name`.

#### Scenario: Two clusters with the same host name

- **WHEN** `cluster` is All and two clusters each have `app0`
- **THEN** System Overview's Load per core shows both hosts and does not fail with "found duplicate series"

#### Scenario: A host match without the cluster fails the unit test

- **WHEN** a dashboard query matches `on (host_name)` without `cluster`
- **THEN** the unit test fails and names the file and the clause

#### Scenario: A match that ignores the cluster fails the unit test

- **WHEN** a dashboard query matches with `ignoring (cluster)`
- **THEN** the unit test fails and names the file and the clause

#### Scenario: An aggregation that drops the cluster fails the unit test

- **WHEN** a dashboard query aggregates `by (host_name)` without `cluster` and matches on `host_name`
- **THEN** the unit test fails and names the file and the clause

### Requirement: Series keep their cluster

Every PromQL and LogQL aggregation on a dashboard with a `cluster` variable SHALL keep `cluster`: `by (host_name)` becomes `by (cluster, host_name)`, and an aggregation with no grouping becomes `by (cluster)`.  Every legend on those series SHALL show the cluster, as `{{cluster_name}}`, the short name that the query's `label_replace` takes from `<name>-<uuid>`: the name and the first 8 characters of the id, for example `test-1a2b3c4d`, so two clusters with one name still differ.  A legend never shows the raw `{{cluster}}`; table panels show the short name in a Cluster column.  These queries are exempt: the variables that list clusters, the Tests dashboard's listing, the comparison views' baseline and candidate run queries, and the AWS/S3 queries that count the shared bucket once.  The comparison views' run queries are exempt only from the grouping rule: their legends and panel titles SHALL name each run by its short name, for example `baseline test-1a2b3c4d`, never by the full `<name>-<uuid>`.  Everywhere else a dashboard shows a cluster to the user (panel titles, table cells, annotations, text panels, and the options of the cluster pickers), it SHALL show the short name; a picker's option value stays the full id, so queries and links still filter by it.  A unit test SHALL check every core and kit dashboard.

#### Scenario: Two clusters selected

- **WHEN** `cluster` selects two clusters that both have `db0`
- **THEN** each panel draws a separate series for each cluster's `db0`, and each legend names the cluster

#### Scenario: An aggregation that drops the cluster fails the unit test

- **WHEN** a dashboard query aggregates without `cluster`, with `by (...)`, `without (cluster)`, or no grouping
- **THEN** the unit test fails and names the file, the panel and the aggregation

#### Scenario: A legend without the cluster fails the unit test

- **WHEN** a panel target's legend does not show `{{cluster_name}}`, shows the raw `{{cluster}}`, or reads `{{cluster_name}}` from a query that does not write it
- **THEN** the unit test fails and names the file, the panel and the legend

#### Scenario: A comparison run is named by its short name

- **WHEN** an operator compares a baseline and a candidate run on a comparison dashboard
- **THEN** each run's legends and panel titles show the run's short name, such as `baseline test-1a2b3c4d`, and never the full cluster id
