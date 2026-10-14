## Why

Grafana runs only on a live cluster's control node. When the cluster goes away, the owner cannot open its dashboards or work on them, even though all of its data is still in the shared store in S3.

Nothing groups the clusters of one test, such as both arms of an A/B test or a series of variants run over several weeks. The only groupings today are `tenant` (the customer) and `cluster` (one cluster). A lab report belongs to one cluster, not to the test. There is no way to hand someone a test's data as one file that opens with no network and no AWS credentials.

The goal of a test is a scientific record: the raw data, the dashboards, and the lab report that interprets them, together in one unit.

Issue #902, "Local observability stack over the shared store, experiment label, one report per experiment, and offline export". It takes in #906 and #987 (owner decision 1). Cross-cluster profiles moved to #1016 (owner decision 4). Part of epic #963.

## What Changes

**Experiment label (`observability-store`, `grafana-annotations`, `opensearch`)**
- `init --experiment <name>` stores the experiment in `InitConfig` next to the tenant. With no flag, the experiment is the cluster's `<name>-<id>`, and `init` stores that value. The name rule is `^[a-z0-9][a-z0-9_-]{0,79}$`. A bad name fails `init`, states the rule, and creates nothing. `init` makes no S3 call and no cross-tenant check.
- An experiment is identified by its tenant and its name (`ExperimentKey`). The same name in two tenants is two experiments.
- The `cluster-config` ConfigMap gains the key `experiment`; workloads read it as the env var `EXPERIMENT`.
- Every place that sets `cluster` also sets `experiment` and `edl_tenant`: the OTel collector (scrape relabels and the `resource/cluster` upsert), `OtelManifestBuilder`, Loki's `index_label` list, the stress sidecar and `StressJobService`, Alloy, the Cassandra sidecar's Pyroscope agent, EMR, the presto and trino start scripts, the JFR shipper (`edl-profiling-reconcile`, which needs a Cassandra AMI rebake), Tempo's external labels, `TemplateService`/`ClusterConfigWriter`, Beyla, and the annotation mirror. `ExperimentLabelCoverageTest` fails on any new `cluster` setter that does not also set both.
- Cluster names allow `_` (`^[a-z][a-z0-9_-]{0,39}$`), so a cluster can be named for its variant, such as `unthrottled_cursor`. The OpenSearch domain name converts `_` through one shared `OpenSearchDomainName.of`, used by both `opensearch start` and provisioning. This also fixes a bug where the provisioning path could cut off the `-os` suffix.

**One report per experiment (`test-documents`)**
- `report upload <dir>` zips the directory and stores the zip unchanged as `reports/<tenant>/<experiment>/report-<yyyyMMdd-HHmmss>.zip` (UTC), written only if absent. Nothing overwrites or removes an earlier upload. It reads the experiment from the workspace state and takes no experiment flag. It works after `down`, with the operator's credentials.
- The zip is also unpacked to `report-<ts>/` next to it, each `.md` gets an HTML copy, and a generated `_files.html` lists the files. The pointer `reports/<tenant>/<experiment>/index.html` moves only forward, to the newest upload's `index.html` or `_files.html`.
- The old `report upload FILE...` form is removed. `up` writes a "No report yet" pointer only when none exists, so a second cluster of an experiment never blanks the first one's report.
- The installer-filled `doc_tenant` variable is removed.

**Local stack (`local-observability-stack`, new)**
- `observability local create <dir> [--tenant <home>]` writes a docker compose stack into `<dir>`: Mimir with its memcached, Loki, Tempo, Grafana, the documents view, and a read gate with a signer. Pyroscope comes with #1016. The directory is required.
- The live stack reads the shared store in S3 directly with the owner's AWS profile, through a gate that passes only GET and HEAD. It runs read-only components and no compactor, and writes nothing to the bucket.
- Datasources come from `GrafanaDatasourceSet` with the same UIDs as a cluster; only the URLs change. Image tags are the cluster's pins, checked by a drift test. Everything comes from packaged resources.
- `observability local dashboards <dir> [--from <dir>]` refreshes only the dashboards through Grafana's provisioning reload API.

**Export (`observability-export`, new)**
- `observability export <path>` takes exactly one selector: `--tenant <t>` (a file copy of the tenant's store), `--cluster <name-id>` (repeatable, an export by query), or `--experiment <name>` (an export by query of every cluster of that experiment in one tenant; `--tenant` names the tenant, default the workspace's tenant, or `default` outside a workspace).
- The zip holds the raw metrics, logs and traces, the docker compose stack in offline mode, the dashboards, the annotations, and every report zip of each experiment it covers. It carries no images. It is written only to the local path. Every export says profiles are not included and names #1016.
- An opened export runs with plain `docker compose up`, offline, from a filesystem backend, with retention off and no compactor.

**Grafana (`cluster-comparison-dashboard`, `multi-cluster-dashboards`, `tests-dashboard`, `observability`, `grafana-install-dashboard`)**
- `dashboards/cassandra/cluster-comparison.json` becomes `dashboards/cassandra/experiment-cassandra.json`, "Experiment: Cassandra", keeping its uid. New `dashboards/infrastructure/experiment-system.json`, "Experiment: System", uid `experiment-system`. Both pick an experiment, then any set of its clusters, and show a per-cluster leaderboard, distributions, an elapsed-time overlay, a timeline, and the experiment's report.
- New `dashboards/cassandra/cluster-pair.json`, uid `cluster-pair`, compares any two clusters exactly with `cluster_a` and `cluster_b` ("B vs A").
- `ab-comparison` and `system-ab-comparison` stay, with A/B pickers: `cluster_a`/`cluster_b`, `build_a`/`build_b`, `hosts_a`/`hosts_b`. Their `cluster` picker narrows to the selected experiment. No comparison is removed.
- The `experiment` picker's value is `<tenant>/<name>`, so an experiment dashboard reads one tenant at a time. It opens on the newest experiment. Clusters from before the label show as experiments of one.
- The Tests dashboard lists experiments: one row per experiment with its clusters and a link to its newest report.
- `ClusterFilterGuards` gains TraceQL. Every core and kit dashboard filters every query, TraceQL included, on the cluster variable. The Tests listing is the one exception.

**Account compactor (`account-compactor`)**
- Tempo's backend-worker keeps merged-away blocks for 48 hours and polls every minute, so an export and the local stack see a consistent Tempo store.

**Help and docs (`cli-help-topics`)**
- `help observability` describes experiments, the report zip upload, the local stack, and the export. `help provisioning` describes `init --experiment`, the rule, the default, and uniqueness within a tenant.
- Docs: `docs/user-guide/monitoring.md`, `docs/reference/commands.md` (`init`, `report upload`, `observability local`, `observability export`), a new local-stack and export page, `dashboards/CLAUDE.md`, the root `CLAUDE.md`, and `configuration/CLAUDE.md`.

**Folded-in debt**
- The `observability-store` spec is brought in line with the code: the Mimir local block retention (15 minutes, not 2 hours), the store-gateway caches, and the documents layout. The prefix and store-gateway contradictions are fixed by archiving #966 and #970 first.
- Root `CLAUDE.md` says the ConfigMap holds `TENANT`; the key is `tenant`.
- `ObservabilityHttp` gets an endpoint-and-tenant seam with a localhost implementation.
- `GrafanaServerSettings` is extracted from `GrafanaManifestBuilder`, so compose reuses the Grafana settings.
- The `denyDataDeletes` KDoc no longer says `report upload` replaces a document.
- The comparison picker names live in one shared constant, `configuration/grafana/DashboardVariables.kt`.
- `generateDomainName` moves out of `OpenSearchStart` into `services/aws/OpenSearchDomainName.kt`.

## Capabilities

### New Capabilities

- `local-observability-stack`: the generated docker compose stack, live over the shared store or offline over an export, and the dashboards refresh.
- `observability-export`: `observability export` with its three selectors and the offline zip.

### Modified Capabilities

- `observability-store`: experiment and `edl_tenant` labels, `_` in cluster names, the documents layout, no report overwrite, and the stale Mimir read-path text.
- `test-documents`: report zip per experiment, directory upload, forward-only pointer, `doc_tenant` removed.
- `tests-dashboard`: one row per experiment.
- `cluster-comparison-dashboard`: Experiment: Cassandra, Experiment: System, the cluster pair, and A/B pickers on the kept dashboards.
- `multi-cluster-dashboards`: experiment pickers, the pre-label fallback, one tenant at a time, TraceQL cluster filter, new defaults.
- `observability`: the renamed comparison dashboard and the Tests picker.
- `grafana-install-dashboard`: the install-time pass sets the new variables.
- `grafana-annotations`: mirrored annotations carry `experiment` and `edl_tenant`.
- `opensearch`: the domain name derived from a cluster name with `_`.
- `account-compactor`: Tempo keeps merged-away blocks for 48 hours and polls every minute.
- `cli-help-topics`: the `observability` and `provisioning` topics.

## Impact

- Code: `commands/Init.kt`, `configuration/ClusterState.kt`, `services/ClusterConfigData.kt`, the collector, Loki, Tempo, Alloy, Beyla, sidecar, stress, EMR and kit configuration, `services/AnnotationMirror.kt`, `services/documents/`, `services/ObjectStore.kt` (conditional writes), `commands/report/ReportUpload.kt`, `commands/Up.kt`, `configuration/grafana/` (`DashboardDefaults`, `GrafanaDatasourceSet`, `DashboardVariables`, `GrafanaServerSettings`), new `localstack/` and export packages, new `observability local` and `observability export` commands, `services/aws/OpenSearchDomainName.kt`, `configuration/compactor/tempo-backend.yaml`.
- Packer: `packer/cassandra/bin/edl-profiling-reconcile` and its shell test. The Cassandra AMI must be rebuilt for the JFR label.
- Dashboards: six core comparison and listing files are reworked or added, and every dashboard with TraceQL is fixed for the cluster filter, all through the `dashboard-editor` agent.
- Tests: many dashboard guard tests change their picker names; new tests for the label coverage, the pre-label fallback, the experiment picker, the domain name, the read-only stack, and the Loki ingester id with `_`.
- Archive order: issue-966, issue-970 and issue-971 must be archived before this change.
- In-flight PRs that add a `cluster` setter (for example new kits) trip `ExperimentLabelCoverageTest` until they set both labels.

## Out of scope

- #1016: every cluster's profiles from the shared store. This covers Pyroscope reading the shared store, profile datasources per tenant and for all tenants, Pyroscope in the local stack, profiles in exports, and the cross-cluster Pyroscope DLQ hazard. This change still puts the `experiment` label on profiles.
- #917: run identity inside one cluster.
- An `experiment` picker on dashboards other than Tests, the two Experiment dashboards, the cluster pair, and the two kept A/B dashboards.
- Uploading an export anywhere.
- A partial or time-ranged export.
- Publishing a prebuilt image of the local stack, or images inside an export.
- Any compactor in the local stack.
- Fixing the MCAC histogram defect.
- Splitting `events/Event.kt`, and the two meanings of `CLUSTER_NAME`. Left for later; no issue filed.
