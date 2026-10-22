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
- Tempo writes its tenant index after a full poll (critic round 3 M4). Tempo 3.0.3's single binary, the only layout that answers Grafana, is always an index builder and PUTs `tempo/<t>/index.pb.zst` on every blocklist poll, whatever its settings (spikes S1, S1-tempo, S5). Only the read gate stops it. `index.json.gz` is never tried, because Tempo stops when the first PUT fails.
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
- Any compaction, merging or deletion in the local stack. (Tempo's single binary starts its backend scheduler and worker; they run inert, see section 4.)
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
21. Cassandra latency is the OpenTelemetry Java agent's per-node percentile gauges, with no histogram buckets. The cluster pair shows A's and B's Percentile Ladders side by side and has no latency heatmap. The per-cluster aggregate is the slowest node's p99 gauge (`max by (cluster)`). "Read-path components only" is corrected: Mimir also runs an idle ingester, which receives no writes (spike S1).
22. A `--tenant` export filters Loki's index with Loki's own code: Loki's compactor runs in a local docker container over a local copy, so the zip holds only the tenant's per-tenant index files. This is the one docker step of a `--tenant` export.

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
- Mimir and Loki configs are the cluster's own resource files with flag overrides, as `CompactorTaskDefinition.kt` does. Tempo gets a generated config file: the cluster's `tempo.yaml` parsed and patched as typed data, never string-built, because 3.0.3 has no flag for `s3.insecure`, `s3.forcepathstyle` or the `blocklist_poll_*` keys other than `blocklist_poll` (changed by spike S1). Everything comes from the classpath.
- The manifest `<dir>/.easy-db-lab-local-stack.json` records the tool version, the mode, the home tenant, the tenant list, the sha256 of each owned file, and the Grafana port.
- A missing or empty directory is created. A non-empty directory without the manifest is refused before any write. A re-run rewrites only owned files, atomically (`AtomicFileWrite`). An owned file the user edited is overwritten and reported (D10). `data/`, `cache/`, `grafana-data/` and `compose.override.yml` are never opened.
- `LocalImages` takes every pin from the builder constants: Mimir, Loki, Tempo, Grafana, the documents nginx, the sigv4 proxy, `Constants.K8s.MIMIR_MEMCACHED_IMAGE`, and a new thanos pin. A drift test fails when a compose `image:` is not a registered cluster pin. The export README's image list is generated from `LocalImages`.
- memcached is an ordinary service on the project network. Mimir gets four address overrides, `-blocks-storage.bucket-store.{index,chunks,metadata}-cache.memcached.addresses=memcached:11211` and `-query-frontend.results-cache.memcached.addresses=memcached:11211`; `mimir.yaml` stays byte-identical (changed by spike S1: a shared `network_mode: "service:mimir"` namespace breaks silently when Mimir restarts in place, leaving every cache failing with warnings only). The memcached args in `MimirManifestBuilder.kt:80-92` cannot be shared unchanged, because the cluster's `-l 127.0.0.1` would refuse the other containers: the size, connection and thread values (`-m 2048 -I 1m -c 4096 -t 4`) are one shared value, and each caller adds its own listen address.
- Datasources: `GrafanaDatasourceSet.build(TenantSet, BackendUrls.LOCAL_COMPOSE)`. The UIDs and types are a cluster's; only URLs change. The tenant list comes from `TenantDirectory` at `create`. `*--all` is fixed when the file is generated. A re-run rewrites the datasource file and calls `POST /api/admin/provisioning/datasources/reload`. `create` prints the tenant list.
- `DashboardInstallContext(cluster: String?, experiment: ExperimentKey?, tenants, documentsUrl)`.
- The documents view is the same nginx as on a cluster, behind the read gate. Offline it uses `root /data`.
- Grafana's `GF_*` settings come from `GrafanaServerSettings`, shared with `GrafanaManifestBuilder`.

Read-only (D11): `store-gate` is an nginx that passes only GET and HEAD (query strings forwarded, so listings work) to `store-signer`, an `aws-sigv4-proxy` with the owner's credentials. Every backend's S3 endpoint is `http://store-gate:8080`, path-style, with dummy keys. PUT, POST and DELETE get 403, which also blocks multipart and `POST ?delete`. The gate is required, not optional, because Tempo's single binary PUTs its tenant index on every poll. No component writes, compacts, merges or deletes. Components (changed by spikes S1 and S1-tempo):

- Mimir: `-target=query-frontend,querier,store-gateway,query-scheduler,ingester`. The idle ingester is required: `mimir.yaml` sets `query_ingesters_within: 0` and Grafana queries end at `now`, so without it every query fails with `empty ring`, and no `-querier.query-ingesters-within` value avoids that. No distributor runs, so the ingester never receives a sample and ships nothing. Plus `-usage-stats.enabled=false`. S1 saw no non-GET/HEAD request from Mimir in about 50 minutes.
- Loki: `-target=read,query-scheduler -querier.query-store-only=true -common.compactor-address=http://127.0.0.1:3100 -reporting.enabled=false`. `read` alone has no query scheduler in 3.7.8 and is never ready; without a compactor address it exits at start (`cache-generation-loader`). The address points at the process itself, and no compactor module runs (`deletion_mode: disabled` is already in `loki.yaml`). S1 saw no non-GET/HEAD request from Loki.
- Tempo: the single binary (no `-target`). The split `query-frontend` plus `querier` cannot answer Grafana (the live-store ring is empty, and a standalone `live-store` wants a Kafka partition id); 3.0.3 accepts no target list. The single binary always starts the backend scheduler and worker, and `main.go` forces the worker unsharded, so no config turns them off. They run inert with the generated config: `overrides.defaults.compaction.compaction_disabled: true` (already in `tempo.yaml`; no compaction or retention job is ever scheduled), `backend_scheduler.backend_flush_interval: 87600h` (no periodic `tempo/work.json`), `backend_scheduler.local_work_path: /dev/null/backend-scheduler` (no `work.json` on shutdown), `usage_report.reporting_enabled: false`, `query_frontend.search.query_backend_after: 1m` and `query_frontend.metrics.query_backend_after: 1m`, `storage.trace.blocklist_poll_fallback: false`, and Tempo's default 5-minute `blocklist_poll`, kept on purpose because every poll lists every block of every tenant (S1-tempo saw 16,484 GETs in 11 minutes at 30s). `blocklist_poll` must stay a real interval: freezing it leaves only the startup PUTs but hides every block written after start. The S3 endpoint, `insecure` and `forcepathstyle` are set in the same file. The one remaining write is `PUT tempo/<tenant>/index.pb.zst` once per tenant per poll, refused by the gate, logged by Tempo as a non-fatal error.

An integration test snapshots the bucket listing (key and ETag), queries metrics, logs and traces of every tenant, single-tenant and federated, and asserts the listing is unchanged. It also asserts that every request the gate refused is a `PUT tempo/<tenant>/index.pb.zst` (no `work.json`, no DELETE, no POST).

Credentials (D12): `~/.aws` is mounted read-only into the signer at `/root/.aws` with `AWS_PROFILE` from `User.awsProfile` and `HOME=/root`. The image sets no `HOME`, and without it aws-sdk-go v1 never reads the mount and every request fails with `NoCredentialProviders` (spike S2); record it as a constant with a comment next to the mount. The same applies to `store-check` if it uses this image. `create` checks with the SDK (`HeadBucket` and a list of `mimir/`) and emits `LocalStack.StoreUnreadable(profile, awsErrorCode)` with `aws sso login --profile <p>` on failure. A one-shot `store-check` service runs before every backend (`depends_on: service_completed_successfully`) and exits non-zero with "cannot read the account bucket with profile P: <code>". It treats the signer's HTTP 502 with a body starting `unable to proxy request` as a credentials failure, separate from an S3 4xx such as `AccessDenied`. The signer has a compose healthcheck (HEAD every 60 seconds), so `docker compose ps` shows it unhealthy once credentials expire. These two are the only things that make expiry visible (spike S1 (d)): a running Mimir and Loki keep answering from their caches with 200 and no error while Tempo returns 500; on a cold start Mimir and Tempo exit 1, and Loki starts ready and answers an empty result. Once the SSO access token expires, the signer cannot refresh it on a read-only mount; `aws sso login --profile <p>` on the host fixes it and a running signer recovers with no restart. The mount stays read-only: the container runs as root and would rewrite the owner's SSO cache. The session-credentials fallback is dropped (changed by spike S2). The home tenant is `local create --tenant`, default `default`.

Tempo freshness (D19, changed by spikes S5 and S1-tempo): the local and reader Tempo is the single binary, which is always an index builder: it ignores `blocklist_poll_tenant_index_builders: 0`, never reads the tenant index, and lists every tenant's blocks on every poll. Freshness therefore comes from the listing, with no stale-index setting; `blocklist_poll_tenant_index_builders` is dropped from the reader config and `blocklist_poll_fallback` is `false`. Search sees the newest blocks only with `query_backend_after: 1m` (Tempo rejects `0s`, which is below its 30s query-end cutoff); at the default 15m, search over the last 15 minutes goes only to the reader's empty live-store. Offline, the single binary lists the read-only directory itself; the export still generates `index.json.gz` (`{created_at, meta: [...], compacted: []}`, no `index.pb.zst` needed) for any split reader. The account compactor's Tempo `blocklist_poll` is 1 minute. `down` is untouched.

### 5. `observability local dashboards <dir> [--from <dir>]` (D13)

Read the manifest. Check Grafana `/api/health` on the recorded port; if it is down, fail and change nothing. Write each dashboard of the tree, after the `GrafanaDashboardTreeWriter` pass, in place in the provisioned tree, each file by an atomic rename (no staging directory, and the tree directory itself is never renamed), remove the files of the previous tree that are not in the new one, and call `POST /api/admin/provisioning/dashboards/reload` once. No docker call, so no container is recreated. `--from` names any `<folder>/<file>.json` tree. Without it, the command uses the packaged catalog plus every kit's dashboards and says "using packaged dashboards".

### 6. Export: selector, atomic write, tenant copy (D15)

- A picocli exclusive `ArgGroup` maps to sealed `ExportSelector`: `Tenant(t)`, `Clusters(ids)`, `Experiment(ExperimentKey)`. `--tenant` alone is the tenant selector; with `--experiment` it names the experiment's tenant. No selector, or more than one kind, fails and names all three.
- A missing path fails. An existing path fails. The zip streams to `<path>.partial-<random>` and is moved into place without overwrite. A failure removes the partial and emits `Export.Failed(signal, step)`. Nothing is written to S3.
- The zip holds `compose.yml` in Offline mode, the Grafana provisioning and dashboards (after `DashboardDefaults` with the export context), `data/<key>`, the annotations, every report zip of each covered experiment with its unpacked directories and pointer, and `README.txt`. No `images/` and no `open.sh` (owner decision 15). `Export.Completed` carries `excluded = [profiles, issue 1016]`; the README says the same.
- Tenant copy order, against a running account compactor: (1) the Loki index, table by table; a table replaced mid-copy is re-listed and redone. (2) Tempo: list `tempo/<t>/` once, copy every block with `meta.json` and no `meta.compacted.json`, and write the zip's tenant index from the copied metas (`TempoTenantIndexWriter`). The account compactor's `compacted_block_retention` is raised to 48 hours in `compactor/tempo-backend.yaml`. (3) Mimir: read `bucket-index.json.gz` once, copy the blocks with no deletion mark (deletion comes 12 hours after the mark), and write the zip's index (`MimirBucketIndexWriter`) last. (4) Immutable objects last: Loki chunks, annotations, `reports/<t>/`.
- Loki index split (owner decision 22). Loki's ingesters upload one shared multi-tenant TSDB file per table and upload, at the table root, named `<epoch>-<ingester id>-<nanos>.tsdb.gz`; the ingester id is `<tenant>.<cluster>` (`loki.yaml`), and the file holds every tenant that ingester received. Copying the root files leaks other tenants' stream labels, chunk references and names. Only Loki's compactor splits them. The export copies each table's root files and `<table>/<t>/` into `<path>.work-<id>/loki/store/loki/index/` (the same table-by-table redo), then, when any root file was copied, runs one container of `LocalImages.LOKI` with `-target=compactor` over that store (the cluster's `loki.yaml` with the offline stack's filesystem storage, read-write), `network_mode: none`, no environment beyond what the file expands, as the operator, retention off, deletion `disabled`, and memberlist bound to `127.0.0.1` (with no network Loki finds no interface address and exits). It waits for the compactor's `compactor started` log line, which Loki writes after its first full pass over every table, then removes the project, after saving its logs in the work directory. It fails when a table root still holds a file, and puts only `<table>/<t>/` files into the zip. Verified on Loki 3.7.8: the compactor waits one compaction interval before the first pass (so the export sets a short one, `Constants.Export.LOKI_SPLIT_INTERVAL_SECONDS`), writes `<table>/<tenant>/<nanos>-compactor-<from>-<through>-<checksum>.tsdb.gz` per tenant, merges a tenant's existing per-tenant files into it, removes the root file, and a later pass leaves split tables unchanged; the offline stack's read-path Loki answers the tenant's newest lines from the split files alone. The work directory is kept and reported (`Export.WorkDirectoryKept`), as for query exports. Alt: parse and rewrite TSDB files in Kotlin; rejected, it reimplements Loki's index format. Alt: copy the root files as they are; rejected, it leaks other tenants.
- `MimirBucketIndexWriter` is Kotlin (changed by spike S4: no Mimir tool writes an index offline, and running a Mimir compactor over the staging directory would merge and mark the blocks). It writes version 2, the same logic as Mimir's `bucketindex.BlockFromThanosMeta`: one entry per block on disk with `block_id`, `min_time`, `max_time`, `segments_format: "1b6d"` and `segments_num` (only when the chunk files are consecutive `000001..00000N`, from `thanos.segment_files` or the `chunks/` entries of `thanos.files`), `uploaded_at`, `compactor_shard_id` (only when `thanos.labels` has `__compactor_shard_id__`), and, omitted when empty, `source`, `compaction_level`, `out_of_order` and `labels`; then `block_deletion_marks: []` and `updated_at`, gzipped to `<prefix>/<tenant>/bucket-index.json.gz`. S4 showed it equal to Mimir's own index field for field, apart from the timestamps. It lists the directory it indexes, never the copy list: an index that omits a block on disk loses that block's data silently, and one that lists a block not on disk fails every query with `err-mimir-store-consistency-check-failed`. A reader needs no `markers/` and no deletion marks.
- Size: sum the listing, print the estimate, and fail early when free disk space is short.
- An empty tenant fails and names the tenant and the prefixes it listed (m5).

### 7. Query exports (D16, D17, D18, addendum M6)

A query export starts a temporary read-only reader project (Live mode, random localhost ports, `docker compose -p`) and a writer project with filesystem backends. It needs the `ObservabilityHttp` endpoint seam (debt 3).

- `--experiment e`: the tenant is `--tenant`, then the workspace's, then `default`, and the command says which. Clusters come from `/api/v1/label/cluster/values?match[]=up{experiment="e"}&match[]=up{experiment="",cluster="e"}&start=0` in that tenant, so `--experiment <name>-<id>` covers a pre-label cluster. None fails and names `e`, `t` and the query.
- `--cluster x` uses `ClusterTenantResolver`: (1) inside the cluster's own workspace, the state's tenant, with no query; (2) if `TenantDirectory` lists one tenant, that tenant, and one non-federated query checks the cluster has data, failing and naming the cluster and the tenant when it has none; (3) with two or more tenants, `/api/v1/series?match[]=up{cluster="x"}&start=0&end=<now>` with every tenant from `TenantDirectory` joined by `|` in `X-Scope-OrgID`, taking each series' tenant from `__tenant_id__`, which a federated response sets on every series, labelled or pre-label (spike S7). With one tenant `__tenant_id__` is absent, so step 2 uses the listed tenant and never looks for the label. Never use `/api/v1/label/__tenant_id__/values`: the federation layer answers it from the header's tenant list and ignores `match[]` (S7). Zero series (HTTP 200, `data: []`) fails naming the cluster; more than one tenant fails naming them; a series with no `__tenant_id__` on a federated response is kept as a defensive failure naming the tenant set. A listed tenant with no data or no bucket index does not fail the request. No Mimir query-length or lookback limit applies: `mimir.yaml` sets none and the 3.2.1 defaults are all off, so `start=0` reaches the oldest blocks; they must stay unset. Mixed tenants across `--cluster` values are allowed; the home tenant is the first `--cluster`'s, and the zip's `TenantSet` is the union.
- Covered experiments come from `label_values(up{cluster=~"x|y"}, experiment)` with the pre-label branch. Their reports go in the zip. A pre-label cluster's experiment is its own id, and its existing `reports/<t>/<name>-<id>/` is copied as is.
- The work directory `<path>.work-<id>/` is kept after the zip. The command prints its location and size and never removes it.

| Signal | Method |
|---|---|
| Logs | `query_range` `{cluster=~"x\|y"}` paged forward with a tie-break, structured metadata kept, pushed to the writer Loki (`reject_old_samples: false`), flushed. Mirrored annotations come with them. |
| Traces | Tempo search `{ resource.cluster =~ "x\|y" }` over recursively split windows of at most 168h (Tempo's `query_frontend.search.max_duration` rejects a longer window, spike S3), fetch each trace, replay as OTLP to the writer Tempo, drain. The writer Tempo sets `live_store.wal.ingestion_time_range_slack: 87600h` and `storage.trace.wal.ingestion_time_range_slack: 87600h`; at the default 2m an old span's block gets the push time as its range, and readers never find it over its real time. Never 0: the clamp has no "off" case, and 0 would clamp every span. Drain waits until every completed live-store block under `live-store/wal/blocks/<tenant>/<id>/` has its `flushed` marker, as `TempoTailFlush` does, and only then stops the writer; a graceful stop alone cancels the upload and leaves a partial backend block. |
| Metrics | Copy the tenant blocks that overlap the clusters' range, run the pinned `quay.io/thanos/thanos:v0.42.4` `tools bucket rewrite --prom-blocks --no-dry-run` with delete matchers `{cluster!~"x\|y"}` and one `--id` per block, drop the originals from staging, drop rewritten blocks whose `stats.numSeries` is missing or 0, then generate the bucket index last (spike S4). `--prom-blocks` is required (Mimir blocks have empty external labels); `--delete-blocks` and `--hash-func` are never passed. The objstore is `FILESYSTEM` rooted at `<staging>/mimir/<tenant>`, with `--tmp.dir` in the work directory outside the tenant directory. Thanos runs with `network_mode: none`, no environment, no AWS credentials, and `user:` set to the operator's uid:gid (the image's uid 1001 cannot write a directory it does not own). Native histograms survive the rewrite. |

`Export.SignalExported(signal, items, bytes)` reports each signal; a signal with zero results is reported as zero.

A Mimir writer never runs on a host bind mount: its shipper sets an xattr that a macOS bind mount does not support (spike S4). Only readers and thanos use bind mounts; the export needs no Mimir writer.

Opening offline: the zip's `compose.yml` mounts `data/` read-only into every backend, with writable state in `cache/`. Retention is off, and no component compacts, merges or deletes; the read-only mount refuses every write. The backends use the live stack's read-only component sets and the same usage-reporting switches. Mimir uses the generated bucket index (`max_stale_period: 87600h`, `ignore_blocks_within: 0`, as in `mimir.yaml`). Tempo is the single binary with `backend: local`, the same inert backend scheduler settings, and `query_backend_after: 1m`; it lists the directory itself, and its tenant-index write fails on the read-only mount with a logged, non-fatal error (spike S5). The documents nginx uses `root /data`.

### 8. Grafana: experiments and comparison

#### 8.1 The experiment picker and the newest default (addendum M3)

Picker: `sort: 0`, no `includeAll`, regex `/exp_key="(?<value>[^"]+)",exp_text="(?<text>[^"]+)"/`, with no space after the comma: Grafana builds each `query_result` row as `{k="v",k2="v2"} <value> <ts>`, joining labels with `,` alone, and the regex with a space matched nothing on 13.2.2 (changed by spike S6). The same holds for every two-label `query_result` regex. Every `query_result(...)` variable query is one line in the dashboard JSON: the Prometheus plugin recognises it with `/^query_result\((.+)\)\s*$/`, and `.` does not match a newline, so a multi-line query falls through to a series lookup and fails (S6). The queries below are wrapped for reading only; `ExperimentPickerTest` rejects a line break inside any `query_result(...)`.

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

The value is the last-seen timestamp. `sort_desc` puts the newest first and All (0) last; `sort: 0` keeps that order. Tests uses the same query without the All row. Without `includeAll`, Grafana scenes opens an empty or invalid `current` on the first option. On a cluster, `DashboardDefaults` sets `current` to the cluster's key. In the live local stack `current` is empty, so every open shows the newest experiment. S6 confirmed all of this on 13.2.2: an empty or stale `current` opens `options[0]`, a valid installed `current` is kept, the `sort_desc` order survives with `sort: 0`, and `var-experiment=.*/.*` selects the synthetic All, so the Tests-row fallback is not needed. The synthetic All stays: native `includeAll` with no `current` opens on All, which would make the most expensive query the default. (13.2.2's `staticOptions` with `staticOptionsOrder: 'after'` could replace the `vector(0)` branch; it was read in the source, not tested live, and is not used.)

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

`ExperimentPickerTest` checks that every cluster list carries `edl_tenant=~"$exp_tenant"`, that the picker regex has no space after the comma, and that no `query_result(...)` holds a line break. S6 confirmed that two tenants holding a same-named experiment stay apart on `mimir--all`, pre-label clusters included.

#### 8.3 Cluster pickers and span helpers (D5)

- `cluster` on the Experiment dashboards is multi-select with `includeAll` and no `allValue`, from the query in 8.2 site 3, with the short-name regex. "All" expands to the explicit option list, which is the experiment's clusters. Panels keep `cluster=~"$cluster"`; there is no exception to the cluster-filter rules. Twenty 77-character ids make about 1.6 KB per selector, sent by POST.
- Span helpers, about eight, independent of N: `exp_start` and `exp_end` = the min and max of `timestamp(count by (cluster) (up{cluster=~"$cluster"}))` over `[$lookback:${resolution}s]`; `exp_len` (padded by one step) and `exp_since_end`; each with `_s`/`_d` pairs, seconds first, as Grafana 13.2.2's `PanelTimeRange` needs (`PanelTimeOverrideTest`). Absolute-time panels get `timeFrom: ${exp_len_s}s-${exp_len_d}d` and `timeShift: ${exp_since_end_s}s-${exp_since_end_d}d`. The dashboard range stays relative.

#### 8.4 Panels for N clusters (D6, D7, D8)

Grafana 13.2.2 can set one `timeFrom`/`timeShift` per panel, a PromQL `offset` per query, instant queries with `@` and subqueries, table, bar chart, Trend and XY panels, the `merge`, `partitionByValues`, `groupBy` and `sortBy` transformations, per-column continuous colour, and repeats by a multi-value variable. It cannot shift time per series, give each repeated clone its own `timeShift`, or pick the i-th value of a multi-value variable. Exact full-resolution overlays need one query and helper chain per cluster, which works for a pair, not for N. Mimir allows 11,000 points per series, but Grafana rounds the computed interval down to a "nice" value (at worst by half), so `maxDataPoints` near 11,000 makes Mimir refuse the query. Overlay panels use `maxDataPoints: 5000`, safe for every span; 3 weeks gets a 300-second step (changed by spike S6).

Per-cluster windows come from the data in one `by (cluster)` query:

```
S = avg_over_time((F and on (cluster) A)[${exp_len}s:${resolution}s] @ ${exp_end})
F = per-cluster figure, e.g. sum by (cluster) (rate(cassandra_client_request_count_total{job="cassandra", cluster=~"$cluster"}[${resolution}s]))
A = active mask (D6), e.g. sum by (cluster) (rate(cassandra_client_request_count_total{job="cassandra", cluster=~"$cluster"}[${resolution}s])) > 0
```

Rows:

1. Leaderboard table, one row per cluster: Cluster (short name), Started (UTC), Active time (`count_over_time(A[...]) * resolution`), Throughput, Read p99, Write p99, Error rate, CPU busy, Disk I/O, GC pause %, Throughput CV. Instant `format: table` per figure, merged. Default sort by throughput, descending; every header sorts; per-column continuous colour, reversed for lower-is-better (D8: raw values only). Row links: System Overview and Cassandra Overview on the cluster's own window; the cluster pair with `var-cluster_a=${__data.fields.cluster}`, carrying `experiment`, `cluster`, the three datasource pickers, `lookback` and `resolution` (owner decision 19, m5); and the report.
2. Distributions: bar charts with clusters on the x axis, sorted by short name; `quantile_over_time(0.5|0.9|0.99, (F and on (cluster) A)[...])` for throughput, read p99, write p99 and CPU.
3. Elapsed-time overlay (D7): Trend panels, x = hours since each cluster's first request. A = `(timestamp(F and on (cluster) A) - on (cluster) group_left min_over_time(timestamp(A)[${exp_len}s:${resolution}s] @ ${exp_end})) / 3600`; B = `F`; range `format: table`; transformations `merge` → `organize` (exclude `Time`) → `partitionByValues(cluster)` → `joinByField(byField: "Value #A", mode: outer)`, then Trend with `xField: "Value #A"`; `maxDataPoints: 5000`. Changed by spike S6: Trend accepts one frame only, so `partitionByValues` alone fails with "Only one frame is supported"; the join works because every cluster's elapsed values sit on the `${resolution}` grid. XY chart in auto mode draws only the first frame and is not a fallback.
4. Timeline: absolute time over the experiment's span; a status-history activity panel per cluster and throughput per cluster.
5. Report: one iframe, `__DOCUMENTS_URL__/reports/${experiment}/index.html`. With "All" the iframe shows the documents server's error page; the panel description says to select one experiment.
6. The existing deep rows (fleet, latency, JVM, compaction, system resources, stress) are already `by (cluster)`; each gets the span override.

Experiment: System uses the same layout with system figures; its active mask is first to last `up`. It keeps `role` and has no host pickers.

#### 8.5 Dashboard fates (D4 as overridden by owner decisions 7, 8, 9, 18)

- `cassandra/cluster-comparison.json` is renamed to `cassandra/experiment-cassandra.json`, "Experiment: Cassandra", keeping uid `c09599ec-...`. It drops `baseline_cluster`/`candidate_cluster`, the `base_*`/`cand_*` helpers, the overlay, side-by-side and summary rows, and "A/B verdict". It gains 8.1 to 8.4. The cluster pair shows the side-by-side Percentile Ladders of A and B, and no latency heatmap (owner decision 21): the Cassandra latency series are per-node percentile gauges with no buckets.
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

## Spike results

All spikes ran on 2026-10-14 with the pinned images and synthetic data, against MinIO or filesystem stores (S2 alone read the account bucket, GET and HEAD only). The full reports are in `.spec-flow/spikes/`. Each decision below is folded into the sections above.

- **S1, read-only Mimir, Loki and Tempo behind the GET/HEAD gate.** The bucket listing (key and ETag, 170 objects) was byte-identical before and after querying all three signals in three tenants, single-tenant and federated. The design's component sets did not work as written. Decisions: Mimir `-target=query-frontend,querier,store-gateway,query-scheduler,ingester` (an idle ingester, no distributor); Loki `-target=read,query-scheduler -querier.query-store-only=true -common.compactor-address=http://127.0.0.1:3100`; Tempo as the single binary with a generated config file; `-usage-stats.enabled=false` (Mimir) and `-reporting.enabled=false` (Loki, Tempo) on every reader. Mimir and Loki sent no non-GET/HEAD request. On a 403, a warm Mimir and Loki keep answering from cache with no error and Tempo returns 500; on a cold start Mimir and Tempo exit 1 and Loki answers empty, so `store-check` and the signer healthcheck are what make expiry visible. memcached under `network_mode: "service:mimir"` breaks silently when Mimir restarts in place; decision: the four `memcached:11211` address overrides, with memcached on the project network and without `-l 127.0.0.1`.
- **S1-tempo, can Tempo 3.0.3's single binary run with no write path.** No: `-target=all` always starts the backend scheduler and worker, and `main.go` forces the worker unsharded, so it owns every tenant-index job and PUTs `tempo/<t>/index.pb.zst` on every poll; no key turns that off. Configuration makes the worker inert and removes the work-file write: `compaction_disabled: true` (no compaction or retention job), `backend_scheduler.backend_flush_interval: 87600h`, `backend_scheduler.local_work_path: /dev/null/backend-scheduler`, `usage_report.reporting_enabled: false`, `blocklist_poll_fallback: false`. Across two runs the only refused requests were the tenant-index PUTs: no `work.json`, no DELETE, no POST, shutdown included. A frozen `blocklist_poll` hides every block written after start, so the poll stays at Tempo's 5-minute default, which also bounds the per-poll listing cost. `/api/v2/traces/<id>` answers `200 {"trace":{}}` for a trace it does not find; tests check `trace.resourceSpans`, never the status code. Decision (owner redirect at Seam 1): the requirement is "no component writes to the bucket; the gate refuses every write; no component compacts, merges or deletes", in place of "no compactor runs".
- **S2, `aws-sigv4-proxy` with an SSO profile from a read-only `~/.aws`.** PASS with `HOME=/root`, which the image does not set; without it every request fails with `NoCredentialProviders`. Failures are HTTP 502 with a body `unable to proxy request - <sdk error>`, and the proxy stays up. An expired SSO access token cannot be refreshed on a read-only mount; `aws sso login` on the host fixes it and a running signer recovers with no restart. Decisions: `HOME=/root` on the signer, `store-check` treats the 502 as a credentials failure, and the session-credentials fallback is dropped.
- **S3, Tempo 3.0.3 accepts old spans in the export writer.** Spans up to 30 days old are accepted, but at the default `ingestion_time_range_slack` (2m) their block's range is the push time, so a reader never finds them over their real time. Decisions: the writer sets `live_store.wal.ingestion_time_range_slack: 87600h` and `storage.trace.wal.ingestion_time_range_slack: 87600h`, never 0; trace export search windows are at most 168h; drain waits for the `flushed` marker on every completed live-store block, because a graceful stop alone leaves a partial backend block; the offline reader sets `query_backend_after: 1m`. The test runs against a reader that holds only the backend blocks, never the writer, which answers from its live store either way.
- **S4, `thanos tools bucket rewrite` on Mimir 3.2.1 blocks and a generated bucket index.** PASS on ingester blocks and on the account compactor's merged blocks, native histograms included; a fresh read-only Mimir returned exact values for the kept clusters and nothing for the others. Decisions: pin `quay.io/thanos/thanos:v0.42.4`; `--prom-blocks --no-dry-run`, no `--delete-blocks`, no `--hash-func`; `FILESYSTEM` objstore at `<staging>/mimir/<tenant>`; `--network none`, no environment, the operator's uid:gid; drop empty rewritten blocks before indexing; write the version 2 bucket index in Kotlin (`MimirBucketIndexWriter`), last, listing exactly the blocks on disk (one omitted is a silent gap; one extra fails every query). A Mimir writer cannot run on a macOS bind mount (xattr); readers and thanos can.
- **S5, Tempo freshness with a stale index, and offline with a generated index.** A reader lists the bucket and serves blocks the index lacks. The single binary ignores `blocklist_poll_tenant_index_builders: 0`, never reads the index, lists every tenant on every poll and PUTs the index each time, so D19's index-builder setting is dropped and local freshness comes from the listing. Search sees the newest blocks only with `query_backend_after: 1m`. Offline, a generated `index.json.gz` with no `index.pb.zst` is read by a split reader, and the single binary serves the read-only tree by listing it; its write attempts fail on the read-only mount, logged and non-fatal.
- **S6, Grafana 13.2.2 experiment picker, panels and query cost.** Points (1) to (5) hold: an empty `current` opens `options[0]`, the `sort_desc` order survives `sort: 0`, native `includeAll` opens on All (so the synthetic All stays), `var-experiment=.*/.*` selects the synthetic All, and a valid installed `current` is kept. Two defects were found and fixed: every `query_result(...)` must be one line (guard test added), and the picker regex has no space after the comma, `/exp_key="(?<value>[^"]+)",exp_text="(?<text>[^"]+)"/`. The Trend overlay needs `merge` → `organize` (drop `Time`) → `partitionByValues(cluster)` → `joinByField("Value #A", outer)`, and `maxDataPoints: 5000`, not 11,000. `groupBy` with all values, "All" with 20 77-character ids, and two tenants with a same-named experiment pass. The picker over 180 days takes about 2 to 3 seconds at a 300-second resolution, about the same as today's pair picker; a 3-week, 20-cluster experiment opens in about 5 to 12 seconds; a cold open on "All" over 180 days takes about 60 seconds, which confirms owner decision 13.
- **S7, Mimir tenant federation for the `--cluster` resolver.** `/api/v1/series?match[]=up{cluster="x"}&start=0&end=<now>` with every tenant joined by `|` returns each series with `__tenant_id__`, labelled and pre-label, including 400-day-old blocks; with one tenant the label is absent. `/api/v1/label/__tenant_id__/values` ignores `match[]` and returns every tenant in the header, so it is never used. The two-matcher `label/cluster/values` query with `start=0` works federated and single-tenant. No Mimir query-length or lookback limit applies (none set, 3.2.1 defaults off). Decision: the resolver takes the tenant from `__tenant_id__`, and with one tenant uses the listed tenant.

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

- Query load: instant subqueries over the span; a 3-week, 20-cluster experiment at 5 minutes is about 6,000 steps over every host series. S6 measured a 3-week, 20-cluster open at about 5 to 12 seconds end to end, and a cold open on "All" over 180 days at about 60 seconds, dominated by the CPU leaderboard figure and the `@`-pinned Trend query, which no cache helps (instant `@` queries are neither split nor results-cached). A coarser `resolution` makes both cheaper. The memcached caches help warm opens.
- Overlay resolution drops on long experiments (300 seconds per point at 3 weeks, spike S6). The pair keeps exact overlays.
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

**Default experiment on open.** (a) Recommended (chosen by owner decision 13): the newest experiment, through a picker sorted by last activity, with a synthetic "All" that must be picked. (b) "All" on open: rejected, a cold open over every cluster of a tenant over 180 days is the most expensive query (critic round 3 M7). (c) Unset, letting Grafana pick the first option by name: rejected, the order would be alphabetical, not by recency. (d) Grafana's native `includeAll` in place of the synthetic All: rejected by spike S6, it opens on All when no `current` is set.

**Same-named experiments in the all-tenants view.** Round 3: a name in two tenants appears once and selects both. Rejected by owner decision 10 and critic round 3 M5. Chosen: the `<tenant>/<name>` picker value and `edl_tenant=~"$exp_tenant"` on every cluster list.

**`--cluster` tenant lookup.** Round 2: the registry's `_clusters` record. Round 3: federated label values of `__tenant_id__`, which critic round 3 M3 showed likely returns every tenant. Chosen (owner decision 16, D17 as refined by addendum M6): a series query, with the one-tenant and in-workspace short cuts. Alt: the tenant from annotation backup keys. Rejected. Spike S7 confirmed the critic's suspicion (the label-values endpoint returns every tenant in the header) and the series query; changed by S7, the tenant is read from `__tenant_id__` rather than `edl_tenant` first, since a federated response sets it on every series.

**Accepted recommendations (owner decision 16).**
- D1, ConfigMap key: `experiment` with env `EXPERIMENT`. Alt: an uppercase key that breaks the lowercase convention. Rejected.
- D3, report layout and pointer: one directory per upload and a forward-only pointer, UTC, `If-Match`. Alt: one `current/` directory overwritten on each upload; rejected, a file dropped from a newer report would linger unless deleted. Alt: compare S3 `LastModified`; alt: last writer wins (critic round 1 m1). Rejected.
- D6, summary window: each cluster's active window (client requests for Cassandra, first to last `up` for System). Alt: the whole lifetime. Rejected.
- D7, lining up N clusters: an elapsed-time Trend overlay, an absolute timeline, and the cluster pair for exact pair overlays. Alt: fixed overlay slots with their own pickers and offsets; alt: absolute time only. Rejected.
- D8, relative figures in the N view: raw values, sortable, with per-column colour; difference % only in the pair. Alt: % from the selection median or from a picked reference cluster, which needs a match that ignores `cluster` and breaks `SeriesClusterTest`/`ClusterJoinTest`. Rejected.
- D10, an owned file the user edited: overwrite and report. Alt: refuse. Rejected.
- D11, read-only: a GET/HEAD gate (required, since Tempo tries to write its index), read-only components, no compactor, and a listing-unchanged test. Alt: read-only components only; ruled out by critic round 3 M4. Alt: an IAM session policy; stronger but more IAM setup. Rejected. Changed by spikes S1 and S1-tempo: Mimir needs an idle ingester, Loki `read,query-scheduler` with a compactor address, and Tempo must run as the single binary, whose backend scheduler and worker cannot be turned off, so "no compactor runs" cannot hold literally. The owner redirected at Seam 1: no component writes to the bucket, the gate refuses every write and a test proves the listing does not change, and no component compacts, merges or deletes; Tempo's scheduler and worker run inert. Alt: freeze Tempo's `blocklist_poll` to stop all but the startup writes; rejected, it hides every block written after start. Alt: memcached in Mimir's network namespace (`network_mode: "service:mimir"`); rejected by S1, it breaks silently when Mimir restarts in place; the four address overrides are used instead.
- D12, credentials and tenants: `~/.aws` read-only into the signer with the profile and `HOME=/root`, `store-check` (the signer's 502 is a credentials failure), a signer healthcheck; home tenant `local create --tenant`, default `default`; a re-run picks up new tenants and reloads datasources. Alt: short-lived session credentials written at `create`. Dropped, changed by spike S2: the read-only mount works, and exported credentials never refresh. Alt: a writable `~/.aws` so the signer can refresh the SSO token; rejected, the root container would rewrite the owner's SSO cache.
- D13, dashboards refresh: the provisioning reload API. Alt: upload through `/api/dashboards/db`. Rejected.
- D15, tenant copy: the safe order and regenerated Mimir and Tempo indexes. Alt: copy the live indexes as they are; rejected, a copied live index would not match the copied blocks and Mimir's consistency check fails.
- D16, metrics query export: `thanos tools bucket rewrite`, filesystem objstore only, no AWS credentials, no network. Alt: remote read and `promtool create-blocks-from openmetrics`, which loses native histograms; alt: `query_range` resampling, which is lossy. Rejected.
- D17, temporary stacks: reader and writer compose projects; mixed tenants allowed in `--cluster`; the work directory kept and its size printed, never removed; the `--cluster` tenant through a series query. Alt: refuse mixed tenants; alt: remove the work directory once the zip is verified; alt: require an already running local stack. Rejected.
- D18, query exports stay in this issue, built last. Alt: split them into their own issue, which drops the `--cluster` and `--experiment` criteria. Rejected.
- D19, Tempo freshness: local Tempo lists the bucket when the index is stale, exports copy Tempo blocks by listing, and the account compactor's Tempo poll is 1 minute. Alt: `down` forces an index build; rejected, `down` only flushes. Alt: rely on the index alone; rejected, it can miss the last cluster's final blocks (critic round 2 M5). Changed by spike S5: the local Tempo is the single binary, which ignores `blocklist_poll_tenant_index_builders: 0` and lists the bucket on every poll, so that setting and the stale-index settings are dropped from the reader config; freshness comes from the listing, with `query_backend_after: 1m` so search sees the newest blocks.
- D16 (refined by spike S4): thanos pinned to `quay.io/thanos/thanos:v0.42.4`, `--prom-blocks --no-dry-run`, and a Kotlin bucket index writer. Alt: run a Mimir compactor once over the staging directory to write the index; rejected, it also merges and marks the staged blocks, and stopping it after the index is a race. Alt: `mimirtool`; it has no bucket-index command.
- m4, report front page: a root `index.md` or `index.html` is allowed as the front page, and the generated list is `_files.html`. Alt: reserve `index.md`, as #971 does. Rejected.
- m5, an empty `--tenant` export: fails and names the tenant and the prefixes. Alt: write an empty zip. Rejected.
- Debt fold-ins (observability-store spec, `TENANT` in CLAUDE.md, the `ObservabilityHttp` seam, `GrafanaServerSettings`, the `denyDataDeletes` KDoc, the shared picker-name constant): folded in. Alt: separate issues. Rejected. `Event.kt` split and the `CLUSTER_NAME` meanings: left for later, no issue filed.
