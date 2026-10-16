# Dashboards

JSON dashboard files in this tree are the core dashboards. Gradle copies the tree onto the classpath under a `dashboards/` prefix at build time, and `grafana update-config` copies it from there onto the control node, where Grafana reads it as files. **Always run `./gradlew installDist` before `grafana update-config`** — `update-config` reads from the built JAR, not the source files directly.

## Layout: one directory per Grafana folder

Each subdirectory is a Grafana folder, and the folder's name is the directory name verbatim:

- `cassandra/` — Cassandra dashboards
- `infrastructure/` — engine-agnostic host, cloud and system dashboards (`system-overview.json`, the home dashboard, lives here)
- `networking/` — CNI dashboards (Cilium datapath, ENI IPAM, Hubble flows); see [Networking folder](#networking-folder)
- `observability/` — dashboards about the observability stack itself (profiling, Tempo, log investigation)
- `opensearch/` — OpenSearch dashboards

There is no registry. This section is the canonical description of how the tree reaches Grafana; other docs link here rather than restate it.

1. **Discovery** — `GrafanaDashboardCatalog.discover()` (`src/main/kotlin/.../configuration/grafana/`) scans the classpath under `dashboards/` with ClassGraph and yields one `GrafanaDashboard(folder, jsonFileName)` per `<folder>/<name>.json`, sorted. A JSON file at the root of the tree or nested deeper is an error, not skipped. The home dashboard is pinned to exactly `infrastructure/system-overview.json` (`Constants.Grafana.HOME_DASHBOARD_PATH`); the catalog refuses to exist without it, and a `system-overview.json` in another folder is an ordinary dashboard. The catalog also knows the classpath base the JSON is read from (`resourcePathOf`); a `GrafanaDashboard` is only `(folder, jsonFileName)`.
2. **Local tree** — `GrafanaDashboardTreeWriter` writes the catalog to a temp directory as `<folder>/<file>.json`. It reads each JSON from the classpath, replaces `__PYROSCOPE_URL__` with the control node's Pyroscope URL (`pyroscopeIngestBaseUrl`; only `observability/profiling.json` carries it), and applies the install-time pass, `DashboardDefaults` (see [Install-time defaults](#install-time-defaults)). The result is written as compact JSON with every number as written. No `TemplateService`: dashboards carry Grafana built-ins like `$__rate_interval` that general substitution corrupts.
3. **Upload and swap** — `GrafanaDashboardTreeUploader` (`src/main/kotlin/.../services/`) hands the local tree to `RemoteOperationsService.replaceDirectory(host, localDir, "/mnt/db1/grafana/dashboards", "472:472")`. That method creates a staging directory beside the target with `sudo mktemp -d -p /mnt/db1/grafana` (same filesystem, chowned to the SSH user so SFTP can write, in one remote command), uploads the tree into it, then in one remote command renames the old tree to `dashboards.old`, renames the staged tree in, `chown -R`s it to the owner, and removes `.old`. Grafana's provider therefore never polls an empty or partial directory, and a dashboard deleted from the repo disappears because the whole tree is replaced. If anything fails before the swap the staging directory is removed. No dashboard is a K8s object and nothing is deleted from K8s.
4. **Provisioning** — `GrafanaDashboardProvisioningConfig` emits one file provider with `options.path: /var/lib/grafana/dashboards` (the hostPath as the Deployment mounts it) and `foldersFromFilesStructure: true`, so each directory becomes a Grafana folder of the same title. `GrafanaManifestBuilder` ships that YAML in the `grafana-dashboards-config` ConfigMap and points `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` at `/var/lib/grafana/dashboards/` + `Constants.Grafana.HOME_DASHBOARD_PATH`; it does not take the catalog and nothing it builds varies with the tree's contents.

So:

- **Adding a dashboard**: drop the JSON into the right folder directory. Nothing else.
- **Adding a folder**: make a directory and put a dashboard in it.
- **Never put a JSON file at the root of `dashboards/`** — discovery rejects it, because a file at the provider's root would land in Grafana's General folder.
- Two folders may hold a file with the same name; each stays under its own directory.
- `infrastructure/system-overview.json` must exist at exactly that path. It is the Grafana home dashboard and discovery fails loudly without it.
- Directory names are deliberately lowercase and match kit names (`kit install clickhouse` installs into a folder called `clickhouse`), so a core dashboard for an engine that also has a kit lands beside the kit's own dashboards rather than in a capitalized twin.

Dashboards that belong to a kit go in the kit's own `dashboards/` directory under `src/main/resources/.../kits/<name>/`, not here.

## Datasources and the tenant pickers

Grafana has a datasource per tenant for metrics, logs and traces, plus one for all tenants
(`GrafanaDatasourceSet`). The stable uids read the cluster's own tenant:

| Datasource | Stable UID  | Type                           | Picker               |
|------------|-------------|--------------------------------|----------------------|
| Mimir      | `mimir`     | `prometheus`                   | `metrics_datasource` |
| Loki       | `loki`      | `loki`                         | `logs_datasource`    |
| Tempo      | `tempo`     | `tempo`                        | `traces_datasource`  |
| Pyroscope  | `pyroscope` | `grafana-pyroscope-datasource` | none                 |

A dashboard names `mimir`, `loki` and `tempo` only through its pickers, so an operator can switch it
to another tenant. The rules:

- Declare one `type: datasource` variable for each signal the dashboard uses: `metrics_datasource`
  (label "Metrics", `query: prometheus`), `logs_datasource` ("Logs", `loki`), `traces_datasource`
  ("Traces", `tempo`). A second picker of one type, such as ClickHouse's `KeeperDatasource`, is
  allowed. Profiles have one datasource only, so there is no Pyroscope picker; `pyroscope` stays a
  fixed uid.
- Every datasource reference names the picker and keeps its `type`:
  `{"type": "prometheus", "uid": "${metrics_datasource}"}`. That covers panels, targets, variable
  queries (the `cluster` variable included), ad hoc filters, annotation queries, and the uids inside
  Explore links (left unencoded in the `panes=` JSON, like any other Grafana variable).
- Leave every picker's `current` empty (`{}`). The install-time pass sets it.

`DashboardDatasourceVariablesTest` enforces all three rules on every core and kit dashboard: no
`uid` or string `datasource` equal to `mimir`, `loki` or `tempo` anywhere (Explore links are
URL-decoded first), a picker for each used type, and the link rule below. It names the file and the
JSON path. `DashboardDatasourceTest` still fails when a dashboard names a datasource Grafana does not
have.

### Links carry the pickers, the selected clusters and the experiment

Every `/d/` link (dashboard link or data link) passes each of `metrics_datasource`,
`logs_datasource`, `traces_datasource`, `cluster`, `cluster_a`, `cluster_b`, `experiment` and
`role` that its own dashboard declares (`DashboardDatasourceGuards.CARRIED`), as
`${name:queryparam}` or as an explicit `var-<name>=value`, and passes no `${name:queryparam}` for a
variable it does not declare. So a drill-down keeps the tenant, the cluster and the experiment:

```
/d/system-overview/system-overview?from=${__from}&to=${__to}&${metrics_datasource:queryparam}&${logs_datasource:queryparam}&${cluster:queryparam}
```

Every `/d/` link also keeps the time range: `from=${__from}&to=${__to}` in its URL, `${__url_time_range}`, or `keepTime`. Links into the own-range dashboards are the exception: Tests (`tests`), Experiment: Cassandra (`c09599ec-8d87-4fe5-9346-9e85c41d33af`, the former Cluster Comparison uid), Experiment: System (`experiment-system`) and the three pair dashboards (`cluster-pair`, `ab-comparison`, `system-ab-comparison`). Those open on their own relative ranges, so such a link carries no `from`/`to`, no `${__url_time_range}` and no `keepTime`, since an absolute range turns their panel `timeFrom` off. `DashboardLinkTimeTest` checks both rules for core and kit dashboards, and that every own-range uid exists.

### The cluster variable

Every `cluster` variable is multi-select with "All", on `${metrics_datasource}`, and lists one of two sets (`ClusterVariableTest`, `configuration/grafana/`):

- On a dashboard that declares no `experiment`, every cluster of the tenant the Metrics picker selects: `label_values(up, cluster)`.  The installer defaults it to the current cluster.
- On a dashboard that declares `experiment` (see [Experiments](#experiments)), only the selected experiment's clusters: a `query_result` over `up{edl_tenant=~"$exp_tenant", experiment=~"$exp_name"}` joined by `or` with the pre-label branch `up{experiment="", cluster=~"$exp_name"}`.  Its "All" carries no `allValue`, so All means the experiment's clusters, not every cluster, and the installer leaves it on All.  Tests, the Experiment dashboards and the cluster pair list over `[$lookback:${resolution}s]` (Tests and the cluster pair hide the variable); A/B Comparison and System A/B Comparison list over `[$__range]`, because their `cluster` scopes the build and host panels to the dashboard range.

So every query that reads `$cluster` matches it with `=~`, never `=`: `cluster=~"$cluster"` (`ClusterVariableTest` fails on an equality match on a dashboard with a multi-select `cluster`).  No dashboard has a single-select `cluster`: Tests picks one experiment instead.

Every dashboard that queries metrics, logs, traces or profiles declares `cluster`, and every selector of every metrics, logs, traces and profile query filters by it: panel targets, annotation queries, Explore links, and the queries of other variables (`label_values(system_cpu_logical_count{cluster=~"$cluster"}, host_name)`).  A Pyroscope query's selector is its `labelSelector`.  A Pyroscope variable is different: the plugin's `VariableSupport` lists values over `{__profile_type__="<profileTypeId>"}` and never reads a `labelSelector` on a variable, so the variable's matchers are written into its `profileTypeId`, which closes the plugin's quote and leaves the last one open: `"profileTypeId": "process_cpu:cpu:nanoseconds:cpu:nanoseconds\",cluster=~\"${cluster:regex}"`.  Its `definition` shows the resulting selector.  The Pyroscope plugin interpolates its queries with Grafana's default format, which writes two selected clusters as the glob `{a,b}` and matches nothing, so every Pyroscope regex matcher on a variable, in a `labelSelector` or a `profileTypeId`, reads it as `${name:regex}`: `cluster=~"${cluster:regex}"`, `hostname=~"${hostname:regex}"`.  A dashboard that queries only profiles still declares the `metrics_datasource` picker, because the `cluster` variable reads it.  Clusters of one tenant share Mimir's, Loki's, Tempo's and Pyroscope's store, so a selector without the filter mixes every cluster that ran at the same time.  Each side of a binary expression needs its own filter, because `a{cluster=~"$cluster"} / b` still divides by every cluster's `b`.  A new `cluster` variable outside an experiment dashboard has `allValue: ".+"`.  The pair dashboards' run views filter by `cluster="$cluster_a"` or `cluster="$cluster_b"` instead (`DashboardVariables.CLUSTER_FILTERS`).  Two kinds of query read every cluster on purpose: the variables that list clusters or experiments (`cluster`, `cluster_a`, `cluster_b`, `experiment`, `exp_tenant`, `exp_name`; `DashboardVariables.CLUSTER_LISTS`), and the Tests dashboard's listing.  `ClusterFilterTest` (`configuration/grafana/`) checks every core and kit dashboard and names the dashboard, the query and the unfiltered selector.  Every profile producer labels its profiles `cluster=<name>-<id>`, the value on every other signal: the JFR shipper, Alloy, the sidecar, the stress job, the Spark agent on EMR, and the presto and trino kits (which read `cluster_name` from `cluster-config`).

Traces are filtered the same way, as their own dialect (`ClusterFilterGuards.Language.TRACEQL`): every TraceQL query holds `resource.cluster` matched against `$cluster`, `${cluster:regex}`, `$cluster_a` or `$cluster_b` in every spanset.  That covers Tempo panel targets, Tempo variable queries (a label-values variable with no filter lists over every span, so it needs one), and the TraceQL inside an Explore `panes=` link on a Tempo datasource, after URL decoding: `{ resource.cluster =~ "${cluster:regex}" && resource.service.name = "${__field.labels.service_name}" }`.  A service map or another Tempo target with no TraceQL carries no query and is not checked.

Every cluster names its hosts the same way (db0, app0, control0), so a vector match pairs hosts `on (cluster, host_name)`, never `on (host_name)` alone.  With `cluster` set to All, `a / on (host_name) b` fails with "found duplicate series for the match group", and `and on (host_name)` keeps one cluster's series for another cluster's reason.  Every `by (...)` that feeds such a match keeps `cluster` (`sum by (cluster, host_name)`), and no match ignores `cluster`.  `ClusterJoinTest` (`configuration/grafana/`) checks every core and kit dashboard.  An arithmetic or comparison match `on (...)` without `group_left`/`group_right` aggregates each side by its match labels (`max by (cluster, host_name) (a) / on (cluster, host_name) max by (cluster, host_name) (b)`): a raw selector holds two series per host for the few minutes after a label appears or changes (`node_role`, a collector upgrade), and a range that covers them answers 422.  `OneToOneMatchTest` checks every core and kit dashboard.

Every series keeps its cluster (owner decision, 2026-09-29), so two selected clusters never draw as one line.  Every PromQL and LogQL aggregation keeps `cluster`: `sum by (cluster, host_name)`, `histogram_quantile(0.99, sum by (cluster, le) (...))`, and `sum by (cluster) (...)` where the aggregation had no grouping.  Never `scalar()` a per-cluster aggregation, because it is NaN once two clusters are selected; match on the cluster instead: `a / on (cluster) group_left sum by (cluster) (b)`.  Every legend shows the cluster by its short name: the name and the first 8 characters of its id (`test-1a2b3c4d`), so two clusters that share a name still differ.  The query writes `cluster_name` from `<name>-<uuid>` with `label_replace(<expr>, "cluster_name", "$1", "cluster", "(.+-[0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")`, and the legend reads it first: `{{cluster_name}} {{host_name}}`.  A legend never reads the raw `{{cluster}}`, which shows the whole `<name>-<uuid>`, and neither does an annotation title: the Loki annotation query writes `cluster_name` with `` | label_format cluster_name=`{{regexReplaceAll "^(.+-[0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" .cluster "$1"}}` `` and titles with `{{cluster_name}}`.  A stat panel shows one value per cluster.  A field override that matched a legend matches it with the cluster in front: `byRegexp` `^(.* )?p50$`, not `byName` `p50`.  A table's legend names a value column, so a table keeps its legends and shows the cluster as a column instead: its `organize` hides `cluster` and renames `cluster_name` to "Cluster".  A table that hides `cluster` writes `cluster_name`, or it has no Cluster column.  Every Prometheus target of a table with more than one target is `format: table`: time-series frames keep their labels off the columns, so `merge` stacks every measure in one column and no row names its cluster.  A table joined over several frames names the columns it shows in its `organize` `includeByName`, with `cluster_name 1` first in `indexByName`: without it every label of every frame is a column (a ClickHouse table had 182).  A join or a concatenation of N targets numbers each frame's columns, so it hides `cluster 1` to `cluster N` and `cluster_name 2` to `cluster_name N`, and renames `cluster_name 1`.  A table joins rows with `merge`, or with `seriesToColumns`/`joinByField` on a key that every target writes from the long `cluster` (`label_join(<expr>, "cluster_instance", "/", "cluster", "instance")`, joined by `cluster_instance`, with the key column hidden), never on a label that two clusters share, and never with `concatenate`, which pairs rows by position.  Two clusters can share a short name (the default is `test`), so the key never reads `cluster_name`.  A query that groups by cluster takes a missing value's zero from a series that has the cluster, `or 0 * sum by (cluster) (<total>)`, never `or vector(0)`: `vector(0)` has no labels, so it gives no zero to a cluster without series and adds a zero that belongs to no cluster.  A `groupby` picker never offers `cluster`, because every grouping already has it and `by (cluster, cluster)` repeats a label.  The exceptions are the variables that list clusters, the Tests dashboard's listing, the pair views' A and B queries (one cluster per side: `SeriesClusterGuards.exempt` exempts a query that reads `$cluster_a` or `$cluster_b` and not `$cluster`), and the AWS/S3 queries below.  `SeriesClusterTest` (`configuration/grafana/`) checks every core and kit dashboard for all of these, and that every `cluster_name` write gives two clusters named `test` two short names, and names the file, the panel, and the aggregation, legend, override, table or join.  A table whose data links read the long `cluster` (the Tests listing) keeps it in the frame: a field override hides it (`custom.hidden`) and names `cluster_name` "Cluster" (`displayName`).

Everywhere else a dashboard shows a cluster, it shows the short name too (owner decision, D3).  Each cluster picker (`cluster`, `cluster_a`, `cluster_b`) splits its options with a named-group regex, so the text is the short name and the value the full id that queries and links read: `/^(?<value>(?<text>.+?)(?:-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?)$/` for `label_values`, and the same groups inside `cluster="..."` for `query_result`.  A title, description, legend, annotation title or text panel names a picker as `${name:text}`, which renders the option text: the pair views' titles read `Throughput: A ${cluster_a:text}`.  Queries, link URLs and a text panel's `src`/`href` keep `$cluster`.  `ClusterShortNameTest` checks every core and kit dashboard.  `PromQlCompatibilityIntegrationTest` runs every query that reads `groupby` once per value the picker offers.

Every running cluster's YACE reports the shared account bucket, each copy labelled with its own cluster, so an AWS/S3 query collapses the copies: `max by (name, dimension_BucketName, dimension_FilterId, dimension_StorageType) (...)`, never a `sum` and never `by (cluster, ...)`.  With `cluster` set to All each bucket then counts once, and one cluster still shows its own values.  `SharedBucketCountTest` (`configuration/grafana/`) checks every AWS/S3 query.

### The role picker

System Overview, System A/B Comparison and Experiment: System declare `role`, a multi-select custom variable with All.  System Overview also declares `service`, the `node_role` values; the collector derives it from the host name (`db`, `app`, `control`) and EMR nodes set their own; its `allValue` is `.*`, which also matches a series that carries no `node_role` (`NodeRoleVariableTest`).  A host's role is read from its name (owner decision), so the variable's `key : value` query maps each role to a host-name regex: `db : db[0-9]+,app : app[0-9]+,control : control[0-9]+,spark : ip-.+` (EMR nodes are named `ip-...` by EC2).  `allValue` is `.*`, which also matches a series with no `host_name`.  Queries read it as `host_name=~"${role:pipe}"`: `:pipe` joins the selected regexes with `|` unescaped, whereas `:regex` would escape them.  The host pickers (`hostname` on System Overview; `hosts_a` and `hosts_b` on System A/B Comparison; Experiment: System has none) apply it, and so does every panel selector and PromQL annotation that has a cluster matcher.  The CloudWatch series carry the host as `tag_Name`, so that selector uses `tag_Name=~"${role:pipe}"`.  Links from these dashboards carry `${role:queryparam}`.  `RolePickerTest` (`configuration/grafana/`) checks the variable, the patterns against sample host names, the host pickers and every selector.

### Install-time defaults

The dashboard files store no defaults, because a default names a cluster, an experiment or a tenant. They also save no auto-refresh (`"refresh": ""`): a saved refresh cancels every query still running at each tick, so a dashboard whose queries take longer than the interval never finishes loading. The refresh picker stays, and `DashboardRefreshTest` checks every core and kit dashboard. Every install
path applies one pass, `DashboardDefaults` (`configuration/grafana/`): the core tree
(`GrafanaDashboardTreeWriter`), kit dashboards on `start` (`KitRunnerCommand`),
`grafana install`, the local stack (`localstack/LocalStackDashboards`) and an export
(`export/ExportStackFiles`). It reads a `DashboardInstallContext` and sets, and changes nothing else:

- each picker's `current` to the stable datasource of its type;
- `cluster` to the current cluster: the value `<name>-<id>` and the text its short name (a
  one-element list when the variable is multi-select), except on a dashboard that declares
  `experiment`, where `cluster` keeps no default and opens on "All", the experiment's clusters;
- `cluster_b` to the current cluster; `cluster_a` gets no default, so it opens on its first
  option, the experiment's oldest cluster;
- `experiment` to the current experiment's `<tenant>/<name>` (`ExperimentKey.pickerValue`);
- the hidden `home_tenant` to one option, the home tenant, selected;
- `__DOCUMENTS_URL__` in any string to the documents web server: `http://<control private
  IP>:3080` on a cluster, `http://127.0.0.1:<documents port>` in the local stack and an export.

The live local stack has no current cluster and no current experiment, so it sets none of
`cluster`, `cluster_b` and `experiment`, and every open shows the newest experiment. An export
sets no cluster, and sets `experiment` only when it was taken of one. The home tenant is the
cluster's tenant, the local stack's `--tenant`, or the export's tenant. So `grafana install` shows
exactly what `up` installs.

## The Tests dashboard

`infrastructure/tests.json` (uid `tests`) lists every experiment of the tenant the Metrics picker
selects in its **Experiments** table: the tenant, the name, the first start and last end of its
clusters, how many clusters it has, and their short names. Start and end are the first and last
`up` sample within `lookback` (custom, default `180d`), found by a subquery at the `resolution`
step. A cluster from before the experiment label is its own row (see
[Clusters from before the label](#clusters-from-before-the-label)). The dashboard opens on
`now-24h`: at `now-180d` Grafana sent the markers annotation as 180 one-day Loki requests in a row.
The listing and the single-select `experiment` picker read over `lookback`, not over the dashboard
range, so torn-down clusters are listed and a 24-hour range still offers every experiment
(`ClusterVariableTest`). `resolution` holds seconds (text `5m`, value `300`), so the subquery step
is `${resolution}s` and the row window is padded by one step in PromQL. Tests' picker has no "All":
it selects one experiment. Its hidden `cluster` lists that experiment's clusters and stays on All;
the **Clusters of `${experiment:text}`** table, the annotation markers and the report panel read
the selection.

The Experiments table's row links are **Newest report**
(`reports/${__data.fields.edl_tenant}/${__data.fields.experiment}/index.html`), **Compare clusters**
(Experiment: Cassandra with the row's `var-experiment` and `var-cluster=$__all`) and **Show here**
(Tests on that experiment). The Clusters table links each cluster to System Overview and Cassandra
Overview (with `var-cluster` and the row's window as `from`/`to`), and **Compare with the current
cluster** opens the cluster pair with `var-cluster_a` set to the row's cluster and no absolute range
(the own-range rule above); B is the pair's installed default, the current cluster. The report
panel shows `reports/${experiment}/index.html`.

## Experiments

Six dashboards select one experiment: Tests, Experiment: Cassandra, Experiment: System and the
three pair dashboards (cluster pair, A/B Comparison, System A/B Comparison). An experiment is a
tenant and a name together (`ExperimentKey`), so the picker never merges two experiments of one
name in two tenants. `ExperimentPickerTest` checks the rules below on every core and kit dashboard.

- **The picker.** `experiment` is a single-select query variable whose value and text are
  `<tenant>/<name>` (`ExperimentKey.pickerValue`). It lists newest first,
  `query_result(sort_desc(max by (exp_key, exp_text) (...[$lookback:${resolution}s]...)))`, kept
  in that order by `sort: 0`. It never uses Grafana's `includeAll`, which would open on the most
  expensive query. Every picker but Tests' ends with a synthetic "All",
  `label_replace(label_replace(vector(0), "exp_key", ".*/.*", "", ""), "exp_text", "All", "", "")`,
  whose timestamp 0 sorts it last and whose value matches every tenant and name.
- **The picker regex** is exactly `/exp_key="(?<value>[^"]+)",exp_text="(?<text>[^"]+)"/`.
  Grafana builds each `query_result` row as `{k="v",k2="v2"} <value> <ts>`, joining labels with a
  comma and no space, so a two-label regex with a space after the comma matches nothing and the
  picker is empty. No two-label variable regex on any dashboard has that space.
- **Every `query_result(...)` variable query is on one line.** Grafana's Prometheus plugin
  recognises `query_result` only on one line; with a line break the variable falls through to a
  series lookup that fails.
- **The split.** The hidden `exp_tenant` and `exp_name` both run
  `query_result(label_replace(vector(1), "k", "$experiment", "", ""))`, with the regexes
  `/k="(?<value>[^/"]*)\//` and `/k="[^/"]*\/(?<value>[^"]*)"/`. With "All" both are `.*`.
- **The tenant filter.** Every cluster list on such a dashboard (`cluster`, `cluster_a`,
  `cluster_b`) filters labelled series by `edl_tenant=~"$exp_tenant"`, beside
  `experiment=~"$exp_name"`. Panels filter by `cluster` only. With "All" on the all-tenants
  datasource, every tenant's clusters show on one panel.
- **`home_tenant`** is a hidden custom variable with no options in the file; the installer gives
  it the home tenant as its one option.

### Clusters from before the label

A cluster from before the `experiment` label has neither `experiment` nor `edl_tenant` on its
series. It is an experiment of one, named for its `<name>-<id>`. `ExperimentFallbackGuards`
(checked by `ExperimentFallbackTest` on the panel targets and variable queries of every core and
kit dashboard) holds five rules:

- (a) Every PromQL query that matches on `experiment` or `edl_tenant` (other than `experiment=""`)
  also reads an `up{...experiment=""...}` branch joined by `or`. A cluster list's branch is
  `count by (cluster) (up{experiment="", cluster=~"$exp_name"})`: the experiment's name is the
  cluster's id.
- (b) A query that outputs `edl_tenant` for such a cluster, by grouping on it or building it with a
  label function, takes the tenant from Mimir's `__tenant_id__`, which a federated datasource of
  two or more tenants adds, and otherwise from `"${home_tenant}"`:
  `label_replace(label_replace(<expr>, "t", "$1", "__tenant_id__", "(.+)"), "t", "${home_tenant}", "t", "")`.
- (c) A dashboard that references `home_tenant` declares it as a hidden custom variable (type
  `custom`, `hide: 2`), the form the installer fills.
- (d) No LogQL or TraceQL query names `experiment` or `edl_tenant`. Logs and traces filter on the
  cluster only.
- (e) The `up{...experiment=""...}` branch keeps out an `up` series with no `cluster` label, with
  `cluster!=""` or a `cluster` matcher bound to a picker or to a value that cannot be empty. The
  picker's branch reads `up{experiment="", cluster!=""}`; without it, a scrape target outside every
  cluster lists as a bogus newest option `<tenant>/`.

### Experiment: Cassandra and Experiment: System

`cassandra/experiment-cassandra.json` (Experiment: Cassandra, which keeps the former Cluster
Comparison uid `c09599ec-8d87-4fe5-9346-9e85c41d33af`) and `infrastructure/experiment-system.json`
(Experiment: System, uid `experiment-system`) compare any number of an experiment's clusters, each
over its own window. Experiment: Cassandra declares no pair variable; Experiment: System declares
`role` and no host picker. `ComparisonDashboardsTest` checks:

- **Span helpers** (hidden `query_result` variables): `exp_start` and `exp_end` (the first and last
  `up` step of the selected clusters within `lookback`), `exp_len`
  (`${exp_end} - ${exp_start} + ${resolution}`), `exp_since_end` (`time() - ${exp_end}`), and a
  `_d`/`_s` pair for each of `exp_len` and `exp_since_end` (see
  [Panel time overrides](#panel-time-overrides)).
- **Pinned at the end.** The leaderboard table and the distribution bar charts are instant queries
  `by (cluster)` over `[${exp_len}s:${resolution}s] @ ${exp_end}`; the distributions use
  `quantile_over_time`. Each figure covers the cluster's own active window.
- **Every other panel with a query** carries the span override, `timeFrom`
  `${exp_len_s}s-${exp_len_d}d` and `timeShift` `${exp_since_end_s}s-${exp_since_end_d}d`, so a
  relative dashboard range still covers an experiment that ran days ago.
- **The elapsed-time overlay** is Trend panels: `merge`, then `organize` dropping `Time`, then
  `partitionByValues` on `cluster` and `cluster_name`, then `joinByField` on `Value #A` (outer),
  with `xField: "Value #A"` (hours since each cluster's start) and `maxDataPoints: 5000`. See
  [Trend panels name their x axis](#trend-panels-name-their-x-axis).
- **The report panel** shows `reports/${experiment}/index.html`, and its title says to select one
  experiment, because "All" has no single report.

### The pair dashboards

`cassandra/cluster-pair.json` (Cluster pair, uid `cluster-pair`), `cassandra/ab-comparison.json`
(A/B Comparison) and `infrastructure/system-ab-comparison.json` (System A/B Comparison) compare
cluster A and cluster B of the selected experiment, each over its own window.
`ComparisonDashboardsTest` checks:

- **The sides.** `cluster_a` and `cluster_b` are single-select `query_result` variables over
  `[$lookback:${resolution}s]`, evaluated at now, with `sort: 0`. A lists oldest first
  (`query_result(sort(min by (cluster) (min_over_time(...`) and has no installed default, so it
  opens on the experiment's oldest cluster. B lists newest first
  (`query_result(sort_desc(max by (cluster) (max_over_time(...`) and defaults to the current
  cluster. `lookback` defaults to `180d`. The dashboard range stays relative and ends at now.
- **Each side's report.** The hidden `a_report` and `b_report` read `$cluster_a` and `$cluster_b`:
  the `<tenant>/<experiment>` of that cluster's last `up` within `lookback`, with the pre-label
  fallback. A side whose cluster has no `up` sample within `lookback` has no report path; the panel
  descriptions say so.
- **Hidden helpers** (all `query_result`, evaluated at now): `a_start`/`b_start` and
  `a_end`/`b_end` (epoch seconds), `a_len`/`b_len`, `max_len`, the overlay offsets
  `a_offset`/`b_offset` (`now - max_len - start`, negative for a run that started later) and the
  side-by-side shifts `a_since_end`/`b_since_end`. Each of `max_len`, `a_len`, `b_len`,
  `a_since_end` and `b_since_end` has a `_d`/`_s` pair.
- **Every span length** (`a_len`, `b_len`, `exp_len`) is the last step minus the first plus one
  step, `${a_end} - ${a_start} + ${resolution}`: a cluster seen at one step then spans one step
  rather than 0s, which PromQL refuses as a range and Grafana as a `timeFrom`, and
  `[${a_len}s] @ ${a_end}` reaches the first step.
- **The names from before the A/B rename** are declared nowhere (`RETIRED_VARIABLES` and
  `RETIRED_BUILD_PICKERS` in `ComparisonDashboardsTest`). The build pickers are `build_a`
  and `build_b` (A/B Comparison, beside `host`), the host pickers `hosts_a` and `hosts_b` (System
  A/B Comparison); the cluster pair has neither.

Three rows sit at the top (panel ids from 1000; the older panels have lower ids):

- **Overlay**: panel `timeFrom` `${max_len_s}s-${max_len_d}d`; each side's query has
  `offset ${a_offset}s` or `offset ${b_offset}s`, so both start at the left edge.
- **Side by side**: one column per side, panel `timeFrom` `${a_len_s}s-${a_len_d}d` and `timeShift`
  `${a_since_end_s}s-${a_since_end_d}d` (and B's), so each axis shows the side's real times.
- **Summary and reports**: one instant table query that joins every figure with `or`: each figure
  over `[${a_len}s] @ ${a_end}` per side and `100 * ((B) - (A)) / ((A) != 0)`, tagged with
  `label_replace` as `figure` and `run` (`1 A`, `2 B`, `3 B vs A %`). `groupingToMatrix` (column
  `run`, row `figure`, value `Value`) makes one row per figure, and `organize` orders and names the
  columns Figure, A, B, B vs A %. Beside it both sides' reports. Keep it one query: with several,
  Grafana names the value fields `Value #A`, `Value #B`, ..., and `groupingToMatrix`, which needs
  one frame and a field named `Value`, returns the rows unpivoted. The `!= 0` filter drops a zero
  A, so that figure's B vs A % cell is empty instead of NaN (0 vs 0) or an infinite percentage;
  each summary's description says so.

These rows filter by `cluster="$cluster_a"` or `cluster="$cluster_b"` only, never by a build or
host picker. Every other delta of a pair dashboard, `label_replace(100 * (B - A) / ((A) != 0), ...)`
on the verdict tiles and the delta series, is guarded the same way. The older panels below read
`cluster` (the experiment's clusters over the dashboard range, default All) and the build or host
pickers, and match A and B on `cluster`: they compare two builds or two host sets inside each
cluster, never a host against itself in another cluster. To compare two clusters, use the top
rows. The cluster pair has no older panels: below the top rows its verdict tiles ("B measured
against A") and side-by-side latency heatmaps are run views too, read A and B only, and its
`cluster` is hidden. `DashboardQueries` gives the helper variables sample values, so
`PromQlCompatibilityIntegrationTest` runs the negative `offset`, `@` and subquery forms against the
pinned Mimir.

### Panel time overrides

Grafana's date math reads at most five digits per number, so a `timeFrom` or `timeShift` of `${x}s`
fails with "invalid timeshift" once `x` passes 99999 seconds (27.8 hours). Write each override as
`${x_s}s-${x_d}d`: `timeShift: "3600s-5d"` shifts by 1 hour and 5 days. `x_d` is whole days,
`query_result(floor(vector(${x}) / 86400))`, and `x_s` the seconds left,
`query_result(vector(${x}) - 86400 * ${x_d})`, so it reads `_d` and resolves after it. PromQL has
no such limit, so `offset` and range values stay in plain seconds. `PanelTimeOverrideTest` fails on
any `${x}s` override.

The order is load-bearing. Grafana 13.2.2's `PanelTimeRange` recomputes the override each time a helper it reads completes, but keeps the new range only when the header text (`timeInfo`) changes, and a `timeFrom` header names only the first number ("Last 0 day"). With days first, a panel whose `_s` resolved after its `_d` kept the one-second range from `0d-s` until a manual refresh. Seconds first, with `_s` computed from `_d`, makes the seconds helper complete last, and that completion always changes the header: from invalid while it is empty to "Last 9000 seconds". `PanelTimeOverrideTest` checks both the order and the `_s` query.

These panels need a relative dashboard range: an absolute range turns panel `timeFrom` off.

## Test documents on dashboards

An experiment's documents are `reports/<tenant>/<experiment>/index.html` in the account bucket
(written by `report upload`, and by `up` as a "No report yet" placeholder only when absent). A
dashboard shows them in a Text panel in HTML mode with an iframe:

```html
<iframe src="__DOCUMENTS_URL__/reports/${experiment}/index.html" width="100%" height="696"></iframe>
```

The `experiment` picker's value is `<tenant>/<name>`, so one variable gives both path parts. A pair
dashboard shows each side's report with `${a_report}` and `${b_report}`. Grafana runs with
`disable_sanitize_html` (set in `[panels]` by `GF_PANELS_DISABLE_SANITIZE_HTML`), and the
documents web server in the Grafana pod serves the file (see `configuration/CLAUDE.md`).

The Text panel sanitizer keeps only `src`, `width` and `height` on an iframe and strips `style`, so size the iframe with attributes: `width="100%"` and a pixel `height` that fills the panel. The height is the panel height, `h * 38 - 8` px, less 56 px for the header, the padding and the inline gap. `DocumentsIframeTest` checks every iframe against its panel's `gridPos.h`, and that the pair dashboards' iframes read exactly `a_report` and `b_report` and the others' exactly `experiment`.

With experiment "All" (`.*/.*`) no report folder matches and the frame shows the documents server's error page, so an Experiment dashboard's report panel says "select one experiment" in its title, not only in its description. On the pair dashboards a side whose cluster has no `up` sample within Lookback has no report path either; their descriptions say so. A data link to a report from a table row reads that row's own fields, `reports/${__data.fields.report}/index.html` (the Experiment: Cassandra leaderboard writes `report` per cluster with `label_join`, the pre-label branch included) or `${__data.fields.edl_tenant}/${__data.fields.experiment}` (Tests), never `${experiment}` (`DocumentsIframeTest`).

## Rows are placed by position, not by file order

Grafana sorts a dashboard's top-level panels by `gridPos` (y, then x) and gives each panel to the row above it in that order. A collapsed row keeps its own panels in its `panels` array, so a top-level panel that sorts under a collapsed row is hidden in it, and the row it was written under renders empty. Give every row its own `y`, below the panels of the row before it. `DashboardRowOrderTest` fails when a top-level panel sorts under a collapsed row or two rows share a `y`, for core and kit dashboards.

## Trend panels name their x axis

A Trend panel draws its `xField` with the panel's default unit unless a `byName` override on that field gives it one, so an elapsed-hours axis on a `percent` panel reads "0% ... 2.5%". Every Trend panel overrides its `xField` with a `displayName` and `unit: h` (the Experiment dashboards' "Hours since ..." axes). `TrendAxisTest` checks core and kit dashboards.

## Label Name Conventions

Mimir and Loki both turn OTel attribute names into underscore names:

| Concept      | Mimir label          | Loki label or field                          |
|--------------|----------------------|----------------------------------------------|
| Cluster      | `cluster`            | `cluster` (stream label)                     |
| Host name    | `host_name`          | `host_name` (stream label)                   |
| Node role    | `node_role`          | `node_role` (stream label)                   |
| Log source   | n/a                  | `source` (stream label)                      |
| Service name | `service_name`       | `service_name` (stream label, Loki derives it) |
| Pod name     | `k8s_pod_name`       | `k8s_pod_name`                               |
| Logger       | n/a                  | `scope_name` (structured metadata)           |
| Severity     | n/a                  | `severity_text`, and Loki's `detected_level` |
| Trace ID     | n/a                  | `trace_id` (structured metadata)             |

**Spanmetrics connector** (from OTel) produces Mimir labels: `service_name`, `span_name`, `status_code`, `db_system`. Dashboard panel queries must use these underscore-style names.

### Writing LogQL

- Every log query is scoped by the dashboard's cluster: `{cluster=~"$cluster", ...}`. Clusters of one tenant share Loki's store.
- Loki refuses a stream selector whose every matcher can match an empty value, so a `cluster` variable used in a stream selector has `allValue: ".+"`, not `".*"`.
- Only `cluster`, `host_name`, `node_role`, `source`, `service_name` and Loki's default Kubernetes labels (`k8s_container_name`, `k8s_pod_name`, ...) are stream labels. Everything else is structured metadata: filter it after the selector (`| scope_name="..."`), which works for both.
- A `Label values` variable lists stream labels only; a structured-metadata value (the logger) is a textbox regex instead.
- The collector already parses Cassandra's GC, compaction and dropped-message figures into attributes (`gc_event_ms`, `compaction_ms`, `compaction_ratio_pct`, `dropped_*`); `unwrap` those rather than re-parsing the line.
- `LogQlCompatibilityIntegrationTest` runs every dashboard LogQL query against the pinned Loki, and `PromQlCompatibilityIntegrationTest` runs every PromQL expression (variables substituted) against the pinned Mimir. A query either backend refuses fails the build.

### Annotations

Every Grafana annotation is mirrored to Loki as its own stream (`source="annotation"`, `cluster`, `annotation_id`; the text is the log line, and the tags, dashboard uid, panel id and end time are structured metadata), so it survives the cluster. Core dashboards read their markers from Loki, not from Grafana's tag query:

- Datasource: `{"type": "loki", "uid": "${logs_datasource}"}`, so the markers follow the Logs picker.
- Query: `{source="annotation", cluster=~"${cluster:regex}"} | dashboard_uid=""` — global markers only.
- `CoreDashboardAnnotationsTest` fails when a core dashboard lacks this annotation query.

## Trace Links in Log Panels — Two Distinct Mechanisms

**This is a frequent source of mistakes. Read carefully before adding any trace navigation to a logs panel.**

### Mechanism 1 — Per-row "View Trace in Tempo" button (datasource-level, global)

Configured on the `loki` datasource in `GrafanaDatasourceConfig.kt` via `derivedFields`:

```kotlin
GrafanaDerivedField(
    name = "trace_id",
    matcherType = "label",      // reads the trace_id structured-metadata field
    matcherRegex = "trace_id",
    url = "\$\${__value.raw}",   // `$$` survives Grafana's provisioning env expansion
    datasourceUid = "tempo",
    urlDisplayLabel = "View Trace in Tempo",
)
```

- Appears as a button in the **log detail drawer** when a log row has a `trace_id` field matching the regex
- Navigates to Tempo Explore with that specific trace ID pre-filled
- **This is global** — it applies to every Loki panel on every dashboard
- **Nothing in the dashboard JSON controls this** — do NOT try to configure it per-panel
- If a service has no traces (e.g. TiDB before OTel was wired up), the button simply does not appear because `trace_id` is absent or doesn't match the regex

### Mechanism 2 — Data-point links on metric/timeseries panels (dashboard JSON)

Used on timeseries panels via `fieldConfig.defaults.links`. Appears when hovering over a data point. Contains a full `/explore?panes=` URL with a TraceQL query using Grafana template variables:

```json
{
  "url": "/explore?schemaVersion=1&orgId=1&panes=<URL-encoded JSON with TraceQL>",
  "title": "View in Tempo"
}
```

The panes JSON contains `queryType: "traceql"` with a query like `{ resource.cluster =~ "${cluster:regex}" && resource.host.name = "${__field.labels.host_name}" }` and `${__from}`/`${__to}` time bounds. See the Cross-Dashboard Navigation section below for how to build these URLs correctly.

- **Logs panels (`type: "logs"`) do NOT support this mechanism** — `fieldConfig.defaults.links` is ignored on log panels
- Only works on `timeseries`, `stat`, `table`, and similar metric panel types
- Build the URL-encoded panes value with the `encode_panes` recipe in [Cross-Dashboard Navigation](#cross-dashboard-navigation-datalinks)

### Summary table

| What you want | Where to configure it | Panel types |
|---|---|---|
| "View Trace in Tempo" per log row | `GrafanaDatasourceConfig.kt` derivedFields | logs (global, auto) |
| Trace search link on hover | `fieldConfig.defaults.links` in panel JSON | timeseries, stat, table |
| Panel-level link (header menu) | `panel.links` in panel JSON | all types |

## Spanmetrics Metric Names

The OTel span_metrics connector with `namespace: traces.spanmetrics` emits:

- `traces_spanmetrics_calls_total` — request count
- `traces_spanmetrics_duration_milliseconds_bucket` — latency histogram (**milliseconds**)
- `traces_spanmetrics_duration_milliseconds_sum`
- `traces_spanmetrics_duration_milliseconds_count`

**The OTel collector is the only producer of these metrics.** Tempo's own metrics generator wrote the same names from the same spans and doubled every rate, so its processors are now empty in `configuration/tempo/tempo.yaml`. Do not query `traces_spanmetrics_latency_*` or `traces_spanmetrics_size_total`; both were Tempo-only and nothing writes them. The collector has no span-size equivalent.

Panel units for latency must be `ms` (not `s`) since the histogram buckets are in milliseconds.

## Cross-Dashboard Navigation (dataLinks)

### Grafana Explore URL Format

Always use `panes=` (NOT the legacy `left=` parameter). The `left=` format is undocumented legacy behavior and does not reliably pre-fill Tempo queries.

```
/explore?schemaVersion=1&orgId=1&panes=<URL-encoded-JSON>
```

The panes value is a JSON object URL-encoded with `urllib.parse.quote`. Grafana template variables (`${__field.labels.service_name}`, `${__from}`, `${__to}`) must **not** be URL-encoded — leave them as-is so Grafana interpolates them before navigating.

Build the URLs with this recipe, inline in the edit script. It is the one copy; there is no helper script:

```python
import json, re, urllib.parse

def encode_panes(panes_dict):
    s = json.dumps(panes_dict, separators=(',', ':'))
    vars_found = []
    def stash(m):
        vars_found.append(m.group(0))
        return f"__GV{len(vars_found)-1}__"
    s2 = re.sub(r'\$\{[^}]+\}', stash, s)
    encoded = urllib.parse.quote(s2, safe='')
    for i, v in enumerate(vars_found):
        encoded = encoded.replace(f"__GV{i}__", v)
    return encoded
```

### Tempo Explore Link (TraceQL)

Use `queryType: "traceql"` with a raw TraceQL `query` string. **Do NOT use `queryType: "traceqlSearch"` with a `filters` array** — constructing filter objects from scratch is fragile and results in an empty TraceQL box.

```python
def tempo_explore(traceql, title):
    # The picker, not the fixed uid: encode_panes leaves ${traces_datasource} unencoded.
    panes = {"a": {"datasource": "${traces_datasource}",
                   "queries": [{"refId": "A",
                                "datasource": {"uid": "${traces_datasource}", "type": "tempo"},
                                "queryType": "traceql",
                                "query": traceql,
                                "limit": 20}],
                   "range": {"from": "${__from}", "to": "${__to}"}}}
    return {"title": title,
            "url": "/explore?schemaVersion=1&orgId=1&panes=" + encode_panes(panes),
            "targetBlank": True}
```

Common TraceQL patterns. Every spanset holds the cluster matcher (see
[The cluster variable](#the-cluster-variable)); a pair dashboard matches `$cluster_a` or `$cluster_b` instead:
- By service: `{ resource.cluster =~ "${cluster:regex}" && resource.service.name = "${__field.labels.service_name}" }`
- By host: `{ resource.cluster =~ "${cluster:regex}" && resource.host.name = "${__field.labels.host_name}" }`
- By service + operation: `{ resource.cluster =~ "${cluster:regex}" && resource.service.name = "${__field.labels.service_name}" && name = "${__field.labels.span_name}" }`
- ClickHouse spans: `{ resource.cluster =~ "${cluster:regex}" && span.db.system = "clickhouse" }`

### Loki Explore Link

```python
def logs_explore(expr, title):
    panes = {"a": {"datasource": "${logs_datasource}",
                   "queries": [{"refId": "A",
                                "datasource": {"uid": "${logs_datasource}", "type": "loki"},
                                "editorMode": "code",
                                "expr": expr,
                                "queryType": "range"}],
                   "range": {"from": "${__from}", "to": "${__to}"}}}
    return {"title": title,
            "url": "/explore?schemaVersion=1&orgId=1&panes=" + encode_panes(panes),
            "targetBlank": True}
```

Common LogQL patterns (a series aggregated away from `cluster` has no `${__field.labels.cluster}`,
so scope by the dashboard variable):
- By service: `{cluster=~"${cluster:regex}", service_name="${__field.labels.service_name}"}`
- By host: `{cluster=~"${cluster:regex}", host_name="${__field.labels.host_name}"}`

### Pyroscope Explore Link

```python
def pyroscope_explore(label_selector, title):
    panes = {"a": {"datasource": "pyroscope",
                   "queries": [{"refId": "A",
                                "datasource": {"uid": "pyroscope",
                                               "type": "grafana-pyroscope-datasource"},
                                "profileTypeId": "process_cpu:cpu:nanoseconds:cpu:nanoseconds",
                                "labelSelector": label_selector}],
                   "range": {"from": "${__from}", "to": "${__to}"}}}
    return {"title": title,
            "url": "/explore?schemaVersion=1&orgId=1&panes=" + encode_panes(panes),
            "targetBlank": True}
```

Example: `label_selector = '{cluster=~"${cluster:regex}",service_name="${__field.labels.service_name}"}'`

Pyroscope has no picker, so this link keeps the fixed `pyroscope` uid.

### Dashboard Navigation Link (panel header)

Added to the panel-level `links` array (not `fieldConfig.defaults.links`). Appears in the panel `...` menu.

```python
def sysoverview_link():
    return {"title": "System Overview",
            # Pass each picker and cluster variable the source dashboard declares (the link rule).
            "url": "/d/system-overview/system-overview?from=${__from}&to=${__to}"
                   "&${metrics_datasource:queryparam}&${cluster:queryparam}",
            "targetBlank": False}
```

### Where Links Go

- `fieldConfig.defaults.links` — data point links, appear on hover over a series (timeseries panels)
- `panel.links` — panel header links, appear in the `...` menu (all panel types including logs panels)

Logs panels (`type: "logs"`) do not support `fieldConfig.defaults.links` for per-row linking. Use datasource `derivedFields` for that (configured in `GrafanaDatasourceConfig.kt`).

## tracesToLogsV2 and tracesToMetrics

Configured on the Tempo datasource in `GrafanaDatasourceConfig.kt`.

**tracesToLogsV2**: `customQuery: true` with a LogQL trace-id lookup across the selected clusters (`LogQl.traceToLogs`):

```kotlin
GrafanaTracesToLogsConfig(
    datasourceUid = "loki",
    spanStartTimeShift = "-1m",
    spanEndTimeShift = "1m",
    filterByTraceID = true,
    filterBySpanID = false,
    customQuery = true,
    query = LogQl.traceToLogs("\$\${__trace.traceId}"),   // {cluster=~".+"} | trace_id="$${__trace.traceId}"
)
```

**tracesToMetrics**: Use `traces_spanmetrics_duration_milliseconds_bucket` (not `latency_bucket`). Use `histogram_quantile(0.99, ...)` (p99, not p90). The `$$__tags` variable injects the span's service label as a Prometheus filter.

## Networking folder

`networking/` holds the Cilium dashboards. They are fed by the three scrape jobs `OtelManifestBuilder.buildCniScrapeJobs` renders only for `CniMode.Cilium`, plus kube-state-metrics:

- `cilium-datapath.json` (uid `cilium-datapath`) — job `cilium-agent` (`<node>:9962`); one `instance` per node, selectable by the `instance` variable.
- `cilium-eni-ipam.json` (uid `cilium-eni-ipam`) — job `cilium-operator` (`instance` is the operator pod name) and `kube-state-metrics` (`kube_daemonset_*{daemonset="cilium"}`, `kube_pod_status_phase{namespace="kube-system"}`).
- `hubble-flows.json` (uid `hubble-flows`) — job `hubble` (`<node>:9965`).

**On a Flannel cluster these dashboards are empty.** None of the three jobs exist, so every panel shows "No data"; that is expected, not a bug.

Things to know before editing them:

- The ICMP panel on Hubble Flows is expected to sit near zero. The security group has no ICMP rule, so only Cilium's own node health checks and IPv6 router solicitations reach the datapath. The panel description says so; keep it.
- `hubble_dns_*` and `hubble_http_*` do not exist on this stack. Those Hubble metrics are not enabled, so there are no DNS or HTTP panels. Do not add them without first enabling the metric and confirming the name in `/api/v1/label/__name__/values`.
- `histogram_quantile(..., rate(..._bucket[...]))` gives nothing useful when every bucket rate is zero in the window. For histograms that only move on rare events (conntrack GC runs, IP allocations) the panels use the cumulative mean `_sum / _count` instead, which always returns a series. Steady histograms (endpoint regeneration, k8s client latency, EC2 API duration) keep the p95.
- `cilium_operator_ipam_ips{type="needed"}` above 0 is the one number that matters on the IPAM dashboard; it has its own red-threshold stat and a red series override. Keep that visible when rearranging.

## Kit Dashboard Metric Queries

### One series per pod (no NodePort triple-scraping)

Every built-in kit is scraped by pod discovery: each pod once, by the collector on its own node,
with `instance` set to the pod name (see `configuration/CLAUDE.md`). A kit's metrics NodePort, where
one still exists, is only for manual `curl`; the collector does not scrape it. Kit dashboard queries
therefore need no per-node filter, and must not pin `host_name` to a node (e.g. `host_name="db0"`):
that label is the collector's node, which is wherever the pod was scheduled, so a pinned query goes
empty when the pod lands elsewhere. Select a pod with `instance` when a panel needs one.

A static `localhost:<port>` job on a NodePort would be scraped by every collector, one series per
node, which is why no built-in kit declares one (`NodePortKitScrapeTest`).

### Counter Naming

The local Prometheus exporter may expose counters without a `_total` suffix, but the collector's remote write stores them **with** `_total` following the OpenMetrics convention. Dashboard queries must always use the `_total` form (e.g. `cnpg_pg_stat_database_blks_hit_total`, not `cnpg_pg_stat_database_blks_hit`). Confirm the exact stored name with Mimir's `/prometheus/api/v1/label/__name__/values` (send `X-Scope-OrgID: <tenant>`) before writing a query.

## Modifying Dashboards

When adding new dashboards or modifying existing ones:

1. Edit the JSON in `dashboards/<folder>/` (or kit-specific path under `src/main/resources/.../kits/`)
2. Run `./gradlew installDist` to bundle the updated JSON into the JAR
3. Run `<cluster>/easy-db-lab grafana update-config` to push to the cluster
4. Push test data if the change affects trace/metric panels
5. Grafana's file provider re-reads the copied tree every 10 seconds — no Grafana restart needed for dashboard changes (datasource config changes do require a restart)

### Verify from Grafana, never from the deploy message

`grafana update-config` prints "All Grafana resources applied successfully!" once the tree is
copied and the Grafana resources are applied. That says nothing about whether the content changed
— if `build/resources/main/` is stale, it will recopy the old panel and still report success.

Step 2 above is not optional and nothing else substitutes for it. `ktlintFormat` does not rebuild
resources. Confirm the build actually picked up the edit:

```bash
diff <(jq -S . dashboards/infrastructure/system-overview.json) \
     <(jq -S . build/resources/main/dashboards/infrastructure/system-overview.json) \
  && echo "in sync"
```

Then read the query back from Grafana, and evaluate it through Grafana's own datasource proxy:

```bash
G=http://<control-ip>:3000
curl -s "$G/api/search" | jq -r '.[] | "\(.uid)  \(.title)"'
curl -s "$G/api/dashboards/uid/<uid>" | jq -r '.dashboard.panels[] | select(.title|test("CPU";"i")) | .targets[].expr'

DS=$(curl -s "$G/api/datasources" | jq -r '.[] | select(.type=="prometheus") | .uid' | head -1)
curl -s -G "$G/api/datasources/proxy/uid/$DS/api/v1/query" --data-urlencode 'query=<expr>'
```

Testing PromQL against Mimir directly proves the query is right. It does not prove
Grafana is serving that query. Only the second failure is the one a user sees.

### Never edit dashboard JSON with `jq`

`jq` round-trips the whole document. In this repo that produced a 1,644-line reindent of
`cluster-comparison.json` (now `experiment-cassandra.json`) for a one-line fix, and unescaped `µ` to `µ` and `—` to `—`
throughout `cassandra-overview.json`.

Use a literal, byte-preserving replacement (`perl -0pi -e` with `\Q...\E`), then check the diff is
the size you expect and the file still parses:

```bash
git diff --stat dashboards/              # expect 1 changed line per file
jq empty dashboards/<folder>/<name>.json # still valid JSON
```

### Match the panel's unit before changing scale

Check `fieldConfig.defaults.unit` and `max` before adding or removing a `* 100`:

- `percent` → 0..100, use the `100 * (...)` form
- `percentunit` (often `max: 1`) → 0..1, use the bare fraction with **no** `* 100`

`experiment-cassandra.json`'s "CPU Usage by Cluster" is `percentunit` with `max: 1`.

### `system_cpu_time_seconds_total`: never depend on the per-core `cpu` label

The per-core `cpu` attribute is opt-in on the host_metrics `cpu` scraper. The collector config
requests it (`attributes: [cpu, state]` in `otel-collector-config.yaml`), so today there is one
series per core per state. Without that setting the scraper emits **one series per host**, summed
across every core, and `avg by(host_name)` averages a single series.

This idiom shipped in four dashboards while the label was absent, rendering about -190% under load:

```promql
100 - (avg by(host_name) (rate(system_cpu_time_seconds_total{state="idle"}[1m])) * 100)   # FRAGILE
```

Without the `cpu` label, a 4-core node's idle rate is ~4.0, giving `100 - 400 = -300%`. Use idle as
a fraction of total across all states — correct with or without the label, core-count independent,
cannot leave 0..100:

```promql
100 * (1 - sum by(host_name) (rate(system_cpu_time_seconds_total{state="idle", ...}[1m]))
          / sum by(host_name) (rate(system_cpu_time_seconds_total{...}[1m])))
```

Always verify against a real multi-core node. This bug is invisible on a single core.

### Fix the class, not the reported panel

One reported negative CPU panel turned out to be four. Before concluding, grep every dashboard for
the same shape:

```bash
grep -rl '<metric>' dashboards/
jq -r '.. | objects | select(has("expr")) | .expr | select(test("<metric>"))' dashboards/*/*.json
```

Grafana's Home dashboard is set by `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` on the grafana
Deployment (`infrastructure/system-overview.json`). A bug there is the first thing a user sees, so
always check Home as well as the dashboard that was reported.
