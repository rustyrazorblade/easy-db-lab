## 0. Prerequisites

- [ ] 0.1 Confirm issue-966, issue-970 and issue-971 are archived before this change archives; this change's MODIFIED requirements are written against their text. Archive order: 966, 970, 971, then 902.
- [ ] 0.2 At archive, fix the stale `multi-cluster-dashboards` Purpose line (it names VictoriaMetrics) and give `observability-store` a real Purpose.

## 1. Spikes (before the phase that needs each)

- [ ] 1.1 S6 (before section 5): on Grafana 13.2.2 confirm (1) no `current` and no `includeAll` opens `options[0]`; (2) `query_result` keeps `sort_desc` order with `sort: 0`; (3) native `includeAll` with no `current` opens All; (4) `var-experiment=.*/.*` selects the synthetic All; (5) a valid installed `current` is kept; (6) the picker query time over 180 days on one tenant and on `mimir--all`, with the pre-label branch. Also `merge` → `partitionByValues` → Trend, `groupBy` with an all-values reducer, "All" with 20 long ids, the leaderboard query time for a 3-week 20-cluster experiment, a cold open on "All" over 180 days, the Trend overlay at 3 weeks, the local stack with memcached, and two tenants with a same-named experiment. Record results; if (1) fails, use the Tests row links with `var-experiment` as the fallback.
- [ ] 1.2 S1 (before section 6): the read-only Mimir, Loki and Tempo component sets behind a GET/HEAD gate, including their behaviour on a 403; compose recreates memcached with Mimir under `network_mode: "service:mimir"`, else switch to four `memcached:11211` address overrides.
- [ ] 1.3 S2 (before section 6): `aws-sigv4-proxy` resolves an SSO profile from a read-only `~/.aws`. If not, fall back to short-lived session credentials written at `create`.
- [ ] 1.4 S5 (before section 6): Tempo with `blocklist_poll_tenant_index_builders: 0`, `blocklist_poll_fallback: true` and a short `blocklist_poll_stale_tenant_index` lists when stale and never writes (look for the refused PUT at the gate); offline with a generated index.
- [ ] 1.5 S3 (before section 9): Tempo 3 accepts old spans in the export writer.
- [ ] 1.6 S4 (before section 9): `thanos tools bucket rewrite` on Mimir 3.2.1 blocks with a filesystem objstore; Mimir reads a generated `bucket-index.json.gz`.
- [ ] 1.7 S7 (before section 9): Mimir 3.2.1 accepts `start=0` on `/api/v1/series` and label values; measure query time; `__tenant_id__` present with two or more tenants and absent with one.

## 2. Phase 1: experiment and tenant labels

- [ ] 2.1 `Constants.Observability.EXPERIMENT_PATTERN = ^[a-z0-9][a-z0-9_-]{0,79}$`; `Constants.CLUSTER_NAME_PATTERN = ^[a-z][a-z0-9_-]{0,39}$` (checked at `Init.kt:390` and `ClusterStateManager.kt:56`).
- [ ] 2.2 `ExperimentKey(tenant, name)` with `pickerValue` (`<tenant>/<name>`) as the only builder; class KDoc says an experiment is (tenant, name). Unit tests for both patterns and the picker value.
- [ ] 2.3 `InitConfig.experiment: String?`, `ClusterState.experiment()` (default `clusterLabelName()`); `init --experiment` option; `Init.validateParameters()` checks the rule, states it, creates nothing; `init` stores the resolved value. No S3 call. Tests: default, explicit, invalid names (uppercase, leading `-`, `/`, 81 characters), digit start, state without an experiment.
- [ ] 2.4 `ClusterConfigData.of` adds key `experiment`; `clusterConfigEnv()` exposes `EXPERIMENT`. `TemplateService` context and `ClusterConfigWriter` gain `EXPERIMENT`.
- [ ] 2.5 Set `experiment` and `edl_tenant` at every `cluster` setter: collector scrape relabels (beyla, ebpf-exporter, yace, otel-collector) and the `resource/cluster` upsert in `otel-collector-config.yaml`; `OtelManifestBuilder` relabels and env; `loki.yaml` `index_label` (and fix the "these four" comment); stress sidecar config and `StressJobService`; Alloy `config.alloy` and `PyroscopeManifestBuilder`; `SidecarManifestBuilder` `-Dpyroscope.labels`; `EMRProvisioningService` `PYROSCOPE_LABELS`; presto and trino `start.sh.template` (read `.data.experiment` from `cluster-config`); `tempo.yaml` external labels; `BeylaManifestBuilder`; `AnnotationMirror` stream labels.
- [ ] 2.6 JFR shipper: `packer/cassandra/bin/edl-profiling-reconcile`, `ProfilingConfig`, `SetupInstance` carry `experiment` and `edl_tenant`; extend `edl-profiling-reconcile.test.sh` (stub curl; assert the labels in the ingest URL). Run `./gradlew testCassandraScripts`.
- [ ] 2.7 `ExperimentLabelCoverageTest` (unit tier): layer 1 renders each registered artifact and checks both labels (collector pipelines running `resource/cluster`, relabel lists, Alloy, sidecar and stress args, EMR, kit templates, annotation mirror streams through a fake push client, `loki.yaml` index labels including `edl_tenant`); layer 2 scans `src/main/kotlin`, `src/main/resources` and `packer/` for cluster-setting patterns and fails with "unregistered cluster setter". Include a negative fixture.
- [ ] 2.8 Move `generateDomainName` to `services/aws/OpenSearchDomainName.kt` (`object OpenSearchDomainName { fun of(clusterName: String): String }`); call it from `OpenSearchStart.kt:81` and `ClusterProvisioningService.kt:430`; trim the base, not the joined string. `OpenSearchDomainNameTest`: `_` to `-`, at most 28 characters, `-os` intact, both paths equal.
- [ ] 2.9 Loki integration test (`src/integrationTest/`, TestContainers): ingester id `acme.unthrottled_cursor-<id>` accepts lines, flushes, and the uploaded index file name carries it.
- [ ] 2.10 Run `./gradlew installDist`, then rebuild the Cassandra AMI with `build-cassandra` (only `packer/cassandra/` changed).
- [ ] 2.11 Live check on a throwaway cluster named with `_` and `--experiment`: `cluster-config` holds `experiment`; a metric, a log line, a span, an eBPF profile, a JFR profile and a mirrored annotation each carry `experiment` and `edl_tenant`; an OpenSearch domain provisions.
- [ ] 2.12 Docs: `help/provisioning.md` (`init --experiment`, the rule, the default, unique within a tenant, `_` in cluster names); `docs/reference/commands.md` `init` section; `docs/user-guide/monitoring.md` labels section.

## 3. Phase 2: report zip and pointer

- [ ] 3.1 `ObjectStore`: `putIfAbsent(content, path, contentType): Written | AlreadyExists`, `putIfMatch(content, path, etag): Written | PreconditionFailed`, `readVersioned(path): Versioned(content, etag)?`. Integration tests against a real S3-compatible container.
- [ ] 3.2 `ObservabilityStore.documentsRoot` keyed by `ExperimentKey`: `reports/<tenant>/<experiment>/`.
- [ ] 3.3 `ReportZip` (validation and zip build): directory exists and is non-empty; segments `[A-Za-z0-9._-]+`; no symlinks; no `..`; root `_files.html` rejected; `X.md` with sibling `X.html` rejected at any depth; every offender named. Unit tests per rule with `@TempDir`.
- [ ] 3.4 `ReportUnpacker` (unpack from the zip itself, HTML per `.md`, `_files.html`) and `ReportPointer` (forward-only, timestamp comment, `If-Match`/`If-None-Match`, re-read on 412; `ensurePlaceholder` with "No report yet"). Tests: older upload does not move the pointer back; concurrent writer retry; `index.md` front page; `_files.html` fallback.
- [ ] 3.5 `ReportArchiveService` orchestrates; zip key `report-<yyyyMMdd-HHmmss>.zip` in UTC, next free second when taken. Replaces `TestDocumentService.upload`.
- [ ] 3.6 `ReportUpload`: `@Parameters(arity = "1") dir: File`; `report upload a.md b.md` fails naming the directory form; experiment from workspace state; event `Report.Uploaded(zipKey, unpackedPrefix, pointerAdvanced, files)` in `events/Event.kt`.
- [ ] 3.7 `Up.kt:162`: `ReportPointer.ensurePlaceholder(key)` only when absent.
- [ ] 3.8 `AWSPolicy.denyDataDeletes` KDoc (`:217-222`): no report is replaced.
- [ ] 3.9 Live check: two uploads for one experiment, both zips byte-identical to the local ones, pointer on the newer; a second cluster of the experiment shows the same report; upload after `down`.
- [ ] 3.10 Docs: `help/observability.md` report section; `docs/reference/commands.md` `report upload`; `docs/user-guide/monitoring.md` documents section and the storage layout table.

## 4. Phase 3a: shared variables, defaults, guards (tests first)

- [ ] 4.1 `configuration/grafana/DashboardVariables.kt`: picker and pair variable names in one place; use it from `DashboardDefaults`, `ClusterFilterGuards`, `SeriesClusterGuards`, `DashboardDatasourceGuards` and the tests.
- [ ] 4.2 `DashboardDefaults`: context `cluster: String?`, `experiment: ExperimentKey?`; `CLUSTER_VARIABLES` = `cluster`, `cluster_b`; `cluster` stays All on dashboards declaring `experiment`; `cluster_a` no current; `experiment` current `<tenant>/<name>`; `home_tenant` option and current; live local sets no cluster/experiment; export sets its experiment; remove `doc_tenant`. Rewrite the KDoc (`:39-44`).
- [ ] 4.3 `DashboardDefaultsTest` (`:66-92,124-136`, `:72-73`).
- [ ] 4.4 `ClusterFilterGuards`: `Language.TRACEQL` (Tempo targets, Tempo variable queries, decoded `panes=` on a Tempo datasource); matcher `cluster_a|cluster_b`; `CLUSTER_LISTS` = `cluster`, `cluster_a`, `cluster_b`, `experiment`, `exp_tenant`, `exp_name`. `ClusterFilterTest` (`:34,61-62`) fixtures, with TraceQL positive and negative cases.
- [ ] 4.5 `ExperimentFallbackGuards` and `ExperimentFallbackTest` (rules a to d), positive and negative fixtures like `ClusterFilterTest.unscoped()`.
- [ ] 4.6 `ExperimentPickerTest`: every cluster list on a dashboard declaring `experiment` carries `edl_tenant=~"$exp_tenant"`; the picker value regex; the synthetic All last.
- [ ] 4.7 Renamed guards and tests: `ComparisonDashboardsTest` (`:22-27` list, `:30` per-dashboard map, `:31` `runFilter`, `:57-62` inverted, `:65-84` new variables and no `doc_tenant`, `:158-193` summary labels; experiment rules), `RolePickerTest` (`:24,30,114`, add `experiment-system.json`), `SeriesClusterGuards` (`:53,61`) and `SeriesClusterTest` (`:85-86,204-213`), `ClusterShortNameTest` (`:27,29,31,160,165`), `DashboardDatasourceGuards` (`:35-37`), `DocumentsIframeTest` (`:88-89`, 9 iframes and the path rule), `DashboardLinkTimeTest` (`:25-27`), `ClusterVariableTest` (`:52,71`, `:90-130`), `CoreDashboardAnnotationsTest`. Run: the dashboard scans fail as expected.

## 5. Phase 3b: dashboard JSON (through the `dashboard-editor` agent)

- [ ] 5.1 Rename `cassandra/cluster-comparison.json` to `cassandra/experiment-cassandra.json`, title "Experiment: Cassandra", uid kept; remove the pair variables, `base_*`/`cand_*`, the overlay, side-by-side and summary rows, "A/B verdict"; add `experiment`, `exp_tenant`, `exp_name`, `home_tenant`, the experiment-scoped `cluster`, span helpers, leaderboard, distributions, elapsed-time overlay, timeline, report panel; span override on the existing rows.
- [ ] 5.2 New `cassandra/cluster-pair.json`, uid `cluster-pair`, from today's top three rows plus the verdict stats and the side-by-side heatmaps; `cluster_a`/`cluster_b`, `a_*`/`b_*`, `a_report`/`b_report`, "B vs A %".
- [ ] 5.3 New `infrastructure/experiment-system.json`, uid `experiment-system`, with system figures, `role`, no host pickers.
- [ ] 5.4 `cassandra/ab-comparison.json`: the A/B rename (`cluster_a`/`cluster_b`, `a_*`/`b_*`, `build_a`/`build_b`), summary labels and difference rule, new variables, oldest-first and newest-first pair lists, narrowed `cluster` default All, report iframes.
- [ ] 5.5 `infrastructure/system-ab-comparison.json`: the same with `hosts_a`/`hosts_b`; link to `ab-comparison` carries `experiment`, `cluster_a`, `cluster_b` and drops `doc_tenant`.
- [ ] 5.6 `infrastructure/tests.json`: `experiment` replaces `cluster`; experiments table with tenant, name, first start, last end, cluster count and short names; row links "Newest report", "Compare clusters", "Show here"; "Clusters of `$experiment`" table with System Overview, Cassandra Overview and "Compare with the current cluster" (cluster pair, `var-cluster_a`); documents panel `reports/${experiment}/index.html`; annotations filtered on the experiment's clusters; pre-label branch.
- [ ] 5.7 TraceQL audit: fix every TraceQL use (system-overview, observability/tempo, clickhouse-logs, tidb and any other) to hold `resource.cluster` against the cluster variable.
- [ ] 5.8 Unit tier green: every guard from section 4 passes on every core and kit dashboard.
- [ ] 5.9 Live check with `installDist`, `grafana update-config` and read-back from Grafana: newest experiment on open; cluster picker lists only the experiment; pair defaults (A oldest, B current); kept dashboards work with A/B pickers; same-named experiments in two tenants stay apart; All on `mimir--all` shows both tenants; a pre-label cluster shows as an experiment of one and opens its documents.

## 6. Phase 4: local stack

- [ ] 6.1 Debt: `ObservabilityHttp` endpoint-and-tenant seam with a localhost implementation; existing callers keep the cluster one.
- [ ] 6.2 Debt: extract `GrafanaServerSettings` from `GrafanaManifestBuilder`; the builder and compose use it.
- [ ] 6.3 `LocalImages` from the builder constants plus `Constants.K8s.MIMIR_MEMCACHED_IMAGE`, the gate nginx pin and a new thanos pin in `Constants`; drift test over the generated compose.
- [ ] 6.4 Shared memcached args value used by `MimirManifestBuilder` and compose.
- [ ] 6.5 `localstack/`: `LocalStackSpec` (`Mode.Live`, `Mode.Offline`), `ComposeFile`/`ComposeService` (`@Serializable`, kaml), `LocalStackComposer`, `LocalStackWriter`, the manifest. Backend configs from the cluster resources plus flag overrides; read-only targets; Tempo poll settings; no compactor; `store-gate`, `store-signer` (`~/.aws` read-only, `AWS_PROFILE`, healthcheck), `store-check`; documents nginx; Grafana provisioning with `GrafanaDatasourceSet.build(TenantSet, BackendUrls.LOCAL_COMPOSE)`.
- [ ] 6.6 `observability local create <dir> [--tenant]`: required directory, create missing or empty, refuse a foreign non-empty one, owned files only, atomic writes, report edited files, never open data or `compose.override.yml`, SDK store check with `LocalStack.StoreUnreadable(profile, awsErrorCode)`, print tenant list, re-run reloads datasources. Unit tests with `@TempDir`: byte-identical data and override, foreign directory, missing argument, edited file reported.
- [ ] 6.7 `observability local dashboards <dir> [--from]`: health check, staging and rename, provisioning reload, "using packaged dashboards". Tests: stopped stack changes nothing.
- [ ] 6.8 Integration tests (`src/integrationTest/`): the read-only listing-unchanged test (bucket listing with keys and ETags before and after metrics, logs and traces queries); a gate refusal test for PUT, POST and DELETE; datasource UIDs equal to a cluster's for tenants `default` and `acme`.
- [ ] 6.9 Live check from an `installDist` build with no source tree on the path: create, `docker compose up`, torn-down clusters show, expired credentials fail loudly, `local dashboards --from` loads an edit.
- [ ] 6.10 Docs: new page `docs/user-guide/local-observability.md` (from "no cluster, data in S3" to a dashboard), `docs/SUMMARY.md`, `docs/reference/commands.md` `observability local`, `help/observability.md` local-stack section.

## 7. Phase 5: tenant export and offline open

- [ ] 7.1 `ExportSelector` (sealed `Tenant`, `Clusters`, `Experiment`) from an exclusive `ArgGroup`; `--tenant` as the experiment's tenant; errors naming the three choices; path checks; tenant default with the reason in the output.
- [ ] 7.2 Atomic zip writer: `<path>.partial-<random>`, move without overwrite, partial removed on failure, `Export.Failed(signal, step)`, `Export.Completed` with `excluded = [profiles, issue 1016]`.
- [ ] 7.3 Tenant copy in the safe order: Loki index table by table with redo; Tempo by listing with `TempoTenantIndexWriter`; Mimir from one bucket index read with `MimirBucketIndexWriter`; immutable objects last; size estimate and disk check; empty tenant fails naming tenant and prefixes.
- [ ] 7.4 Account compactor: `compactor/tempo-backend.yaml` `compacted_block_retention: 48h` and `blocklist_poll: 1m`; test on the built configuration.
- [ ] 7.5 Offline `compose.yml` (`Mode.Offline`): `data/` read-only, `cache/` writable, retention off, no compactor, documents nginx `root /data`; README with the image list from `LocalImages` and the #1016 note; dashboards after `DashboardDefaults` with the export context.
- [ ] 7.6 Tests: selector errors, path errors, atomicity (fail mid-copy leaves no zip), generated indexes match the copied blocks, nothing written to S3 (listing unchanged).
- [ ] 7.7 Live check: export a tenant, open it on a machine with networking off and no AWS credentials (images pulled beforehand) with plain `docker compose up`; metrics, logs, traces, annotations, dashboards and the report show.
- [ ] 7.8 Docs: export section in the new page, `docs/reference/commands.md` `observability export`, `help/observability.md` export section.

## 8. Phase 6: query exports

- [ ] 8.1 Temporary reader and writer compose projects (`docker compose -p`, random localhost ports) through `ObservabilityHttp`; work directory kept, location and size printed.
- [ ] 8.2 `ClusterTenantResolver` (workspace, one tenant, series query across tenants; failure messages) with a fake `ObservabilityHttp`; tests for zero, one and two tenants and pre-label series.
- [ ] 8.3 `--experiment` discovery with the pre-label branch; covered experiments for `--cluster`; their reports copied.
- [ ] 8.4 Logs export (paged `query_range` with tie-break, structured metadata, push to writer Loki, flush); traces export (split windows, fetch, OTLP replay, drain); metrics export (block copy, thanos rewrite with filesystem objstore, no credentials, `network_mode: none`, generated index). `Export.SignalExported(signal, items, bytes)` with zero as zero.
- [ ] 8.5 Integration tests on TestContainers: only the selected clusters' data in the zip; mixed tenants; same-named experiment in two tenants exports only one.
- [ ] 8.6 Live check: `--cluster x --cluster y` and `--experiment e --tenant t` against the real store, opened offline.

## 9. Docs, specs and CLAUDE.md

- [ ] 9.1 `dashboards/CLAUDE.md` (`:68-69`, `:89`, `:105-107`, `:127` Tests link to `cluster-pair` with `var-cluster_a`, `:134-135`, `:166`, `:178-182`): experiment picker, `home_tenant`, A/B names, new dashboards, pre-label rule, TraceQL rule.
- [ ] 9.2 Root `CLAUDE.md`: `TENANT` to `tenant` with `experiment` (debt 2); documents layout `reports/<tenant>/<experiment>/`; `report upload <dir>`; local stack and export; comparison dashboards paragraph (`:388`).
- [ ] 9.3 `configuration/CLAUDE.md` (`:169`), `events/CLAUDE.md` (new `Report`, `LocalStack` and `Export` events), `commands/CLAUDE.md` (new commands).
- [ ] 9.4 `docs/user-guide/monitoring.md`: experiments, the report zip, links to the local-stack page.
- [ ] 9.5 Run `./gradlew ktlintFormat`, `./gradlew detekt` (JDK 21; fix findings with code only), and `./gradlew check` in a subagent.
