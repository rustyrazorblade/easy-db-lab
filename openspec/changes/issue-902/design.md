## Context

Every cluster writes all of its observability data to one shared store in the account bucket: Mimir at `mimir/`, Loki at `loki/` (index under `loki/index/`), Tempo at `tempo/`, Pyroscope at `pyroscope/`, annotation backups at `grafana/annotations/<tenant>/`, and test documents at `reports/<tenant>/<name>-<id>/`. Mimir on every cluster reads the whole store through its store-gateway. One account compactor on Fargate compacts Mimir, Loki and Tempo with retention off and rewrites Mimir's bucket indexes every minute. Grafana has stable `mimir`, `loki`, `tempo` and `pyroscope` datasources on the cluster's own tenant, plus `<signal>-<tenant>` and `<signal>--all` for metrics, logs and traces (`GrafanaDatasourceSet`).

Issue #971 added the datasource pickers, the Tests dashboard, the two-run comparison views on `cluster-comparison`, `ab-comparison` and `system-ab-comparison` (`baseline_cluster`/`candidate_cluster`), and test documents with a `doc_tenant` variable. Issues #966, #970 and #971 are merged but not archived. Their deltas are the current contract.

The design comes from three architect rounds and a final addendum (2026-10-13 to 2026-10-14), each followed by a `design-critic` pass. The owner decided at the design stop (owner decisions 1 to 20 below). Where the architect's text and an owner decision differ, the owner decision applies. Kotlin paths are relative to `src/main/kotlin/com/rustyrazorblade/easydblab/`; dashboard paths are relative to `dashboards/`.

Facts that shaped the design:

- Mimir tenant federation is on; a multi-tenant query gives each series `__tenant_id__` (`configuration/mimir/mimir.yaml`). With one tenant, federation does not merge and the label is absent.
- Grafana's Prometheus datasources use POST (`GrafanaDatasourceSet.kt:149`), so a long `cluster=~"a|b|..."` is not URL-limited.
- The pinned Grafana is 13.2.2 (`configuration/grafana/GrafanaManifestBuilder.kt:48`).
- `ab-comparison` compares Cassandra builds (`baseline`/`candidate` over `cassandra_build`); `system-ab-comparison` compares host groups within a cluster (over `host_name`).
- `baseline_cluster`/`candidate_cluster` are hard-coded in six places (`DashboardDefaults.kt:52`, `ClusterFilterGuards.kt:37,40`, `SeriesClusterGuards.kt:53`, `ClusterShortNameTest.kt:160,165`, `DashboardDatasourceGuards.kt:35-37`).
- `ClusterFilterGuards.Language` is `PROMQL, LOGQL, PROFILES`, with no TraceQL. TraceQL has 61 uses, nearly all in Explore `panes=` links.
- The documents nginx accepts `^/reports/[A-Za-z0-9._/-]+$`, GET only (`configuration/grafana/documents-nginx.conf:30-38`).
- `up` rebuilds the documents index on every run (`commands/Up.kt:162`). With experiment-keyed reports, a second cluster's `up` would blank the first cluster's report.
- `ObjectStore` has no conditional write (`services/ObjectStore.kt:146-149`). AWS SDK 2.48.3 supports `If-None-Match` and `If-Match`.
- Tempo's fallback poll writes the tenant index after a full poll, even on a reader that builds no index. Only the read gate stops a local Tempo from writing `tempo/<t>/index.json.gz` (critic round 3 M4).
- Only the OpenSearch provisioning path breaks on `_` in a cluster name today (owner decision 14). Bucket names use only the cluster id.

## Goals / Non-Goals

**Goals:**
- An `experiment` label, with `edl_tenant`, on every signal at every place that sets `cluster`.
- One report per experiment, kept forever, shown on a cluster and in the local stack.
- A local Grafana over the live shared store that needs no cluster and writes nothing.
- An export zip that opens offline with plain `docker compose up`.
- Experiment dashboards for any number of clusters, an exact two-cluster pair, and the kept build and host-group comparisons.
- Every dashboard query, TraceQL included, filters on the cluster variable.
- Pay down the nearby debt listed below.

**Non-Goals:**
- Profiles from the shared store, profile datasources per tenant, Pyroscope locally, and profiles in exports (#1016).
- Run identity inside one cluster (#917).
- Images in the export, or a published local-stack image.
- A compactor in the local stack.
- A partial or time-ranged export, or uploading an export.
- An experiment registry, or any cross-tenant check at `init`.
- Merging same-named experiments of two tenants on one dashboard.

## Owner decisions

The binding list is in `.spec-flow/owner-decisions.md`. In short:

1. #906 and #987 closed as duplicates of #902.
2. This run may provision its own throwaway clusters.
3. After round 1 the design went back for a second round.
4. Cross-cluster profiles moved to #1016. This change keeps the `experiment` label on profiles. The local stack and the export cover metrics, logs and traces and say profiles are not included.
5. An experiment is (tenant, name). Names are unique within a tenant. No cross-tenant check, no registry. `export --experiment` takes `--tenant` (default the workspace's tenant, or `default`).
6. No baseline/candidate model and no arm. Pick an experiment, then any set of its clusters. The cluster is the variant.
7. Keep a two-cluster view. Its pickers list the selected experiment's clusters; experiment "All" lists every cluster.
8. The pair pickers are `cluster_a` and `cluster_b`; the difference reads "B vs A".
9. Keep `ab-comparison` and `system-ab-comparison`, with A/B pickers (`build_a`/`build_b`, `hosts_a`/`hosts_b`). Nothing that compares is removed.
10. Same-named experiments in two tenants stay apart. An experiment dashboard works in one tenant at a time.
11. Add an `edl_tenant` label at every place that sets `cluster`. Dashboards read the tenant from the data; the install-time `doc_tenant` is retired.
12. Pre-label clusters are each an experiment of one, through an `or` branch on series with no `experiment` label. Their report link resolves to the existing `reports/<tenant>/<name>-<id>/`.
13. With nothing selected, an Experiment dashboard opens on the newest experiment. "All" must be picked. The spike measures a cold open on "All" over 180 days and the Trend overlay at 3 weeks. The local stack runs memcached as the cluster does.
14. Cluster names allow `_`. Change `CLUSTER_NAME_PATTERN`, clean the OpenSearch domain name with the existing function, and add a Loki integration test with `_` in the ingester id.
15. Offline open assumes the images are already on the machine. No images and no `open.sh` in the zip; plain `docker compose up`; default pull policy.
16. The owner accepted the 17 remaining recommendations: D1, D3, D6, D7, D8, D10, D11, D12, D13, D15, D16, D17, D18, D19, m4, m5, and the debt fold-ins. `Event.kt` split and `CLUSTER_NAME` meanings are left for later, with no issue filed.
17. A final focused critic pass; the architect addendum closes its findings.
18. On `ab-comparison` and `system-ab-comparison`, the `cluster` multi-select narrows to the selected experiment, over the dashboard range, default "All".
19. Pair defaults: A is the experiment's oldest cluster; B is the current cluster on a cluster's Grafana and the newest cluster locally. A leaderboard row link sets only A.
20. A pre-label cluster's tenant comes from Mimir's `__tenant_id__` when present (all-tenants view), and the home tenant only when it is absent.

## Decisions

### 1. Experiment and tenant labels (D1, D2, owner decisions 5, 11, 14)

- `InitConfig.experiment: String?` sits next to `tenant` (`ClusterState.kt:132`). `ClusterState.experiment()` works like `tenant()` (`:368`). `init` stores the resolved value. The default is `clusterLabelName()`, `<name>-<id>`, at most 77 characters, which fits the 80-character rule.
- `Constants.Observability.EXPERIMENT_PATTERN = ^[a-z0-9][a-z0-9_-]{0,79}$`, checked in `Init.validateParameters()` (`Init.kt:394-400`). A bad name fails, states the rule, and creates nothing. Neither the tenant nor the experiment pattern allows `/`.
- `init` makes no S3 call and no cross-tenant check.
- New `ExperimentKey(tenant, name)`. It is the one place that says an experiment is (tenant, name). `init` checks both patterns through it. Report, export and dashboard defaults take it. `ExperimentKey.pickerValue` (`<tenant>/<name>`) is the only builder of the picker value.
- `ClusterConfigData.of` (`services/ClusterConfigData.kt:27-43`) adds the key `experiment`; the env var is `EXPERIMENT` (D1, matching the lowercase `tenant` key).
- Every `cluster` setter also sets `experiment` and `edl_tenant`: the collector's scrape relabels and the `resource/cluster` upsert (`configuration/otel/otel-collector-config.yaml`), `OtelManifestBuilder`, `loki.yaml` `index_label` (`:98-103`, and the "these four" comment at `:93`), the stress sidecar config and `StressJobService`, Alloy (`config.alloy`, `PyroscopeManifestBuilder`), `SidecarManifestBuilder` (`-Dpyroscope.labels`), `EMRProvisioningService` (`PYROSCOPE_LABELS`), the presto and trino `start.sh.template`s (reading `.data.experiment` from `cluster-config`), the JFR shipper (`edl-profiling-reconcile`, `ProfilingConfig`, `SetupInstance`; Cassandra AMI rebake), `AnnotationMirror` stream labels, `tempo.yaml` external labels, `TemplateService`/`ClusterConfigWriter`, and `BeylaManifestBuilder`. Traces carry `resource.experiment` and `resource.edl_tenant`.
- The tenant label is `edl_tenant`, not `tenant`, because Tempo's own metrics already carry a `tenant` label that the collector scrapes (critic round 3 m2).
- `ExperimentLabelCoverageTest` (unit tier) has two layers. Layer 1 renders and inspects each registered artifact: every collector pipeline that runs `resource/cluster` also stamps both labels; every relabel list that sets `cluster` sets both; Alloy, the sidecar and stress args, EMR, the kit templates, the annotation mirror streams, and `loki.yaml` index labels each carry both. Layer 2 is a catch-all scan of `src/main/kotlin`, `src/main/resources` and `packer/` for cluster-setting patterns (`target_label: cluster`, `targetLabel = "cluster"`, `cluster=` in Pyroscope label strings, `"cluster" to`, `key: cluster`, `target_label = "cluster"`). A hit in an unregistered file fails with "unregistered cluster setter". The JFR shipper is checked in `edl-profiling-reconcile.test.sh`.
- `Constants.CLUSTER_NAME_PATTERN` becomes `^[a-z][a-z0-9_-]{0,39}$` (checked at `Init.kt:390` and `ClusterStateManager.kt:56`).
- `generateDomainName` (`commands/opensearch/OpenSearchStart.kt:153-164`) moves to `services/aws/OpenSearchDomainName.kt` as `object OpenSearchDomainName { fun of(clusterName: String): String }`, called from `OpenSearchStart.kt:81` and `ClusterProvisioningService.kt:430`. Today the provisioning path uses `"${name}-os".take(28)`, which can cut the suffix, and the two paths disagree for long names; the move fixes that bug. It trims the base, not the joined string (`trimEnd` at `:164` never trims). `OpenSearchDomainNameTest` checks `_` to `-`, at most 28 characters, and `-os` intact.
- Loki's ingester id `${TENANT}.${CLUSTER_NAME}` (`loki.yaml:73`) now can hold `_`; an integration test proves Loki accepts it and its index files carry it.

### 2. How dashboards learn an experiment's tenant (D2, addendum M3 to M5)

- The `experiment` picker's option value is `<tenant>/<name>` (for example `acme/e1`), and its text is the same. The value is also the report folder: `reports/${experiment}/index.html`.
- Hidden split variables (`hide: 2`): `exp_tenant` = `query_result(label_replace(vector(1), "k", "$experiment", "", ""))` with regex `/k="(?<value>[^/"]*)\//`; `exp_name` uses the same query with regex `/k="[^/"]*\/(?<value>[^"]*)"/`.
- "All" is a synthetic last option, value `.*/.*`, text `All`. It is not Grafana's `includeAll`. It gives `exp_tenant` = `exp_name` = `.*`.
- `home_tenant` is a hidden custom variable that the install pass fills with one option: the cluster's tenant, `local create --tenant`, or the export's tenant. It replaces `doc_tenant`.
- Panels filter on `cluster` only. Cluster ids are unique across tenants.
- Every cluster list filters `edl_tenant=~"$exp_tenant"`. The pre-label branch matches `cluster=~"$exp_name"`, because a pre-label experiment's name is its globally unique cluster id. `default/e1` and `acme/e1` are two options. With "All" and `mimir--all`, every tenant's clusters show on one panel (owner decision 10).

### 3. Report zip (D3, m4)

Layout under `reports/<tenant>/<experiment>/`:

- `report-<yyyyMMdd-HHmmss>.zip` (UTC): the exact bytes the command built, written with `If-None-Match: *`. If that second is taken, it moves to the next free second. Never overwritten.
- `report-<ts>/`: that zip unpacked from the zip itself. Each `.md` gets an HTML copy. Every upload gets a generated `report-<ts>/_files.html` listing its files.
- `index.html`: the pointer. It is the only object that is ever rewritten, and it is derived, not data. It targets `report-<ts>/index.html` when the upload has `index.md` or `index.html`, else `report-<ts>/_files.html`.

The pointer moves only forward (D3). It names its target timestamp in a comment line. An upload reads the pointer and its ETag. If the pointer already names an equal or newer report, it is left alone. Otherwise the upload PUTs with `If-Match: <etag>`, or `If-None-Match: *` when there is none. On 412 it re-reads and repeats. Timestamps are UTC. The remaining gap is clock skew between two machines that upload within seconds.

Command: `ReportUpload` takes `@Parameters(arity = "1") dir: File`. `report upload a.md b.md` fails and names the directory form. The experiment comes from the workspace state; there is no flag. Validation runs before anything is built: the directory exists and is not empty; every relative path segment matches `[A-Za-z0-9._-]+`; no symlinks; no `..`; a root `_files.html` is rejected; any `X.md` with a sibling `X.html`, at any depth, is rejected and both are named. Every offender is named and nothing is uploaded. Root `index.md` or `index.html` is allowed as the front page.

`TestDocumentService.upload` (`services/documents/TestDocumentService.kt:52-130`) is replaced by `ReportArchiveService` (orchestrates), `ReportZip`, `ReportUnpacker`, and `ReportPointer`. The event is `Report.Uploaded(zipKey, unpackedPrefix, pointerAdvanced, files)`. `Up.kt:162` becomes `ReportPointer.ensurePlaceholder(key)`, written with `If-None-Match: *` only when no pointer exists, saying "No report yet".

New `ObjectStore` methods: `putIfAbsent(content, path, contentType): Written | AlreadyExists`, `putIfMatch(content, path, etag): Written | PreconditionFailed`, and `readVersioned(path): Versioned(content, etag)?`.

The `denyDataDeletes` KDoc (`providers/aws/AWSPolicy.kt:217-222`) no longer says `report upload` replaces a document.

### 4. Local stack: `observability local create <dir>`

- Package `localstack/`: `LocalStackSpec` with sealed `Mode`: `Live(bucket, region, awsProfile, home)` and `Offline(dataDir, home)`. `ComposeFile` and `ComposeService` are `@Serializable` and written with kaml. `LocalStackComposer` turns a spec into `Map<relativePath, bytes>`; `LocalStackWriter` writes it.
- The base file is `compose.yml`, so a user's `compose.override.yml` merges automatically. It cannot collide with the repo's `docker-compose.yml`.
- Backend configs are the cluster's own resource files with flag overrides, as `CompactorTaskDefinition.kt` does. Everything comes from the classpath.
- The manifest `<dir>/.easy-db-lab-local-stack.json` records the tool version, the mode, the home tenant, the tenant list, the sha256 of each owned file, and the Grafana port.
- A missing or empty directory is created. A non-empty directory without the manifest is refused before any write. A re-run rewrites only owned files, atomically (`AtomicFileWrite`). An owned file the user edited is overwritten and reported (D10). `data/`, `cache/`, `grafana-data/` and `compose.override.yml` are never opened.
- `LocalImages` takes every pin from the builder constants: Mimir, Loki, Tempo, Grafana, the documents nginx, the sigv4 proxy, `Constants.K8s.MIMIR_MEMCACHED_IMAGE`, and a new thanos pin. A drift test fails when a compose `image:` is not a registered cluster pin. The export README's image list is generated from `LocalImages`.
- memcached runs with `network_mode: "service:mimir"` and `depends_on: [mimir]`, so `mimir.yaml`'s `127.0.0.1:11211` addresses stay byte-identical. The memcached args in `MimirManifestBuilder.kt:80-92` (including `-m 2048`) become one shared value. S1 confirms compose recreates memcached with Mimir; if not, four `-...memcached.addresses=memcached:11211` overrides replace the shared namespace.
- Datasources: `GrafanaDatasourceSet.build(TenantSet, BackendUrls.LOCAL_COMPOSE)`. The UIDs and types are a cluster's; only URLs change. The tenant list comes from `TenantDirectory` at `create`. `*--all` is fixed when the file is generated. A re-run rewrites the datasource file and calls `POST /api/admin/provisioning/datasources/reload`. `create` prints the tenant list.
- `DashboardInstallContext(cluster: String?, experiment: ExperimentKey?, tenants, documentsUrl)`.
- The documents view is the same nginx as on a cluster, behind the read gate. Offline it uses `root /data`.
- Grafana's `GF_*` settings come from `GrafanaServerSettings`, shared with `GrafanaManifestBuilder`.

Read-only (D11): `store-gate` is an nginx that passes only GET and HEAD (query strings forwarded, so listings work) to `store-signer`, an `aws-sigv4-proxy` with the owner's credentials. Every backend's S3 endpoint is `http://store-gate:8080`, path-style, with dummy keys. PUT, POST and DELETE get 403, which also blocks multipart and `POST ?delete`. The gate is required, not optional, because Tempo's fallback poll tries to write its tenant index. Components: Mimir `-target=query-frontend,querier,store-gateway` (plus the query scheduler it needs), Loki read path from the store only, Tempo querier and query-frontend with no live-store. No compactor anywhere. An integration test snapshots the bucket listing (key and ETag), queries metrics, logs and traces, and asserts the listing is unchanged.

Credentials (D12): `~/.aws` is mounted read-only into the signer with `AWS_PROFILE` from `User.awsProfile`. `create` checks with the SDK (`HeadBucket` and a list of `mimir/`) and emits `LocalStack.StoreUnreadable(profile, awsErrorCode)` with `aws sso login --profile <p>` on failure. A one-shot `store-check` service runs before every backend (`depends_on: service_completed_successfully`) and exits non-zero with "cannot read the account bucket with profile P: <code>". The signer has a compose healthcheck (HEAD every 60 seconds), so `docker compose ps` shows it unhealthy once credentials expire. The home tenant is `local create --tenant`, default `default`.

Tempo freshness (D19): local and reader Tempo run `blocklist_poll_tenant_index_builders: 0`, `blocklist_poll_fallback: true`, and a short `blocklist_poll_stale_tenant_index`, so they list the bucket when the index is stale and never write one (the gate refuses the write). Offline, Tempo reads the index the export generated. The account compactor's Tempo `blocklist_poll` is 1 minute. `down` is untouched.

### 5. `observability local dashboards <dir> [--from <dir>]` (D13)

Read the manifest. Check Grafana `/api/health` on the recorded port; if it is down, fail and change nothing. Write the tree after the `GrafanaDashboardTreeWriter` pass into a staging directory inside the mounted parent, rename it into place, and call `POST /api/admin/provisioning/dashboards/reload`. No docker call, so no container is recreated. `--from` names any `<folder>/<file>.json` tree. Without it, the command uses the packaged catalog plus every kit's dashboards and says "using packaged dashboards".

### 6. Export: selector, atomic write, tenant copy (D15)

- A picocli exclusive `ArgGroup` maps to sealed `ExportSelector`: `Tenant(t)`, `Clusters(ids)`, `Experiment(ExperimentKey)`. `--tenant` alone is the tenant selector; with `--experiment` it names the experiment's tenant. No selector, or more than one kind, fails and names all three.
- A missing path fails. An existing path fails. The zip streams to `<path>.partial-<random>` and is moved into place without overwrite. A failure removes the partial and emits `Export.Failed(signal, step)`. Nothing is written to S3.
- The zip holds `compose.yml` in Offline mode, the Grafana provisioning and dashboards (after `DashboardDefaults` with the export context), `data/<key>`, the annotations, every report zip of each covered experiment with its unpacked directories and pointer, and `README.txt`. No `images/` and no `open.sh` (owner decision 15). `Export.Completed` carries `excluded = [profiles, issue 1016]`; the README says the same.
- Tenant copy order, against a running account compactor: (1) the Loki index, table by table; a table replaced mid-copy is re-listed and redone. (2) Tempo: list `tempo/<t>/` once, copy every block with `meta.json` and no `meta.compacted.json`, and write the zip's tenant index from the copied metas (`TempoTenantIndexWriter`). The account compactor's `compacted_block_retention` is raised to 48 hours in `compactor/tempo-backend.yaml`. (3) Mimir: read `bucket-index.json.gz` once, copy the blocks with no deletion mark (deletion comes 12 hours after the mark), and write the zip's index (`MimirBucketIndexWriter`). (4) Immutable objects last: Loki chunks, annotations, `reports/<t>/`.
- Size: sum the listing, print the estimate, and fail early when free disk space is short.
- An empty tenant fails and names the tenant and the prefixes it listed (m5).

### 7. Query exports (D16, D17, D18, addendum M6)

A query export starts a temporary read-only reader project (Live mode, random localhost ports, `docker compose -p`) and a writer project with filesystem backends. It needs the `ObservabilityHttp` endpoint seam (debt 3).

- `--experiment e`: the tenant is `--tenant`, then the workspace's, then `default`, and the command says which. Clusters come from `/api/v1/label/cluster/values?match[]=up{experiment="e"}&match[]=up{experiment="",cluster="e"}&start=0` in that tenant, so `--experiment <name>-<id>` covers a pre-label cluster. None fails and names `e`, `t` and the query.
- `--cluster x` uses `ClusterTenantResolver`: (1) inside the cluster's own workspace, the state's tenant, with no query; (2) if `TenantDirectory` lists one tenant, that tenant, and one non-federated query checks the cluster has data, failing and naming the cluster and the tenant when it has none; (3) with two or more tenants, `/api/v1/series?match[]=up{cluster="x"}&start=0&end=<now>` with `X-Scope-OrgID: a|b|...`, reading `edl_tenant`, or `__tenant_id__` for pre-label series. Zero fails naming the cluster; more than one fails naming them; neither label fails naming the tenant set. Mixed tenants across `--cluster` values are allowed; the home tenant is the first `--cluster`'s, and the zip's `TenantSet` is the union.
- Covered experiments come from `label_values(up{cluster=~"x|y"}, experiment)` with the pre-label branch. Their reports go in the zip. A pre-label cluster's experiment is its own id, and its existing `reports/<t>/<name>-<id>/` is copied as is.
- The work directory `<path>.work-<id>/` is kept after the zip. The command prints its location and size and never removes it.

| Signal | Method |
|---|---|
| Logs | `query_range` `{cluster=~"x\|y"}` paged forward with a tie-break, structured metadata kept, pushed to the writer Loki (`reject_old_samples: false`), flushed. Mirrored annotations come with them. |
| Traces | Tempo search `{ resource.cluster =~ "x\|y" }` over recursively split windows, fetch each trace, replay as OTLP to the writer Tempo, drain. |
| Metrics | Copy the tenant blocks that overlap the clusters' range, run the pinned `thanos tools bucket rewrite` with delete matchers `{cluster!~"x\|y"}`, drop the originals from staging, generate the bucket index. Thanos gets a filesystem objstore only, no AWS credentials, and `network_mode: none`. |

`Export.SignalExported(signal, items, bytes)` reports each signal; a signal with zero results is reported as zero.

Opening offline: the zip's `compose.yml` mounts `data/` read-only into every backend, with writable state in `cache/`. Retention is off and no compactor runs. Mimir and Tempo use the generated index files. The documents nginx uses `root /data`.

### 8. Grafana: experiments and comparison

#### 8.1 The experiment picker and the newest default (addendum M3)

Picker: `sort: 0`, no `includeAll`, regex `/exp_key="(?<value>[^"]+)", exp_text="(?<text>[^"]+)"/`:

```
query_result(sort_desc(max by (exp_key, exp_text) (
    label_join(label_join(
      max_over_time(timestamp(count by (edl_tenant, experiment) (up{experiment!=""}))[$lookback:${resolution}s]),
    "exp_key", "/", "edl_tenant", "experiment"), "exp_text", "/", "edl_tenant", "experiment")
  or
    label_join(label_join(label_replace(label_replace(
      max_over_time(timestamp(count by (cluster, __tenant_id__) (up{experiment=""}))[$lookback:${resolution}s]),
    "t", "$1", "__tenant_id__", "(.+)"), "t", "${home_tenant}", "t", ""),
    "exp_key", "/", "t", "cluster"), "exp_text", "/", "t", "cluster")
  or
    label_replace(label_replace(vector(0), "exp_key", ".*/.*", "", ""), "exp_text", "All", "", "")
)))
```

The value is the last-seen timestamp. `sort_desc` puts the newest first and All (0) last; `sort: 0` keeps that order. Tests uses the same query without the All row. Without `includeAll`, Grafana scenes opens an empty or invalid `current` on the first option. On a cluster, `DashboardDefaults` sets `current` to the cluster's key. In the live local stack `current` is empty, so every open shows the newest experiment. If S6 shows that the first option does not open, the fallback is the Tests row links with `var-experiment`.

#### 8.2 The pre-label branch at every site (addendum M4, owner decisions 12, 20)

Two forms. The list form is `<labelled> or <experiment="">`, with the same aggregation labels, so a cluster with both collapses. The lookup form is `<labelled> or on() <unlabelled or home literal>`.

Sites: (1) the `experiment` picker and its newest ordering on both Experiment dashboards, the cluster pair, both kept dashboards, and Tests; (2) `exp_tenant`/`exp_name` (derive only); (3) `cluster` on the Experiment dashboards:

```
query_result(max by (cluster) (max_over_time(timestamp(
  count by (cluster) (up{edl_tenant=~"$exp_tenant", experiment=~"$exp_name"})
  or count by (cluster) (up{experiment="", cluster=~"$exp_name"}))[$lookback:${resolution}s])))
```

(4) `cluster_a`/`cluster_b` on the pair and both kept dashboards, with the same inner selector; (5) the narrowed `cluster` on the kept dashboards; (6) `a_report`/`b_report`:

```
query_result(max by (exp_key) (label_join(last_over_time(up{cluster="$cluster_a", experiment!=""}[$lookback]), "exp_key", "/", "edl_tenant", "experiment"))
or on() max by (exp_key) (label_join(label_replace(label_replace(last_over_time(up{cluster="$cluster_a", experiment=""}[$lookback]),
  "t", "$1", "__tenant_id__", "(.+)"), "t", "${home_tenant}", "t", ""), "exp_key", "/", "t", "cluster")))
```

(7) the Tests experiments table and "Clusters of `$experiment`": unlabelled rows get `edl_tenant` from `__tenant_id__`, else the home tenant, and `experiment` = `cluster`; links use `${__data.fields.edl_tenant}/${__data.fields.experiment}`; (8) the Kotlin export's `--experiment` discovery and `--cluster` covered-experiment lookup. No LogQL or TraceQL reads `experiment` or `edl_tenant`.

`ExperimentFallbackGuards` and `ExperimentFallbackTest` check every core and kit dashboard, targets and variable queries: (a) a PromQL query with any `experiment` or `edl_tenant` matcher other than `experiment=""` also contains an `up{...experiment=""...}` selector joined by `or`; (b) a query that outputs `edl_tenant` contains `"${home_tenant}"`; (c) a dashboard that references `${home_tenant}` declares it as a hidden custom variable; (d) no LogQL or TraceQL names `experiment` or `edl_tenant`. Positive and negative fixtures follow `ClusterFilterTest.unscoped()`. The `DashboardDefaults` KDoc (`:39-44`) says the pass sets `current` and the options of the variables it owns; a custom variable's `query` is its option list.

`ExperimentPickerTest` checks that every cluster list carries `edl_tenant=~"$exp_tenant"`. S6 checks two tenants that hold a same-named experiment.

#### 8.3 Cluster pickers and span helpers (D5)

- `cluster` on the Experiment dashboards is multi-select with `includeAll` and no `allValue`, from the query in 8.2 site 3, with the short-name regex. "All" expands to the explicit option list, which is the experiment's clusters. Panels keep `cluster=~"$cluster"`; there is no exception to the cluster-filter rules. Twenty 77-character ids make about 1.6 KB per selector, sent by POST.
- Span helpers, about eight, independent of N: `exp_start` and `exp_end` = the min and max of `timestamp(count by (cluster) (up{cluster=~"$cluster"}))` over `[$lookback:${resolution}s]`; `exp_len` (padded by one step) and `exp_since_end`; each with `_s`/`_d` pairs, seconds first, as Grafana 13.2.2's `PanelTimeRange` needs (`PanelTimeOverrideTest`). Absolute-time panels get `timeFrom: ${exp_len_s}s-${exp_len_d}d` and `timeShift: ${exp_since_end_s}s-${exp_since_end_d}d`. The dashboard range stays relative.

#### 8.4 Panels for N clusters (D6, D7, D8)

Grafana 13.2.2 can set one `timeFrom`/`timeShift` per panel, a PromQL `offset` per query, instant queries with `@` and subqueries, table, bar chart, Trend and XY panels, the `merge`, `partitionByValues`, `groupBy` and `sortBy` transformations, per-column continuous colour, and repeats by a multi-value variable. It cannot shift time per series, give each repeated clone its own `timeShift`, or pick the i-th value of a multi-value variable. Exact full-resolution overlays need one query and helper chain per cluster, which works for a pair, not for N. Mimir allows 11,000 points per series, so the step is at least span/11,000 (about 165 seconds for 3 weeks).

Per-cluster windows come from the data in one `by (cluster)` query:

```
S = avg_over_time((F and on (cluster) A)[${exp_len}s:${resolution}s] @ ${exp_end})
F = per-cluster figure, e.g. sum by (cluster) (rate(cassandra_client_request_count_total{job="cassandra", cluster=~"$cluster"}[${resolution}s]))
A = active mask (D6), e.g. sum by (cluster) (rate(cassandra_client_request_count_total{job="cassandra", cluster=~"$cluster"}[${resolution}s])) > 0
```

Rows:

1. Leaderboard table, one row per cluster: Cluster (short name), Started (UTC), Active time (`count_over_time(A[...]) * resolution`), Throughput, Read p99, Write p99, Error rate, CPU busy, Disk I/O, GC pause %, Throughput CV. Instant `format: table` per figure, merged. Default sort by throughput, descending; every header sorts; per-column continuous colour, reversed for lower-is-better (D8: raw values only). Row links: System Overview and Cassandra Overview on the cluster's own window; the cluster pair with `var-cluster_a=${__data.fields.cluster}`, carrying `experiment`, `cluster`, the three datasource pickers, `lookback` and `resolution` (owner decision 19, m5); and the report.
2. Distributions: bar charts with clusters on the x axis, sorted by short name; `quantile_over_time(0.5|0.9|0.99, (F and on (cluster) A)[...])` for throughput, read p99, write p99 and CPU.
3. Elapsed-time overlay (D7): Trend panels, x = hours since each cluster's first request. A = `(timestamp(F and on (cluster) A) - on (cluster) group_left min_over_time(timestamp(A)[${exp_len}s:${resolution}s] @ ${exp_end})) / 3600`; B = `F`; range `format: table`; `merge` then `partitionByValues(cluster)`; `maxDataPoints` toward 11,000. S6 confirms.
4. Timeline: absolute time over the experiment's span; a status-history activity panel per cluster and throughput per cluster.
5. Report: one iframe, `__DOCUMENTS_URL__/reports/${experiment}/index.html`. With "All" the iframe shows the documents server's error page; the panel description says to select one experiment.
6. The existing deep rows (fleet, latency, JVM, compaction, system resources, stress) are already `by (cluster)`; each gets the span override.

Experiment: System uses the same layout with system figures; its active mask is first to last `up`. It keeps `role` and has no host pickers.

#### 8.5 Dashboard fates (D4 as overridden by owner decisions 7, 8, 9, 18)

- `cassandra/cluster-comparison.json` is renamed to `cassandra/experiment-cassandra.json`, "Experiment: Cassandra", keeping uid `c09599ec-...`. It drops `baseline_cluster`/`candidate_cluster`, the `base_*`/`cand_*` helpers, the overlay, side-by-side and summary rows, and "A/B verdict". It gains 8.1 to 8.4. The side-by-side latency heatmaps move to the cluster pair.
- New `infrastructure/experiment-system.json`, uid `experiment-system`, "Experiment: System".
- New `cassandra/cluster-pair.json`, uid `cluster-pair`, built from today's top three rows of `cluster-comparison` (overlay with a per-run `offset`, side by side, summary with difference % and documents) plus the verdict stats. Pickers `cluster_a` and `cluster_b`.
- `cassandra/ab-comparison.json` (uid kept): `baseline_cluster` to `cluster_a`, `candidate_cluster` to `cluster_b`; `base_*` to `a_*` and `cand_*` to `b_*` (`a_start`, `a_end`, `a_len`, `a_offset`, `a_since_end`, `a_len_d`/`_s`, `a_since_end_d`/`_s`; `max_len*` unchanged); `baseline` to `build_a`, `candidate` to `build_b`; `host` unchanged. Summary labels `"1 A"`, `"2 B"`, `"3 B vs A %"`; difference `100 * ((B) - (A)) / ((A) != 0)`, empty when A is 0; legends `A {{cluster_name}}` and `B {{cluster_name}}`. New variables `experiment`, `exp_tenant`, `exp_name`, `home_tenant`, `a_report`, `b_report`. `cluster_a` lists with `sort(min by (cluster) (min_over_time(...)))` and `sort: 0` (oldest first); `cluster_b` with `sort_desc(max by (cluster) (max_over_time(...)))` and `sort: 0` (newest first). Iframes `__DOCUMENTS_URL__/reports/${a_report}/index.html` and `${b_report}`. The `cluster` multi-select narrows to the experiment (owner decision 18): `query_result(count by (cluster) (count_over_time(up{edl_tenant=~"$exp_tenant", experiment=~"$exp_name"}[$__range]) or count_over_time(up{experiment="", cluster=~"$exp_name"}[$__range])))`, `includeAll: true`, default All.
- `infrastructure/system-ab-comparison.json` (uid kept): the same pair changes; `baseline` to `hosts_a`, `candidate` to `hosts_b`; `role` unchanged; the same narrowed `cluster`. Its link to `ab-comparison` carries `${experiment:queryparam}`, `${cluster_a:queryparam}` and `${cluster_b:queryparam}` and drops `doc_tenant`.
- `cassandra/profile-comparison.json` is unchanged (#1016).

#### 8.6 Tests dashboard

An experiments table: one row per (tenant, experiment) from `count by (edl_tenant, experiment, cluster)` over `$lookback`, with the pre-label branch; `groupBy` tenant and experiment gives the first start, the last end (`min`/`max_over_time(timestamp(...))`, regrouped), the number of clusters, and the cluster short names. Row links: "Newest report" `__DOCUMENTS_URL__/reports/${__data.fields.edl_tenant}/${__data.fields.experiment}/index.html` in a new tab; "Compare clusters" to Experiment: Cassandra with `var-experiment`; "Show here" to Tests with `var-experiment`. A "Clusters of `$experiment`" table lists today's per-cluster rows filtered by the experiment; its "Compare with the current cluster" link opens the cluster pair with `var-cluster_a` set. The documents panel shows the report of `$experiment`. A single-select `experiment` replaces Tests' single-select `cluster`. The Tests marker annotation filters on the selected experiment's clusters. The listing stays the one cluster-filter exception.

#### 8.7 DashboardDefaults, guards and tests

- `DashboardDefaults` context: `cluster: String?`, `experiment: ExperimentKey?`. `CLUSTER_VARIABLES` becomes `cluster` and `cluster_b`. On a cluster install, `cluster` gets the current cluster, except on dashboards that declare `experiment`, where it stays "All"; `cluster_b` gets the current cluster; `cluster_a` gets no `current` (opens on the oldest); `experiment` gets the cluster's `<tenant>/<name>`; `home_tenant` gets the cluster's tenant. In the live local stack, no `cluster`, `cluster_b` or `experiment` default, and `home_tenant` is `local create --tenant`. In an export, `experiment` is the export's experiment and `home_tenant` the export's tenant. The `doc_tenant` filling is removed.
- `ClusterFilterGuards` adds `Language.TRACEQL`: Tempo panel targets, Tempo variable queries, and decoded `panes=` on a Tempo datasource. Every spanset holds `resource.cluster` matched against `$cluster`, `${cluster:regex}`, or a pair variable. The matcher becomes `cluster_a|cluster_b`. `CLUSTER_LISTS` is `cluster`, `cluster_a`, `cluster_b`, `experiment`, `exp_tenant`, `exp_name`. The 61 current TraceQL uses are expected to fail and are fixed through `dashboard-editor`.
- `ClusterVariableTest` (`:52,71`): an exception for dashboards that declare `experiment`; `:90-130` Tests rule becomes `experiment`.
- `SeriesClusterGuards` (`:53,61`): `sideVariable` = `cluster_a|cluster_b`. `SeriesClusterTest` (`:85-86,204-213`).
- `ComparisonDashboardsTest` (`:22-27`): the list becomes `cluster-pair`, `ab-comparison`, `system-ab-comparison`; `:57-62` inverted (`cluster_a`/`cluster_b` present on all three); `:65-84` requires `experiment`, `home_tenant`, `a_report`, `b_report` and no `doc_tenant`; `:158-193` summary columns and labels. The `oldFilter` regex at `:30` becomes a per-dashboard map (`ab-comparison` to `build_a`, `build_b`, `host`; `system-ab-comparison` to `hosts_a`, `hosts_b`; `cluster-pair` to none), each listed variable asserted declared first; `runFilter` (`:31`) becomes `cluster="\$\{?(cluster_a|cluster_b)` (m6). Experiment rules: variables present, span helpers seconds first, span override on every absolute-time panel, leaderboard and distribution `by (cluster)` at `@ ${exp_end}`.
- `RolePickerTest` (`:24,30`): `hosts_a`/`hosts_b`; add `experiment-system.json` with `role` only; `:114` matcher `cluster|cluster_a|cluster_b`.
- `ClusterFilterTest` (`:34,61-62`) fixtures. `ClusterShortNameTest` (`:27,29,31,160,165`; iframe fixture `${a_report}`).
- `DashboardDatasourceGuards` (`:35-37`) `CARRIED` drops `baseline_cluster`, `candidate_cluster`, `doc_tenant` and adds `cluster_a`, `cluster_b`, `experiment`.
- `DashboardDefaultsTest` (`:66-92,124-136`): `doc_tenant` to `home_tenant`; `experiment` current `<tenant>/<name>`; a null context sets no current on `cluster`, `cluster_b` or `experiment`; `:72-73` pair defaults.
- `DocumentsIframeTest` (`:88-89`): 7 to 9 iframes (Tests 1, Experiment: Cassandra 1, Experiment: System 1, pair 2, `ab-comparison` 2, `system-ab-comparison` 2); every iframe path is `reports/${experiment}/` or `reports/${a_report|b_report}/`.
- `DashboardLinkTimeTest` (`:25-27`): add `experiment-system` and `cluster-pair`. `CoreDashboardAnnotationsTest` follows the Tests annotation change.
- The picker names live in `configuration/grafana/DashboardVariables.kt` (debt 7).
- Dashboard JSON changes go through the `dashboard-editor` agent (byte-preserving scripts, `installDist`, read-back from Grafana).

## Spikes

- S1: the read-only component sets behind the gate, including their behaviour on a 403 (Mimir, Loki, Tempo); compose recreates memcached with Mimir.
- S2: `aws-sigv4-proxy` resolves an SSO profile from a read-only `~/.aws`.
- S3: Tempo 3 accepts old spans in the export writer.
- S4: `thanos tools bucket rewrite` on Mimir 3.2.1 blocks, and Mimir reading a generated bucket index.
- S5: Tempo with `blocklist_poll_tenant_index_builders: 0`, `blocklist_poll_fallback: true` and a short `blocklist_poll_stale_tenant_index` lists when stale and never writes (look for the refused PUT); offline with a generated index.
- S6: on Grafana 13.2.2: (1) no `current` and no `includeAll` opens `options[0]`; (2) `query_result` keeps the `sort_desc` order with `sort: 0`; (3) native `includeAll` with no `current` opens All; (4) `var-experiment=.*/.*` selects the synthetic All; (5) a valid installed `current` is kept; (6) the picker query time over 180 days, on one tenant and on `mimir--all`, with the pre-label branch; `merge` then `partitionByValues` then Trend; `groupBy` with an all-values reducer; "All" with 20 long ids; the leaderboard query time for a 3-week, 20-cluster experiment; a cold open on "All" over 180 days; the Trend overlay at 3 weeks; the local stack with memcached; two tenants holding a same-named experiment.
- S7: Mimir 3.2.1 accepts `start=0` on `/api/v1/series` and label values; query time; `__tenant_id__` is present with two or more tenants and absent with one.

## Phases

0. Archive issue-966, issue-970 and issue-971 before this change archives.
1. Labels: `experiment` and `edl_tenant`, the coverage test, `_` in cluster names, the OpenSearch domain name, the Loki ingester id test, the JFR shipper and AMI rebake, `help provisioning`, the init docs.
2. Report zip and pointer, `ObjectStore` conditional writes, the Tests rows, the `help observability` report section.
3. Comparison redesign and the TraceQL audit (S6 first).
4. Local stack, `local dashboards`, the docs page, the `help observability` local-stack section (S1, S2, S5).
5. Tenant export and offline open, the export docs and help section.
6. Query exports (S3, S4, S7).

Hard dependencies on unmerged work: none. #1016 depends on this change. PR 1010 touches `User.awsProfile`. PRs that add a `cluster` setter trip the coverage test. #917 is independent.

## Risks / Trade-offs

- Query load: instant subqueries over the span; a 3-week, 20-cluster experiment at 5 minutes is about 6,000 steps over every host series. The `resolution` picker, Mimir's 1-hour results cache and the local memcached limit it; S6 measures.
- Overlay resolution drops on long experiments (about 165 seconds per point at 3 weeks). The pair keeps exact overlays.
- JSON churn: three 5,000 to 8,600 line files reworked and two added. Tests that pin pair names change.
- A tenant export of `default` may be very large; the estimate fails early on disk space.
- A trace export searches and then fetches each trace, which is slow at scale.
- The Cassandra AMI must be rebuilt for the JFR label.
- The opening machine must already have the stack's images, about 1.2 GB per platform.
- The forward-only pointer compares uploader clocks; two machines with clock skew uploading within seconds could leave the pointer on the older upload. Both zips are kept.

## Nearby structural debt

1. The `observability-store` spec contradicts the code. Fold in: archive #966 and #970 first, then this change's delta fixes the Mimir local retention text and adds the documents layout.
2. Root `CLAUDE.md` says the ConfigMap holds `TENANT`; the key is `tenant`. Fold in.
3. `ObservabilityHttp` is tied to the cluster. Fold in: an endpoint-and-tenant seam with a localhost implementation.
4. Grafana's `GF_*` settings live only in `GrafanaManifestBuilder`. Fold in: extract `GrafanaServerSettings`.
5. The installer-filled `doc_tenant` is retired. Fold in.
6. The `denyDataDeletes` KDoc (`AWSPolicy.kt:217-222`) says `report upload` replaces a document. Fold in.
7. The comparison picker names are hard-coded in six places. Fold in: one shared constant.
8. `events/Event.kt` is about 6,200 lines. Left for later; no issue filed.
9. `CLUSTER_NAME` has two meanings. Left for later; no issue filed.
10. Pyroscope DLQ, adaptive placement, and usage stats: moved to #1016.

## Profiles

Moved to #1016. This change only puts `experiment` and `edl_tenant` on profiles (Alloy, the JFR shipper, the stress sidecar, the Cassandra sidecar, EMR, presto and trino). `down` keeps "profiles need no flush".

## Archive ordering

issue-966, issue-970 and issue-971 must be archived before issue-902. This change's MODIFIED requirements are written against their text: `observability-store` against #966, #970 and #971; `account-compactor` against #970; `cluster-comparison-dashboard`, `multi-cluster-dashboards`, `test-documents`, `tests-dashboard`, `observability` and `grafana-install-dashboard` against #971. Archiving this change first would let an older text overwrite these changes.

## Alternatives Considered

**Finding an experiment across tenants: registry, label, or reports scan.**
- Round 1 and 2 recommended an S3 registry: `experiments/<e>/experiment.json` claimed with `If-None-Match: *`, member records, and `experiments/_clusters/<cluster>.json` for the `--cluster` tenant lookup, with `init` reading the bucket before any write. Rejected by owner decision 5: an experiment is (tenant, name), so there is nothing to claim across tenants. The architect's round-3 design dropped it.
- Listing `experiment` label values across tenants at `init`: rejected in round 1 (needs a running Mimir; an inited cluster has no series). It survives as the discovery method for `export --experiment`, which runs over data that exists.
- Scanning `reports/*/<experiment>/`: rejected (exists only after an upload).
- When the claim is written (round-1 D2, round-2 D3: at `init`, or at `up`): moot without a registry.

**Profiles.**
- P-A (round 1, recommended): `down` saves a block list and a read-only reader Pyroscope loads it. Rejected after critic round 1 (B1 to B3): profiles reach other readers only after `down`, there is no per-tenant datasource, and `--force` teardowns are never indexed.
- P-B: rebuild an index by scanning `block.bin` footers. Rejected: double counts merged inputs for 15 minutes after each merge, and query failures under merge churn.
- P-C: one account-level metastore on Fargate. Rejected: Raft needs EFS, every cluster VPC uses the same CIDR so peering is impossible, and offline still needs a loader.
- P-D: upgrade Pyroscope. Rejected: v2.4.0 adds no rebuild.
- Generation readers fed by views published every minute (round 2 D5 to D8, with DLQ off on writers and a final view at `down`). Critic round 2 found double counts from DLQ off, a publisher race with the final view, silent gaps, a forbidden verify check in `down`, and rebuild load that grows with the account's history. **Owner decision 4 split cross-cluster profiles out to #1016.** This change keeps only the label.

**Arm label vs cluster as the variant.** Earlier rounds treated an experiment as two arms (baseline and candidate) and considered an arm label. **Owner decision 6:** no arm, no `--arm`, no variant label; each cluster is a variant and its name says what it tests.

**D4, the fate of the comparison dashboards.**
- Round 3 (recommended): Experiment: Cassandra replaces `cluster-comparison`, Experiment: System replaces `system-ab-comparison`, a new cluster pair, and `ab-comparison` removed with its unique sections moved into Experiment: Cassandra. **Owner decision 9 overrode the removal:** `ab-comparison` and `system-ab-comparison` stay, with A/B pickers; Experiment: System is a new file. "Nothing that compares is removed."
- Alt (b): keep all three with N-cluster rows added and pair rows limited to the experiment. Not chosen as such; the kept dashboards take the pair rename and the experiment narrowing.
- Alt (c): one dashboard for everything. Rejected.

**D9, pair picker names.** Round 3 recommended keeping `baseline_cluster`/`candidate_cluster` for a directional difference %, pinned by a test from an earlier owner choice. Alt: neutral `cluster_a`/`cluster_b`. **Owner decision 8 overrode the architect:** `cluster_a`/`cluster_b`, "B vs A". The kept dashboards' build and host pickers follow (`build_a`/`build_b`, `hosts_a`/`hosts_b`).

**D2, how a dashboard learns an experiment's tenant.**
- Recommended (chosen by owner decision 11): a tenant label at every `cluster` setter, named `edl_tenant` to avoid Tempo's own `tenant` label.
- Alt (b): keep the installer-filled `doc_tenant`. Rejected: links break outside the home tenant view.
- Alt (c): one info series per cluster, `easydblab_cluster_info{cluster, experiment, tenant}`. Rejected: needs an emitter and a join on every lookup.
- Round 2's `reports/_experiments/<e>/index.html` redirect and per-arm `baseline_experiment` lookups: superseded by the report path inside `reports/<tenant>/` and the `<tenant>/<name>` picker value.

**D14, images in the export.** Round 1 recommended `docker save` of every pinned image plus `open.sh`; round 2 both CPU platforms (about 2.4 to 3 GB) with `pull_policy: never`; alt host platform only. **Owner decision 15 overrode the architect:** no images and no `open.sh`; the opening machine already has the images; plain `docker compose up` with the default pull policy.

**Cluster names with `_`.** Alt: keep `^[a-z][a-z0-9-]{0,39}$` and document that variant names use `-`. **Owner decision 14 chose to allow `_`**, with the OpenSearch domain fix and the Loki ingester id test.

**Pair defaults.** Recommended (chosen by owner decision 19): A is the experiment's oldest cluster; B is the current cluster on a cluster and the newest locally; a leaderboard link sets only A. Alt: both default to the current cluster, as #971 does, which shows 0% until one is changed. Rejected.

**Narrowing `cluster` on the kept dashboards.** Recommended (chosen by owner decision 18): narrow the `cluster` multi-select to the experiment, over the dashboard range, default All. Alt: leave `label_values(up, cluster)` and narrow only the pair pickers.

**Pre-label clusters.** (a) Recommended (chosen by owner decision 12, refined by decision 20): each pre-label cluster is an experiment of one through an `or` branch on series with no `experiment` label; its tenant comes from `__tenant_id__`, else the home tenant. (b) Round 3's `label_replace(..., "experiment", "$1", "cluster", "(.+)")` over every series: rejected by critic round 3 M6, it made every cluster its own experiment and gave empty tenant paths. (c) Hide pre-label clusters from experiment pickers (round 3 as written, `experiment!=""`): rejected; they must appear in pickers and Tests.

**Default experiment on open.** (a) Recommended (chosen by owner decision 13): the newest experiment, through a picker sorted by last activity, with a synthetic "All" that must be picked. (b) "All" on open: rejected, a cold open over every cluster of a tenant over 180 days is the most expensive query (critic round 3 M7). (c) Unset, letting Grafana pick the first option by name: rejected, the order would be alphabetical, not by recency.

**Same-named experiments in the all-tenants view.** Round 3: a name in two tenants appears once and selects both. Rejected by owner decision 10 and critic round 3 M5. Chosen: the `<tenant>/<name>` picker value and `edl_tenant=~"$exp_tenant"` on every cluster list.

**`--cluster` tenant lookup.** Round 2: the registry's `_clusters` record. Round 3: federated label values of `__tenant_id__`, which critic round 3 M3 showed likely returns every tenant. Chosen (owner decision 16, D17 as refined by addendum M6): a series query, with the one-tenant and in-workspace short cuts. Alt: the tenant from annotation backup keys. Rejected.

**Accepted recommendations (owner decision 16).**
- D1, ConfigMap key: `experiment` with env `EXPERIMENT`. Alt: an uppercase key that breaks the lowercase convention. Rejected.
- D3, report layout and pointer: one directory per upload and a forward-only pointer, UTC, `If-Match`. Alt: one `current/` directory overwritten on each upload; rejected, a file dropped from a newer report would linger unless deleted. Alt: compare S3 `LastModified`; alt: last writer wins (critic round 1 m1). Rejected.
- D6, summary window: each cluster's active window (client requests for Cassandra, first to last `up` for System). Alt: the whole lifetime. Rejected.
- D7, lining up N clusters: an elapsed-time Trend overlay, an absolute timeline, and the cluster pair for exact pair overlays. Alt: fixed overlay slots with their own pickers and offsets; alt: absolute time only. Rejected.
- D8, relative figures in the N view: raw values, sortable, with per-column colour; difference % only in the pair. Alt: % from the selection median or from a picked reference cluster, which needs a match that ignores `cluster` and breaks `SeriesClusterTest`/`ClusterJoinTest`. Rejected.
- D10, an owned file the user edited: overwrite and report. Alt: refuse. Rejected.
- D11, read-only: a GET/HEAD gate (required, since Tempo tries to write its index), read-only components, no compactor, and a listing-unchanged test. Alt: read-only components only; ruled out by critic round 3 M4. Alt: an IAM session policy; stronger but more IAM setup. Rejected.
- D12, credentials and tenants: `~/.aws` read-only into the signer with the profile, `store-check`, a signer healthcheck; home tenant `local create --tenant`, default `default`; a re-run picks up new tenants and reloads datasources. Alt: short-lived session credentials written at `create`. Kept only as the S2 fallback.
- D13, dashboards refresh: the provisioning reload API. Alt: upload through `/api/dashboards/db`. Rejected.
- D15, tenant copy: the safe order and regenerated Mimir and Tempo indexes. Alt: copy the live indexes as they are; rejected, a copied live index would not match the copied blocks and Mimir's consistency check fails.
- D16, metrics query export: `thanos tools bucket rewrite`, filesystem objstore only, no AWS credentials, no network. Alt: remote read and `promtool create-blocks-from openmetrics`, which loses native histograms; alt: `query_range` resampling, which is lossy. Rejected.
- D17, temporary stacks: reader and writer compose projects; mixed tenants allowed in `--cluster`; the work directory kept and its size printed, never removed; the `--cluster` tenant through a series query. Alt: refuse mixed tenants; alt: remove the work directory once the zip is verified; alt: require an already running local stack. Rejected.
- D18, query exports stay in this issue, built last. Alt: split them into their own issue, which drops the `--cluster` and `--experiment` criteria. Rejected.
- D19, Tempo freshness: local Tempo lists the bucket when the index is stale, exports copy Tempo blocks by listing, and the account compactor's Tempo poll is 1 minute. Alt: `down` forces an index build; rejected, `down` only flushes. Alt: rely on the index alone; rejected, it can miss the last cluster's final blocks (critic round 2 M5).
- m4, report front page: a root `index.md` or `index.html` is allowed as the front page, and the generated list is `_files.html`. Alt: reserve `index.md`, as #971 does. Rejected.
- m5, an empty `--tenant` export: fails and names the tenant and the prefixes. Alt: write an empty zip. Rejected.
- Debt fold-ins (observability-store spec, `TENANT` in CLAUDE.md, the `ObservabilityHttp` seam, `GrafanaServerSettings`, the `denyDataDeletes` KDoc, the shared picker-name constant): folded in. Alt: separate issues. Rejected. `Event.kt` split and the `CLUSTER_NAME` meanings: left for later, no issue filed.
