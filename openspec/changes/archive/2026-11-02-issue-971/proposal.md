## Why

The shared store holds every test of a tenant, but Grafana cannot use it for day-to-day performance work.  The owner works for himself (tenant `default`) and for customers (one tenant each), over many tests and months.  He needs to see the current cluster by default, switch to a past test, and compare an older test with the current one, including tests of different lengths.

Today no dashboard can select a tenant for logs, traces or annotations.  The `cluster` variable has no default and selects the first cluster by name.  Every dashboard opens at `now-1h`, so a past test shows no data until its window is found by hand.  `cluster-comparison` compares two clusters on one shared time range only.  There is no place for the owner's notes about a test.

Issue #971, "Tenant pickers, a Tests dashboard, cross-test comparison, and test documents in Grafana".  Part of epic #963, "Epic: shared observability store for all tests, with per-tenant export".  The tenant datasources themselves come from #970.

## What Changes

**Datasource pickers (all 43 core and kit dashboards)**
- A one-time edit through the `dashboard-editor` agent, in batches.  Each dashboard declares `metrics_datasource` (Prometheus), `logs_datasource` (Loki) and `traces_datasource` (Tempo) for each type it uses.  The existing `datasource` variable in 20 files is renamed to `metrics_datasource`.  Pyroscope gets no picker.
- Every fixed `mimir`, `loki` or `tempo` uid becomes the matching variable: panel and query datasources, variable queries, ad hoc filters, annotation queries, and uids inside Explore and data-link URLs.  Queries do not change otherwise.  The picker `current` values stay empty in the files.
- Every `/d/` link passes the carried variables that its source dashboard declares (`metrics_datasource`, `logs_datasource`, `traces_datasource`, `cluster`, `baseline_cluster`, `candidate_cluster`, `doc_tenant`), by `${name:queryparam}` or by an explicit `var-<name>=`.
- New unit test `DashboardDatasourceVariablesTest` (unit tier) on a new shared `DashboardFiles` test fixture: no fixed uid anywhere (URL-decoded links included, `pyroscope` allowed), every used type has its picker, and the `/d/` link rule.  It names the file.
- Fixes `opensearch.json` and `clickhouse-logs.json`, which use `${datasource}` without declaring it.

**Install-time defaults**
- New pure transform `DashboardDefaults` in `configuration/grafana/`.  It sets the picker defaults (`mimir`, `loki`, `tempo`), `cluster`, `baseline_cluster` and `candidate_cluster` to the current `<name>-<id>`, fills the `doc_tenant` options from `TenantDirectory` with the home tenant as default, and fills the documents host.  It changes no query.
- Wired into `GrafanaDashboardTreeWriter` (core tree), `KitRunnerCommand.installDashboards` (kits) and `GrafanaInstall` (`grafana install`, using the workspace cluster state).

**Tests dashboard**
- New core dashboard `dashboards/infrastructure/tests.json`, uid `tests`.  One row per cluster of the selected tenant, with start, end and duration from the first and last `up` sample in Mimir (subquery, about 5m step, over a `lookback` variable, default `180d`).
- Row links: System Overview, Cassandra Overview (both with the test's window), "Compare with the current cluster" (only `baseline_cluster`), and "Show documents".
- The selected test's documents in an iframe.

**Cross-test comparison**
- `cluster-comparison` (renames `cluster_a`/`cluster_b`), `ab-comparison` and `system-ab-comparison` get `baseline_cluster`, `candidate_cluster` and `lookback`, plus hidden helper variables evaluated at now.
- Three new top rows with curated figures: an overlay (panel `timeFrom` = the longer run's length, each run moved by a PromQL `offset`, which may be negative), side-by-side panels (`timeFrom` = run length, backward `timeShift` = now minus run end), and a summary table (`@ end` with `[len]`, difference in percent), with both runs' documents next to it.
- The dashboard range stays relative and ends at now.  No "Align runs" step.

**Test documents**
- New command group `report` with `report upload FILE...` (`commands/report/`).  It accepts only `.md` files named `[A-Za-z0-9._-]+`, rejects `index.md`, and names each file it rejects.  It uploads with the operator's credentials to `reports/<tenant>/<name>-<id>/`, so it works after `down`.
- New service `TestDocumentService`: converts each markdown file to HTML (new markdown library dependency), stores both, and rebuilds one `index.html` per test with every document under its own heading.  New `ObservabilityStore` paths for the document folder, `Constants.Observability.REPORTS_ROOT`, and one typed `Event.Report.*` event.
- `up` rebuilds the test's index, which says "No documents yet" when the test has none.
- Grafana pod: an `aws-sigv4-proxy` sidecar bound to `127.0.0.1` that signs with the instance role for the bucket's region, and a read-only web server sidecar (GET only, under `reports/`, fixed bucket prefix, normalized path, query string dropped) on a fixed host port.  Built with fabric8 in `GrafanaManifestBuilder`; ports in `Constants`; the web server config joins the `ConfigHashAnnotator` map.  Grafana sets `disable_sanitize_html`.
- The bucket region lookup moves out of `CompactorService` into a shared `services/aws/` helper.
- No Infinity plugin.  This change does not close #928.

**IAM**
- `AWSPolicy.Inline.S3AccessWildcard`: the instance role's delete deny also covers `reports/`.  No `PutObject` deny.

**Debt folded in (D1-D8)**
- D1 the two dashboards with an undeclared `${datasource}`; D2 `DashboardQueries.languageOf` recognises the picker names and prefers `type`; D3 `CoreDashboardAnnotationsTest` expects `${logs_datasource}`; D4 the shared `DashboardFiles` fixture; D5 the Explore link recipe in `dashboards/CLAUDE.md` takes the variables, and its references to the missing `bin/generate-dashboard-links.py` are removed; D6 the shared bucket region helper; D7 stale spec text (VictoriaMetrics, "All" default, fixed Mimir datasource, `cluster2`); D8 `GrafanaDashboardService` split into a Grafana HTTP client (dashboards, folders, annotations, `GrafanaAnnotationSource`) and a deploy service, which rewires the `down` annotation path.

**Docs**
- `dashboards/CLAUDE.md`, `configuration/CLAUDE.md`, `services/aws/CLAUDE.md`, root `CLAUDE.md`, `docs/development/kits.md`, `docs/user-guide/monitoring.md`, `docs/reference/commands.md`, `docs/reference/ports.md`, `docs/user-guide/mimir.md`, `docs/user-guide/loki.md`.

## Capabilities

### New Capabilities

- `tests-dashboard`: the Tests dashboard, its listing of tests, its row links, and its documents panel.
- `test-documents`: `report upload`, the name rules, the HTML copies and the index, the empty index at `up`, the signing proxy and the read-only web server, and `doc_tenant`.

### Modified Capabilities

- `multi-cluster-dashboards`: the `cluster` variable reads the metrics picker and defaults to the current cluster (not "All"); ad hoc filters use the picker; adds the pickers, the no-fixed-uid test, the link rule, and the install-time defaults.
- `cluster-comparison-dashboard`: `baseline_cluster`/`candidate_cluster` replace the stale `cluster2` and `cluster_a`/`cluster_b`; adds the run pickers, the three views and the documents for all three comparison dashboards.
- `observability-store`: the instance role delete deny covers `reports/`; the two sidecar images are pinned; the comparison queries parse on the pinned Mimir.
- `observability`: "Grafana Dashboards" allows the install-time pass, adds the sidecars and `disable_sanitize_html`, and moves the `cluster` variable off the fixed `mimir` uid.
- `grafana-install-dashboard`: `grafana install` applies the install-time pass.

## Impact

- Dashboards: all 43 files under `dashboards/` and `kits/*/dashboards/`; one new file `dashboards/infrastructure/tests.json`.
- Code: `configuration/grafana/` (`DashboardDefaults`, `GrafanaDashboardTreeWriter`, `GrafanaManifestBuilder`, `GrafanaDatasourceSet` callers), `services/` (the `GrafanaDashboardService` split, `GrafanaDashboardTreeUploader`, `ObservabilityStackService`, `TestDocumentService`, `AnnotationMirror` wiring), `services/aws/` (bucket region helper, `CompactorService`), `commands/report/`, `commands/grafana/GrafanaInstall.kt`, `commands/install/KitRunnerCommand.kt`, `commands/Up.kt`, `configuration/ObservabilityStore.kt`, `configuration/ConfigHashAnnotator.kt` callers, `providers/aws/AWSPolicy.kt`, `Constants.kt`, `events/Event.kt`, Koin modules, `gradle/libs.versions.toml`.
- Tests: new unit tests for the guard, `DashboardDefaults`, the name rules, the index builder, the web server config, the IAM deny and the manifest; `DashboardQueries` and `CoreDashboardAnnotationsTest` updates; `PromQlCompatibilityIntegrationTest` sample values for the new forms.
- Grafana rolls once for the new sidecars.  No backend changes.
- Out of scope: the tenant datasources (#970); comparison on kit dashboards; a Pyroscope picker; any query change other than the datasource references and the `cluster` filter (#983, folded in); the Infinity plugin (#928).
