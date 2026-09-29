## 1. Guard test and shared fixture (lands first and fails)

- [x] 1.1 Extract the dashboard file walk from `DashboardDatasourceTest.dashboards()` into a shared `DashboardFiles` test fixture (core `dashboards/` and every `kits/*/dashboards/`), and move `DashboardDatasourceTest` onto it (D4).
- [x] 1.2 Add `DashboardDatasourceVariablesTest` (unit tier, `src/test/.../configuration/grafana/`), test 1: walk each JSON tree; fail on a `uid` key or a string `datasource` key equal to `mimir`, `loki` or `tempo`; URL-decode every string holding `/explore` or `panes=` (keep `+`) and fail on `"(uid|datasource)"\s*:\s*"(mimir|loki|tempo)"`; allow `pyroscope`; report `<file>: <json path>`.
- [x] 1.3 Test 2: a file that references a datasource of type `prometheus`, `loki` or `tempo` must declare a `type: datasource` variable with that `query`.  This catches `opensearch.json` and `clickhouse-logs.json` (D1).
- [x] 1.4 Test 3 (F2, F4): every `/d/` link passes each carried variable its source dashboard declares (`metrics_datasource`, `logs_datasource`, `traces_datasource`, `cluster`, `baseline_cluster`, `candidate_cluster`, `doc_tenant`) as `${name:queryparam}` or explicit `var-<name>=`, and passes no `${name:queryparam}` for an undeclared variable.  Report the file and the link.
- [x] 1.5 Unit-test the three checks themselves on small inline JSON samples (a fixed uid in a panel, in an encoded `panes=` link, a `pyroscope` uid, a missing picker, a link that drops a declared variable, an explicit `var-cluster=`, an undeclared `${cluster:queryparam}`).  Run the suite: the dashboard scan fails as expected.

## 2. Query classification and annotation tests (D2, D3)

- [x] 2.1 `DashboardQueries.languageOf` (integration tier): classify by `type` first; recognise `${metrics_datasource}`, `${logs_datasource}` and `${traces_datasource}` as well as the stable uids, so no Loki or Prometheus query drops out of `LogQlCompatibilityIntegrationTest` or `PromQlCompatibilityIntegrationTest`.
- [x] 2.2 `CoreDashboardAnnotationsTest`: expect the annotation datasource `{"type":"loki","uid":"${logs_datasource}"}`.
- [x] 2.3 Add sample values for the new variables to the `DashboardQueries` substitution table (`lookback`, `resolution`, run starts, ends, lengths, a negative and a positive offset, `@` end), so `PromQlCompatibilityIntegrationTest` parses the negative `offset`, `@` and subquery forms against the pinned Mimir (verification item 1).

## 3. Install-time defaults (F10)

- [x] 3.1 `DashboardDefaults` in `configuration/grafana/`, pure, on kotlinx `JsonObject`: set picker `current` to `mimir`/`loki`/`tempo` by picker type (including `KeeperDatasource`); set `cluster`, `baseline_cluster`, `candidate_cluster` `current` to `<name>-<id>` (one-element list when `multi`); fill `doc_tenant` options from the tenant list and default it to the home tenant; replace the documents host placeholder.  No string splicing; nothing else changes.
- [x] 3.2 Unit tests for `DashboardDefaults`: multi and single `cluster`; the run variables; picker types; `doc_tenant` options and default; a dashboard with none of these comes out equal; every query and variable query is unchanged.
- [x] 3.3 Core path: `ObservabilityStackService` passes the cluster label, tenants and documents host through the deploy service and `GrafanaDashboardTreeUploader` to `GrafanaDashboardTreeWriter`; `render` parses, applies `DashboardDefaults` and the `__PYROSCOPE_URL__` substitution, then serialises.  Update its KDoc ("one substitution").  Extend `GrafanaDashboardTreeWriterTest`.
- [x] 3.4 Kit path: `KitRunnerCommand.installDashboards` applies `DashboardDefaults` after `KitDashboardInstance.rendered()` and before install.  Test on a real kit dashboard file.
- [x] 3.5 `grafana install`: `GrafanaInstall` loads the workspace cluster state and tenants and applies the same pass.  Extend `GrafanaInstallTest`.

## 4. Split `GrafanaDashboardService` (D8)

- [x] 4.1 Split into a Grafana HTTP client (dashboard install, folder lookup, annotation create and fetch; implements `GrafanaAnnotationSource`) and a deploy service (datasource ConfigMap, dashboard tree upload, K8s apply, `ConfigHashAnnotator` map).  Each gets a class-level KDoc.
- [x] 4.2 Rewire every caller: `ObservabilityStackService`, `KitRunnerCommand`, `GrafanaInstall`, `grafana annotate`, `grafana backup`, the `down` annotation mirror and backup path (`AnnotationMirror`, `TeardownFlushService`), and the Koin modules.
- [x] 4.3 Move the existing `GrafanaDashboardServiceTest` cases to the class that now owns each behavior; deploy tests no longer mock HTTP.  Run `AnnotationMirrorTest` and `AnnotationMirrorIntegrationTest` (in a subagent).
- [x] 4.4 Run detekt; fix findings with code only.

## 5. Picker edit of every dashboard (through the `dashboard-editor` agent, in batches)

- [x] 5.1 Batch 1, kit dashboards: declare the pickers each file uses with empty `current`; rename `datasource` to `metrics_datasource`; move every fixed uid to its picker, keeping `type`; byte-preserving `perl -0pi` passes, never `jq` rewrites; `jq empty` and `git diff --stat` on each file.  Deploy and read back per the agent's sequence.
- [x] 5.2 Batch 2, `dashboards/infrastructure/`.
- [x] 5.3 Batch 3, `dashboards/cassandra/`.
- [x] 5.4 Batch 4, `dashboards/observability/`, `dashboards/networking/`, `dashboards/opensearch/` (declares `metrics_datasource` for its `cluster` variable, D1).
- [x] 5.5 Hand-edit the 4 files with Explore URLs so the `panes=` JSON names the picker, left unencoded.
- [x] 5.6 Rewrite every `/d/` link to pass the carried variables its source declares (F2, F4), including the system-ab to ab link with `baseline_cluster` and `candidate_cluster`.
- [x] 5.7 Run the guard tests, the query-compatibility integration tests (in a subagent) and `CoreDashboardAnnotationsTest`: all pass.

## 6. Tests dashboard

- [x] 6.1 Through the `dashboard-editor` agent, add `dashboards/infrastructure/tests.json`, uid `tests`: pickers; single-select `cluster`; `lookback` (custom, default `180d`); `resolution` (custom, default `5m`); `doc_tenant`.
- [x] 6.2 Table panel: start and end queries from decision 4 (instant, table format, joined by `cluster`) and a duration column.
- [x] 6.3 Row data links: System Overview and Cassandra Overview (row cluster, window padded one step), "Compare with the current cluster" (`var-baseline_cluster` only, no absolute range), "Show documents"; each carries the pickers and `doc_tenant`.
- [x] 6.4 Documents panel: Text panel in HTML mode with an iframe to `<documents host>/reports/${doc_tenant}/${cluster}/index.html`.
- [x] 6.5 Guard tests pass on the new file; `PromQlCompatibilityIntegrationTest` parses its queries.

## 7. Comparison views (curated figures, decision 12)

- [x] 7.1 Through the `dashboard-editor` agent, rename `cluster_a`/`cluster_b` to `baseline_cluster`/`candidate_cluster` in `cluster-comparison.json` and update every reference.
- [x] 7.2 Add to `cluster-comparison`, `ab-comparison` and `system-ab-comparison`: `baseline_cluster`, `candidate_cluster` (single-select, over `$lookback`, evaluated at now), `lookback`, `resolution`, `doc_tenant`, and the hidden helper variables (starts, ends, lengths, `max_len`, overlay offsets, side-by-side shifts).
- [x] 7.3 Overlay row: panel `timeFrom` `${max_len}s`; each run's query with `offset ${<run>_offset}s`; panel description notes the relative range and the annotation limit.
- [x] 7.4 Side-by-side row: one column per run, panel `timeFrom` `${<run>_len}s`, `timeShift` `${<run>_since_end}s`.
- [x] 7.5 Summary row: one instant query per figure with `[${<run>_len}s] @ ${<run>_end}` and `100 * (C - B) / B`; the baseline and candidate documents iframes side by side next to it.
- [x] 7.6 Figures: `cluster-comparison` and `ab-comparison` throughput, read p99, write p99, error rate, CPU, disk I/O, GC pause; `system-ab-comparison` CPU, memory, disk, network.  Verify metric names against the metrics catalog.  The new views filter by cluster only; existing panels and filters stay below.
- [x] 7.7 Guard tests and `PromQlCompatibilityIntegrationTest` pass.

## 8. `report upload`

- [x] 8.1 `Constants.Observability.REPORTS_ROOT = "reports"`; `ObservabilityStore.documentsRoot(cluster)` = `reports/<tenant>/<name>-<id>/` and `document(name)`.  Extend `ObservabilityStoreTest`.
- [x] 8.2 Add a markdown library (GFM tables) to `gradle/libs.versions.toml` and the build.
- [x] 8.3 Pure name check: `.md` only, `[A-Za-z0-9._-]+`, not `index.md`, file exists and is not a directory; returns every rejected file.  Unit tests for each rule.
- [x] 8.4 Pure index builder: from `(name, html)` pairs in name order, one section per document headed with its name; "No documents yet" when empty; `<meta charset="utf-8">`.  Unit tests (empty, one, several, a replaced document appears once).
- [x] 8.5 `TestDocumentService` (interface + default): check all names first; upload each `.md` and its `.html` (written to a local `.html` file) through `ObjectStore`; list the folder's `.md` keys; download and render them; write `index.html` the same way.  Integration test against S3 TestContainers/LocalStack in the pattern of `S3ObjectStoreIntegrationTest`: key layout, replace on same name, index holds every document.
- [x] 8.6 `commands/report/Report.kt` and `ReportUpload.kt`: `@Parameters(arity = "1..*")`, no `@RequiresProxy`; register in the command tree.  One typed event `Event.Report.DocumentsUploaded` with each name and S3 URI and the index URI; add it to `events/Event.kt` and its serialization.
- [x] 8.7 `Up`: rebuild the test's index with the operator's credentials after the account bucket is configured (F7).
- [x] 8.8 Command test: rejected files are named and nothing is uploaded; the command works with no running cluster.

## 9. Documents sidecars and Grafana settings

- [x] 9.1 Extract `CompactorService.bucketRegion` into a shared bucket region helper in `services/aws/` and use it from both places (D6).  Keep `CompactorServiceTest` passing.
- [x] 9.2 `Constants`: the proxy port, the web server host port (not 8081), and the pinned `aws-sigv4-proxy` and web server images.
- [x] 9.3 `GrafanaManifestBuilder.buildDeployment(...)` takes the bucket, the bucket region and the ports: add the `aws-sigv4-proxy` container bound to `127.0.0.1` (`--name s3 --region <region> --host s3.<region>.amazonaws.com`) and the web server container, built with fabric8; set `GF_SECURITY_DISABLE_SANITIZE_HTML=true`.
- [x] 9.4 Web server config as a classpath resource loaded with `TemplateService`: GET only, only normalized paths under `/reports/`, fixed bucket prefix, query string dropped, forward to the proxy.  Add its ConfigMap to the `ConfigHashAnnotator` map.
- [x] 9.5 Unit tests on the manifest (containers, images pinned, proxy bound to loopback, ports, env) and on the rendered web server config (method and path rules).
- [x] 9.6 Integration test with a K3s TestContainer (in a subagent): the web server refuses `PUT`, `POST`, `DELETE`, a path outside `reports/`, and `/reports/../mimir/`, and forwards a `GET` under `reports/`.
- [x] 9.7 Confirm the instance role can `GetObject` under `reports/` in the account bucket.

## 10. IAM

- [x] 10.1 `AWSPolicy.Inline.S3AccessWildcard`: add `reports/*` to the instance role's `s3:DeleteObject`/`s3:DeleteObjectVersion` deny.  No `PutObject` deny.
- [x] 10.2 Extend `AWSpolicyTest` to assert the deny covers `reports/*`.

## 11. Link recipes (D5)

- [x] 11.1 Remove the two references to `bin/generate-dashboard-links.py` from `dashboards/CLAUDE.md`; the script does not exist, and the inline recipe is the one source (owner decision at Seam 1).
- [x] 11.2 Update the Python snippets in `dashboards/CLAUDE.md` to take the picker variables.

## 12. Docs

- [x] 12.1 `dashboards/CLAUDE.md`: the pickers replace "the uids are constants"; the guard tests; the link rule; the Explore recipes; the Tests dashboard; the comparison views and helper variables; the documents iframe and `doc_tenant`.
- [x] 12.2 `configuration/CLAUDE.md`: `DashboardDefaults`, the tree writer's pass, the two sidecars, `ObservabilityStore.documentsRoot`.
- [x] 12.3 `services/aws/CLAUDE.md` and root `CLAUDE.md`: `reports/` in the deny list and the store layout; the `GrafanaDashboardService` split; the bucket region helper; the new sidecars in the Observability section.
- [x] 12.4 `docs/development/kits.md`: kit dashboards declare the pickers, name no fixed uid, and follow the link rule; the install-time `cluster` default.
- [x] 12.5 `docs/user-guide/monitoring.md`: the pickers, the current-cluster default, the Tests dashboard, the comparison views, `report upload`, and the note that two concurrent uploads can drop a document from `index.html` until the next upload.
- [x] 12.6 `docs/reference/commands.md`: `report upload`.  `docs/reference/ports.md`: the proxy and web server ports.  `docs/user-guide/mimir.md` and `docs/user-guide/loki.md`: the pickers where they mention datasources.
- [ ] 12.7 On archive, update the `multi-cluster-dashboards` Purpose, which still names VictoriaMetrics (D7).

## 13. Build and live verification

- [x] 13.1 `./gradlew ktlintFormat`, `./gradlew check` on JDK 21 (in a subagent), and `./gradlew installDist`.
- [ ] 13.2 Live cluster: verification item 2, variable interpolation in panel `timeShift` and `timeFrom` on the comparison dashboards.
- [ ] 13.3 Live cluster: verification item 3, the iframe renders under `disable_sanitize_html`.
- [ ] 13.4 Live cluster: verification item 4, the browser reaches the web server over Tailscale and SOCKS, and the proxy signs for the bucket's region.
- [ ] 13.5 Live cluster: `report upload` before and after `down`, and the Tests dashboard shows the documents.
- [ ] 13.6 Live comparison, short tests only (owner-authorized, no 24h test): one new cluster with 15 to 20 minutes of data and a short stress load, compared against a past test already in the store in the same tenant within `lookback`, of a different length.  If no such past test exists, run two short clusters in the same tenant one after the other (for example 20 minutes, then `down`, then a new cluster for 10 minutes); this also checks that the torn-down cluster shows on the Tests dashboard.  Check the overlay, the side-by-side panels, the summary table and both runs' documents.
