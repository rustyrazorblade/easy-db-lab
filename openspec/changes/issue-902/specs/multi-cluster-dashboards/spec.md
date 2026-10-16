## MODIFIED Requirements

### Requirement: All dashboards have a cluster multi-select variable

Every dashboard that has a `cluster` template variable (lowercase) SHALL populate it on the dashboard's metrics datasource picker, `${metrics_datasource}`, and never on a fixed datasource.  The variable SHALL support multi-select and SHALL include an "All" option.  On a dashboard that declares no `experiment` variable, it SHALL be populated by `label_values(up, cluster)`; the installer SHALL default it to the current cluster, and "All" SHALL NOT be the default.  On a dashboard that declares an `experiment` variable, it SHALL list only the selected experiment's clusters, through a query filtered by `edl_tenant=~"$exp_tenant"` and `experiment=~"$exp_name"` joined with the branch for clusters from before the label, and SHALL default to "All", which means the experiment's clusters.  The Tests dashboard declares a single-select `experiment` variable in place of `cluster`, listed over the `lookback` variable, and SHALL open on the last 24 hours.  The dashboard file SHALL store no default for the variable.  Every dashboard that queries metrics, logs or traces SHALL have a `cluster` variable, and every metrics, logs and traces query on it, including the queries of other template variables such as a host list, SHALL filter by the cluster variable.  Two exceptions apply: the Tests dashboard's listing of experiments, which lists every cluster of the tenant by design; and the variables that list clusters or experiments themselves (`cluster`, `cluster_a`, `cluster_b`, `experiment`, `exp_tenant`, `exp_name`).

#### Scenario: Cluster variable reads the selected metrics tenant
- **WHEN** an operator opens a dashboard that has a `cluster` variable and no `experiment` variable
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

#### Scenario: Two clusters running at once are not mixed
- **WHEN** two clusters of one tenant send metrics and logs at the same time
- **AND** an operator opens any dashboard with the `cluster` variable set to one of them
- **THEN** every panel and every variable list shows only that cluster's data

#### Scenario: A dashboard without a cluster filter fails the unit test
- **WHEN** a dashboard queries metrics, logs or traces and has no `cluster` variable, or has a query in any dialect that does not filter on the cluster variable
- **THEN** the unit test fails and names the dashboard and the query

#### Scenario: All tenants and All clusters show every cluster
- **WHEN** an operator selects the "all tenants" datasource in each picker and "All" in the `cluster` variable
- **THEN** the dashboard shows the data of every cluster of every tenant
- **AND** no query of the dashboard changes

#### Scenario: An experiment dashboard lists the experiment's clusters
- **WHEN** an operator selects experiment `acme/e1` on a dashboard that declares `experiment`
- **THEN** the `cluster` variable offers only the clusters of `e1` in tenant `acme`, and "All" selects all of them

### Requirement: Dashboard links carry the pickers and the selected runs

Every link from one dashboard to another (`/d/` in a dashboard link or a data link) SHALL pass each carried variable that the source dashboard declares.  The carried variables are `metrics_datasource`, `logs_datasource`, `traces_datasource`, `cluster`, `cluster_a`, `cluster_b` and `experiment`.  A link SHALL pass each of them either as `${<name>:queryparam}` or as an explicit `var-<name>=` value.  A link SHALL NOT pass `${<name>:queryparam}` for a variable that the source dashboard does not declare.  A unit test SHALL enforce both rules and SHALL name the file and the link.

#### Scenario: A drill-down keeps the tenant and the cluster
- **WHEN** an operator on tenant `acme` follows a link from one dashboard to another
- **THEN** the target dashboard opens with the same pickers and the same `cluster`

#### Scenario: A drill-down keeps the experiment
- **WHEN** an operator with experiment `acme/e1` selected follows a link from Experiment: Cassandra to the cluster pair
- **THEN** the cluster pair opens with experiment `acme/e1`

#### Scenario: A link that drops a declared variable fails the test
- **WHEN** a `/d/` link omits a carried variable that its source dashboard declares
- **THEN** the unit test fails and names the file

#### Scenario: An explicit value satisfies the rule
- **WHEN** a `/d/` link sets `var-cluster_a=` to an explicit value in place of `${cluster_a:queryparam}`
- **THEN** the unit test passes for that variable

#### Scenario: A link that passes an undeclared variable fails the test
- **WHEN** a `/d/` link passes `${cluster:queryparam}` from a dashboard that declares no `cluster` variable
- **THEN** the unit test fails and names the file

### Requirement: Installed dashboards default to the current cluster

Every path that installs a dashboard SHALL apply one install-time pass to it: the core dashboard tree, kit dashboards on `start`, `grafana install`, the local stack, and an export.  On a cluster the pass SHALL read the cluster state of the workspace.  It SHALL set only the `current` value and the options of variables it owns, and SHALL NOT change any query:

- the `current` value of each datasource picker to the stable datasource of its type (`mimir`, `loki` or `tempo`), which reads the home tenant;
- on a cluster, the `current` value of `cluster` to the current cluster's `<name>-<id>`, as a one-element list when the variable is multi-select, except on a dashboard that declares `experiment`, where `cluster` stays "All";
- on a cluster, the `current` value of `cluster_b` to the current cluster; `cluster_a` SHALL get no `current`, so it opens on the experiment's oldest cluster;
- on a cluster, the `current` value of `experiment` to the cluster's `<tenant>/<name>`; in an export, to the export's experiment when it has one;
- the one option and `current` value of the hidden `home_tenant` to the home tenant: the cluster's tenant, the local stack's `--tenant`, or the export's tenant;
- the documents host placeholder to the documents web server's address.

In the live local stack the pass SHALL set no `current` on `cluster`, `cluster_b` or `experiment`.

#### Scenario: Core dashboards default to the current cluster
- **WHEN** easy-db-lab installs the core dashboards on cluster `lab-<id>`
- **THEN** the `cluster` variable of each installed dashboard with no `experiment` variable defaults to `lab-<id>`

#### Scenario: Kit dashboards default to the current cluster
- **WHEN** a kit `start` installs its dashboards on cluster `lab-<id>`
- **THEN** the `cluster` variable of each installed kit dashboard defaults to `lab-<id>`

#### Scenario: The current cluster shows with no selection by hand
- **WHEN** an operator opens a dashboard that has a `cluster` variable and no `experiment` variable on a running cluster
- **THEN** it shows the current cluster with no selection by hand

#### Scenario: A multi-select variable gets a one-element list
- **WHEN** the pass sets the default of a multi-select `cluster` variable
- **THEN** the default is a list that holds only the current cluster

#### Scenario: The experiment and the home tenant are set on a cluster
- **WHEN** the pass runs on cluster `lab-<id>` in tenant `acme` with experiment `e1`
- **THEN** `experiment` defaults to `acme/e1`, `home_tenant` holds `acme`, and `cluster_b` defaults to `lab-<id>`
- **AND** `cluster_a` has no default

#### Scenario: The live local stack sets no cluster or experiment
- **WHEN** the pass runs for the live local stack with `--tenant acme`
- **THEN** `cluster`, `cluster_b` and `experiment` have no default
- **AND** `home_tenant` holds `acme`

#### Scenario: Nothing else changes
- **WHEN** the pass runs on a dashboard
- **THEN** every query, every variable query and every panel is the same as in the file

#### Scenario: A dashboard without these variables is unchanged
- **WHEN** the pass runs on a dashboard that has no picker, no `cluster`, no pair variable, no `experiment` and no `home_tenant`
- **THEN** the installed dashboard has the same content as the file

### Requirement: Series keep their cluster

Every PromQL and LogQL aggregation on a dashboard with a `cluster` variable SHALL keep `cluster`: `by (host_name)` becomes `by (cluster, host_name)`, and an aggregation with no grouping becomes `by (cluster)`.  Every legend on those series SHALL show the cluster, as `{{cluster_name}}`, the short name that the query's `label_replace` takes from `<name>-<uuid>`: the name and the first 8 characters of the id, for example `test-1a2b3c4d`, so two clusters with one name still differ.  A legend never shows the raw `{{cluster}}`; table panels show the short name in a Cluster column.  These queries are exempt: the variables that list clusters or experiments, the Tests dashboard's listing, the pair views' A and B queries, and the AWS/S3 queries that count the shared bucket once.  The pair views' A and B queries are exempt only from the grouping rule: their legends and panel titles SHALL name each side by its short name, for example `A test-1a2b3c4d`, never by the full `<name>-<uuid>`.  Everywhere else a dashboard shows a cluster to the user (panel titles, table cells, annotations, text panels, and the options of the cluster pickers), it SHALL show the short name; a picker's option value stays the full id, so queries and links still filter by it.  A unit test SHALL check every core and kit dashboard.

#### Scenario: Two clusters selected
- **WHEN** `cluster` selects two clusters that both have `db0`
- **THEN** each panel draws a separate series for each cluster's `db0`, and each legend names the cluster

#### Scenario: An aggregation that drops the cluster fails the unit test
- **WHEN** a dashboard query aggregates without `cluster`, with `by (...)`, `without (cluster)`, or no grouping
- **THEN** the unit test fails and names the file, the panel and the aggregation

#### Scenario: A legend without the cluster fails the unit test
- **WHEN** a panel target's legend does not show `{{cluster_name}}`, shows the raw `{{cluster}}`, or reads `{{cluster_name}}` from a query that does not write it
- **THEN** the unit test fails and names the file, the panel and the legend

#### Scenario: A pair side is named by its short name
- **WHEN** an operator compares cluster A and cluster B on a pair dashboard
- **THEN** each side's legends and panel titles show its short name, such as `A test-1a2b3c4d`, and never the full cluster id

### Requirement: System dashboards have a role picker

System Overview, System A/B Comparison and Experiment: System SHALL declare `role`, a multi-select custom variable with an "All" option.  A host's role SHALL be read from its host name: `db` is `db[0-9]+`, `app` is `app[0-9]+`, `control` is `control[0-9]+`, and `spark` is `ip-.+` (EMR nodes).  The default SHALL be All, whose value `.*` matches every host.  Queries SHALL read the variable as `host_name=~"${role:pipe}"`, and the CloudWatch selector SHALL read it as `tag_Name=~"${role:pipe}"`.  The host pickers (`hostname` on System Overview; `hosts_a` and `hosts_b` on System A/B Comparison) SHALL list only hosts of the selected roles.  Experiment: System has no host picker.  Every panel selector and PromQL annotation on these dashboards that has a cluster matcher SHALL filter by `role`.  Every `/d/` link from these dashboards SHALL carry `role`, as `${role:queryparam}` or as an explicit `var-role=` value, in addition to the variables that the requirement "Dashboard links carry the pickers and the selected runs" lists.  A unit test SHALL check the variable, its patterns against sample host names, the host pickers and every panel selector.

#### Scenario: A role shows every host of that role
- **WHEN** an operator selects the role `db` on System Overview
- **THEN** the host picker lists only the db hosts
- **AND** every panel shows only db hosts

#### Scenario: Each role's pattern selects exactly its hosts
- **WHEN** the role patterns are matched against the host names `db0`, `db12`, `app0`, `control0` and `ip-10-28-1-222`
- **THEN** each host matches only the pattern of its own role

#### Scenario: All is the default and shows every host
- **WHEN** an operator opens System Overview, System A/B Comparison or Experiment: System with no role selected by hand
- **THEN** `role` is All and every host shows

#### Scenario: The A/B host pickers follow the role
- **WHEN** an operator selects the role `app` on System A/B Comparison
- **THEN** `hosts_a` and `hosts_b` list only app hosts

#### Scenario: A link keeps the selected roles
- **WHEN** an operator with the role `app` selected follows a `/d/` link from System Overview
- **THEN** the target dashboard opens with `role` set to `app`

#### Scenario: A panel selector without the role filter fails the unit test
- **WHEN** a panel selector on System Overview, System A/B Comparison or Experiment: System has a cluster matcher and no `role` matcher
- **THEN** the unit test fails and names the file and the selector

## ADDED Requirements

### Requirement: Experiment filters include clusters from before the label

A cluster whose series carry no `experiment` label SHALL be an experiment of one, named for its `<name>-<id>`.  Every PromQL query on a dashboard that matches on `experiment` or `edl_tenant` (other than `experiment=""`) SHALL also hold a branch over `up{experiment=""}` joined by `or`, so these clusters appear in experiment pickers, cluster pickers, report lookups and the Tests listing.  A query that outputs `edl_tenant` for such a cluster SHALL take it from Mimir's `__tenant_id__` when present and from the hidden `home_tenant` variable otherwise.  A dashboard that references `${home_tenant}` SHALL declare it as a hidden custom variable.  No LogQL or TraceQL query SHALL name `experiment` or `edl_tenant`.  A unit test SHALL check every core and kit dashboard, panel targets and variable queries, and SHALL name the file and the query that breaks a rule.

#### Scenario: A pre-label cluster appears as an experiment of one
- **WHEN** the store holds a cluster `lab-<id>` with no `experiment` label
- **THEN** the experiment pickers offer `<tenant>/lab-<id>`, and selecting it offers `lab-<id>` in the cluster picker

#### Scenario: A pre-label cluster's tenant in the all-tenants view
- **WHEN** an operator on the all-tenants datasource selects a pre-label cluster of tenant `acme`
- **THEN** its report link uses `acme`, read from `__tenant_id__`

#### Scenario: A pre-label cluster's tenant in the home view
- **WHEN** an operator on the home tenant's datasource selects a pre-label cluster
- **THEN** its report link uses the home tenant

#### Scenario: A query without the unlabelled branch fails the unit test
- **WHEN** a dashboard query matches `experiment=~"$exp_name"` and holds no `up{experiment=""}` branch joined by `or`
- **THEN** the unit test fails and names the file and the query

#### Scenario: A LogQL query on the experiment fails the unit test
- **WHEN** a dashboard's LogQL or TraceQL query names `experiment` or `edl_tenant`
- **THEN** the unit test fails and names the file and the query

### Requirement: An experiment dashboard reads one tenant at a time

An experiment SHALL be selected by its `<tenant>/<name>` value.  Every cluster list on a dashboard that declares `experiment` SHALL filter by `edl_tenant=~"$exp_tenant"` for labelled series, so two experiments with one name in two tenants are two options and never merge.  Panels SHALL filter by `cluster` only.  With "All", `exp_tenant` and `exp_name` SHALL match everything, so on the all-tenants datasource every tenant's clusters show on one panel.  A unit test SHALL check that every cluster list on such a dashboard carries the tenant filter.

#### Scenario: Same-named experiments stay apart
- **WHEN** tenants `default` and `acme` each hold an experiment `e1` and an operator selects the all-tenants datasource
- **THEN** the experiment picker offers `default/e1` and `acme/e1` as two options
- **AND** selecting `acme/e1` lists only the clusters of `acme`

#### Scenario: All shows every tenant on one panel
- **WHEN** an operator selects the all-tenants datasource and experiment "All" on a comparison dashboard
- **THEN** clusters of different tenants show on one panel

#### Scenario: A cluster list without the tenant filter fails the unit test
- **WHEN** a cluster list on a dashboard that declares `experiment` does not filter by `edl_tenant=~"$exp_tenant"`
- **THEN** the unit test fails and names the file and the variable

### Requirement: Every trace query filters on the cluster variable

Every TraceQL query on a core or kit dashboard SHALL filter on the cluster variable: Tempo panel targets, Tempo variable queries, and the TraceQL inside Explore `panes=` links on a Tempo datasource, after URL decoding.  Every spanset SHALL hold `resource.cluster` matched against `$cluster`, `${cluster:regex}`, `$cluster_a` or `$cluster_b`.  The cluster-filter unit test SHALL check TraceQL as a dialect next to PromQL, LogQL and profiles.

#### Scenario: Every dialect filters on the cluster
- **WHEN** every core and kit dashboard is checked
- **THEN** every query in every dialect, including TraceQL, filters on the cluster variable, except the Tests listing

#### Scenario: A TraceQL link without the cluster fails the unit test
- **WHEN** a dashboard data link holds an Explore `panes=` value whose TraceQL spanset has no `resource.cluster` matcher
- **THEN** the unit test fails and names the file and the query
