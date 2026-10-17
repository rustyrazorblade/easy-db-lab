## Overrides existing behavior

### observability-store: Cluster names are validated (MODIFIED)
**Currently** (`openspec/specs/observability-store/spec.md`): "A cluster name SHALL match `^[a-z][a-z0-9-]{0,39}$`. `init` SHALL refuse any other name before creating infrastructure ... it SHALL NOT contain characters that break any of them."
**This change:** the rule is `^[a-z][a-z0-9_-]{0,39}$`, so a name may hold `_` (owner decision 14). Every derived name that does not accept `_` converts it; today that is the OpenSearch domain name. Adds the scenario "A cluster name with an underscore is accepted".

### observability-store: Producers read the tenant from the cluster configuration (MODIFIED)
**Currently** (baseline): "The tenant SHALL be published in the `cluster-config` ConfigMap ... The OTel collector, Alloy, Tempo, Mimir and Loki SHALL read it from there through an environment variable."
**This change:** the ConfigMap also holds the experiment, under the lowercase keys `tenant` and `experiment`, read as `TENANT` and `EXPERIMENT`. Adds the scenario "The experiment is published after up".

### observability-store: Every series and every log line carries its cluster (MODIFIED)
**Currently** (baseline): "Every metric series and every log line SHALL carry `cluster=<name>-<id>`. Loki SHALL index `cluster`, `host.name`, `node_role` and `source` as labels."
**This change:** every metric series, log line, span, eBPF profile, JFR profile and mirrored annotation also carries `experiment` and `edl_tenant`, at every place that sets `cluster`. Loki also indexes `experiment` and `edl_tenant`. A unit test walks every registered setter and scans for unregistered ones. Adds four scenarios. The reader rules are unchanged.

### observability-store: Clusters in one tenant never overwrite each other (MODIFIED)
**Currently** (baseline): "Each Loki ingester SHALL be named `<tenant>.<name>-<id>`, so every Loki index file name carries its tenant and cluster."
**This change:** a cluster name with `_` gives a valid ingester name. Adds the scenario "An ingester name with an underscore works".

### observability-store: Observability data lands in one layout in the account bucket (MODIFIED)
**Currently:** issue-966's delta is the effective text: the `mimir`, `loki`, `tempo`, `pyroscope` roots and `grafana/annotations/<tenant>/`, and no `observability` or `observabilitymetrics` prefix. (The main spec still names the pre-966 prefixes.)
**This change:** adds the reports layout, `reports/<tenant>/<experiment>/`, with a pre-label cluster's folder `reports/<tenant>/<name>-<id>/` as its experiment of one. Adds the scenario "Reports location". Every other rule of issue-966's text is unchanged.

### observability-store: The cluster cannot delete observability objects (MODIFIED)
**Currently:** issue-971's delta is the effective text (built on issue-970's): the instance role denies deletes under `mimir/`, `loki/`, `tempo/`, `grafana/` and `reports/`; "Deletes made by the owner from a workstation SHALL NOT be affected, so `report upload` can still replace a document."
**This change:** drops "so `report upload` can still replace a document". No command of the tool overwrites or deletes a report zip; the pointer is the only rewritten object under `reports/`. Adds the scenario "A report upload never replaces a report". The denies are unchanged.

### observability-store: Mimir reads the whole shared store (MODIFIED)
**Currently:** issue-970's delta (ADDED there): "The ingester SHALL keep local blocks for 2 hours (`blocks_storage.tsdb.retention_period: 2h`)."
**This change:** matches the code since issue 988: local blocks are kept for 15 minutes; the store-gateway caches and the results cache use the memcached sidecar; query sharding is off. The scenario "Local blocks are kept for 2 hours" becomes "Local blocks are kept for 15 minutes"; adds "Reads are cached in memcached". This is debt item 1.

### grafana-annotations: Grafana annotations are mirrored to Loki (MODIFIED)
**Currently** (`openspec/specs/grafana-annotations/spec.md`): "Each annotation SHALL be its own Loki stream, labelled with `cluster`, `source="annotation"` and its Grafana id ..."
**This change:** each stream is also labelled with `experiment` and `edl_tenant`. Adds the scenario "A mirrored annotation carries the experiment and the tenant". The "outside Loki's window" scenario is restated without a GIVEN line; its behavior is unchanged.

### test-documents: report upload stores documents in the test's folder (MODIFIED)
**Currently:** issue-971's delta: "A command `report upload FILE...` ... SHALL upload one or more files to the cluster's document folder, `reports/<tenant>/<name>-<id>/` ... Uploading a file with the name of an existing document SHALL replace it."
**This change:** `report upload <dir>` zips the directory and stores the zip unchanged as `reports/<tenant>/<experiment>/report-<yyyyMMdd-HHmmss>.zip` (UTC), written only if absent. Nothing is replaced. The report belongs to the experiment, so every cluster of it shows the same report.

### test-documents: report upload renders each document and rebuilds the test's index (MODIFIED)
**Currently:** issue-971's delta: "`report upload` SHALL convert each markdown file to HTML ... It SHALL then rewrite one `index.html` for the test from every `.md` document in the folder ..."
**This change:** the zip is unpacked to `report-<ts>/` with an HTML copy of each `.md` and a generated `_files.html`. A forward-only pointer `index.html` leads to the newest upload's `index.html` or `_files.html`.

### test-documents: up writes an empty index for the test (MODIFIED)
**Currently:** issue-971's delta: "`up` SHALL rebuild the test's `index.html` ... When the test has no documents, the index SHALL say "No documents yet"."
**This change:** `up` writes a "No report yet" pointer only when none exists, and never changes an existing one, so a second cluster of an experiment does not hide the first one's report.

### test-documents: report upload accepts only markdown files with safe names (REMOVED)
**Currently:** issue-971's delta: "`report upload` SHALL accept only `.md` files whose names match `[A-Za-z0-9._-]+`. It SHALL reject `index.md`, any other file type ..."
**This change:** Removed. Replaced by the ADDED "report upload takes one directory whose files the documents server can serve": any file type, path segments `[A-Za-z0-9._-]+`, no symlinks or `..`, root `_files.html` reserved, no `X.md` beside `X.html`, `index.md` allowed as the front page, and the file-list form gone.

### test-documents: A doc_tenant variable selects the documents' tenant folder (REMOVED)
**Currently:** issue-971's delta: "Every dashboard that shows documents SHALL declare one `doc_tenant` custom variable. The installer SHALL fill its options from the tenant list ..."
**This change:** Removed — no replacement variable of that kind. Dashboards read the tenant from `edl_tenant` and the `<tenant>/<name>` picker value, with a hidden `home_tenant` fallback for pre-label clusters (owner decision 11).

### tests-dashboard: A Tests dashboard lists every test of the selected tenant (MODIFIED)
**Currently:** issue-971's delta: "A core dashboard "Tests", uid `tests`, SHALL list every cluster of the tenant ... one row per cluster ... The dashboard SHALL declare the datasource pickers, a single-select `cluster` variable for the selected test, `lookback`, and `doc_tenant`."
**This change:** one row per experiment (tenant and name), with its clusters, first start, last end and cluster count; a table of the selected experiment's clusters; pre-label clusters as experiments of one. A single-select `experiment` replaces `cluster`; `home_tenant` replaces `doc_tenant`.

### tests-dashboard: Each test row links to dashboards for that test (MODIFIED)
**Currently:** issue-971's delta: per-cluster rows link to System Overview, Cassandra Overview, "Compare with the current cluster" (opens `cluster-comparison` with `baseline_cluster`), and "Show documents"; links carry the pickers and `doc_tenant`.
**This change:** experiment rows link to "Newest report", "Compare clusters" (Experiment: Cassandra with `var-experiment`) and "Show here". Cluster rows of the selected experiment keep System Overview and Cassandra Overview and link "Compare with the current cluster" to the cluster pair with `cluster_a`. Links carry the pickers and the experiment.

### tests-dashboard: The Tests dashboard shows the selected test's documents (MODIFIED)
**Currently:** issue-971's delta: "`reports/${doc_tenant}/${cluster}/index.html` ... A test with no documents shows "No documents yet"."
**This change:** shows `reports/${experiment}/index.html`; "No report yet" when empty; annotation markers follow the experiment's clusters.

### cluster-comparison-dashboard: Cluster comparison dashboard exists and is registered (MODIFIED)
**Currently** (`openspec/specs/cluster-comparison-dashboard/spec.md`): "A Grafana dashboard named "Cluster Comparison" SHALL exist at `dashboards/cassandra/cluster-comparison.json` ..." and a scenario saying it is provisioned as a ConfigMap.
**This change:** "Experiment: Cassandra" at `dashboards/cassandra/experiment-cassandra.json`, keeping the uid; the old file is gone. The stale ConfigMap scenario is replaced by the dashboard tree copy.

### cluster-comparison-dashboard: Dashboard uses shared cluster variable pattern (MODIFIED)
**Currently:** issue-971's delta: "It SHALL also include the `baseline_cluster` and `candidate_cluster` single-select variables, which replace the former `cluster_a` and `cluster_b`."
**This change:** Experiment: Cassandra has `experiment`, the hidden split and `home_tenant` variables, and a `cluster` multi-select of the experiment's clusters, default All. It declares none of `cluster2`, `baseline_cluster`, `candidate_cluster`, `cluster_a` and `cluster_b`.

### cluster-comparison-dashboard: Latency section with multiple representations (MODIFIED)
**Currently** (baseline): four latency panels, including "Latency Distribution Heatmap (one per cluster, side by side for two clusters)".
**This change:** three per-cluster latency panels stay on Experiment: Cassandra, the aggregate being the slowest node's p99 gauge; the cluster pair shows A's and B's Percentile Ladders side by side; no latency heatmap remains, since the latency series are per-node percentile gauges with no histogram buckets (owner decision 21).

### cluster-comparison-dashboard: Comparison views sit in three new top rows with a curated set of figures (MODIFIED)
**Currently:** issue-971's delta: the three rows on `cluster-comparison`, `ab-comparison` and `system-ab-comparison`.
**This change:** the three rows are on the cluster pair, `ab-comparison` and `system-ab-comparison`; Experiment: Cassandra has none.

### cluster-comparison-dashboard: The overlay view shows both runs from a common start (MODIFIED)
**Currently:** issue-971's delta, in terms of the baseline and the candidate.
**This change:** the same mechanism in terms of `cluster_a` and `cluster_b`, with legends `A <short name>` and `B <short name>`.

### cluster-comparison-dashboard: The side-by-side view shows each run on its own time range (MODIFIED)
**Currently:** issue-971's delta, one column per baseline and candidate.
**This change:** one column per side, A then B. Mechanism unchanged.

### cluster-comparison-dashboard: The summary table shows each run's figures over its own window (MODIFIED)
**Currently:** issue-971's delta: "the difference in percent, `100 * (candidate - baseline) / baseline`."
**This change:** columns "A", "B" and "B vs A %", `100 * (B - A) / A`, empty when A is 0. Adds the scenario "A zero A value gives no percentage".

### cluster-comparison-dashboard: Comparison dashboards show both runs' documents next to the summary (MODIFIED)
**Currently:** issue-971's delta: "Each side SHALL show the run's `index.html` from `reports/${doc_tenant}/<run>/` ... One `doc_tenant` variable serves both sides."
**This change:** each side shows `reports/<tenant>/<experiment>/index.html` for that cluster's own tenant and experiment, read from its series; a pre-label cluster shows `reports/<tenant>/<name>-<id>/index.html`. "No report yet" when empty.

### cluster-comparison-dashboard: Comparison dashboards select a baseline run and a candidate run (REMOVED)
**Currently:** issue-971's delta: `baseline_cluster` and `candidate_cluster` on all three comparison dashboards, both defaulting to the current cluster.
**This change:** Removed. Replaced by the ADDED "Pair dashboards select cluster A and cluster B": `cluster_a` (oldest, no default) and `cluster_b` (current cluster, newest locally) on the cluster pair, `ab-comparison` and `system-ab-comparison`, listed from the selected experiment. The build and host pickers become `build_a`/`build_b` and `hosts_a`/`hosts_b` (owner decisions 7, 8, 9, 19).

### multi-cluster-dashboards: All dashboards have a cluster multi-select variable (MODIFIED)
**Currently:** issue-971's delta: `label_values(up, cluster)` on `${metrics_datasource}`, default the current cluster; the Tests `cluster` is single-select; every metrics and logs query filters by `cluster=~"$cluster"`; list exceptions `cluster`, `baseline_cluster`, `candidate_cluster`.
**This change:** on a dashboard that declares `experiment`, `cluster` lists only the experiment's clusters and defaults to All. Tests has `experiment` in place of `cluster`. The filter rule covers traces as well. List exceptions are `cluster`, `cluster_a`, `cluster_b`, `experiment`, `exp_tenant`, `exp_name`.

### multi-cluster-dashboards: Dashboard links carry the pickers and the selected runs (MODIFIED)
**Currently:** issue-971's delta: carried variables `metrics_datasource`, `logs_datasource`, `traces_datasource`, `cluster`, `baseline_cluster`, `candidate_cluster` and `doc_tenant`.
**This change:** carried variables are `metrics_datasource`, `logs_datasource`, `traces_datasource`, `cluster`, `cluster_a`, `cluster_b` and `experiment`. Adds "A drill-down keeps the experiment".

### multi-cluster-dashboards: Installed dashboards default to the current cluster (MODIFIED)
**Currently:** issue-971's delta: the pass sets the pickers, `cluster`, `baseline_cluster` and `candidate_cluster` to the current cluster, and `doc_tenant`'s options and default.
**This change:** the pass also runs for the local stack and exports; it sets `cluster` (All on dashboards declaring `experiment`), `cluster_b` to the current cluster, no default for `cluster_a`, `experiment` to `<tenant>/<name>`, and `home_tenant`. The live local stack sets no `cluster`, `cluster_b` or `experiment`. `doc_tenant` is gone.

### multi-cluster-dashboards: Series keep their cluster (MODIFIED)
**Currently:** issue-971's delta: the comparison views' baseline and candidate queries are exempt from grouping; legends like `baseline test-1a2b3c4d`.
**This change:** the pair views' A and B queries are exempt the same way, with legends like `A test-1a2b3c4d`; the experiment variables join the exempt cluster lists.

### multi-cluster-dashboards: System dashboards have a role picker (MODIFIED)
**Currently:** issue-971's delta: `role` on System Overview and System A/B Comparison; host pickers `hostname`, `baseline` and `candidate`.
**This change:** `role` also on Experiment: System; the System A/B host pickers are `hosts_a` and `hosts_b`. Adds "The A/B host pickers follow the role".

### grafana-install-dashboard: grafana install command uploads a dashboard JSON (MODIFIED)
**Currently:** issue-971's delta: the pass sets "`cluster`, `baseline_cluster` and `candidate_cluster` defaults, `doc_tenant` options and default".
**This change:** the pass sets the `cluster`, `cluster_b` and `experiment` defaults and `home_tenant`. Adds "Install sets the experiment".

### observability: Grafana Dashboards (MODIFIED)
**Currently:** issue-971's delta: the Tests dashboard's `cluster` is single-select; scenario "Cluster comparison dashboard appears in Grafana" copies `dashboards/cassandra/cluster-comparison.json`.
**This change:** Tests has an `experiment` variable in place of `cluster`; the scenario becomes "Experiment comparison dashboard appears in Grafana" for `experiment-cassandra.json`; the pair views may scope by a pair variable; the same install rule applies to the local stack and exports.

## Conflicts with other in-flight changes

**issue-966** (merged, not archived) modifies `observability-store` ("Observability data lands in one layout in the account bucket", "The cluster cannot delete observability objects", "Data reaches S3 while the cluster is up and survives a restart"), `cluster-lifecycle`, `grafana-annotations` ("Back up Grafana annotations to an account-level S3 location") and `cloudwatch-metrics-export`.
- **Ordered, not incompatible: "Observability data lands in one layout in the account bucket".** This change's MODIFIED block restates issue-966's text and adds only the reports layout. Archive issue-966 first.
- **Ordered, not incompatible: "The cluster cannot delete observability objects".** issue-966, issue-970 and issue-971 all modify it; this change starts from issue-971's text, the newest. Archive 966, 970, 971, then this change.
- **No conflict: `grafana-annotations`.** issue-966 modifies the backup requirement; this change modifies only "Grafana annotations are mirrored to Loki".
- **No conflict: `cluster-lifecycle`, `cloudwatch-metrics-export`.** This change has no delta there.

**issue-970** (merged, not archived) adds `account-compactor`, modifies `cluster-lifecycle`, and in `observability-store` adds "Mimir reads the whole shared store" and "Grafana has a datasource per tenant and one for all tenants", modifies "No observability backend deletes data automatically" and "The cluster cannot delete observability objects", and removes "Mimir reads only the cluster's own data".
- **Ordered, not incompatible: "Mimir reads the whole shared store".** This change modifies issue-970's ADDED requirement (2 hours to 15 minutes, memcached caches). It needs issue-970 archived first, or the MODIFIED header has nothing to apply to.
- **Ordered, not incompatible: `account-compactor`.** This change adds one requirement to the capability issue-970 creates; archive issue-970 first.
- **No conflict:** "Grafana has a datasource per tenant and one for all tenants" is used, not modified; "No observability backend deletes data automatically" is not modified.

**issue-971** (merged, not archived) adds or modifies `cluster-comparison-dashboard`, `multi-cluster-dashboards`, `test-documents`, `tests-dashboard`, `observability`, `grafana-install-dashboard` and `observability-store`, plus `cassandra`, `cloudwatch-metrics-export`, `server`, `spark-emr` and `tool-execution`.
- **Actual conflict by design, resolved by ordering:** this change modifies or removes issue-971's requirements in `cluster-comparison-dashboard` (the baseline/candidate requirement is removed; six are modified), `multi-cluster-dashboards` (five modified), `test-documents` (three modified, two removed), `tests-dashboard` (three modified), `observability` ("Grafana Dashboards"), `grafana-install-dashboard`, and `observability-store` ("The cluster cannot delete observability objects"). Each block restates issue-971's text with the owner's new decisions. The behaviors differ on purpose (owner decisions 6 to 12). issue-971 must be archived before this change; archiving this change first would apply its MODIFIED and REMOVED headers to requirements that do not exist yet in the main specs.
- **No conflict:** `cassandra`, `cloudwatch-metrics-export`, `server`, `spark-emr`, `tool-execution`; this change has no delta there.

**issue-1000** (`cluster-lifecycle`, `end-to-end-testing`, `env-file-config`, `kit-lifecycle-hooks`, `networking`, `typed-install-steps`, `workload-runner`): no shared capability. No conflict.

**issue-992** (`ami-building`, `cassandra`): no shared capability. The Cassandra AMI rebake in this change is a task, not a spec change. No conflict.

**ssm-packer-builds** (`ami-building`) and **ssm-ssh-transport** (`networking`, `setup`): no shared capability. No conflict.
