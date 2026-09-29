## Context

Issue #966 put every backend at the top level of the account bucket.  Issue #970 added the tenant datasources (`GrafanaDatasourceSet`): the stable `mimir`, `loki`, `tempo` and `pyroscope` uids read the cluster's own tenant, `<signal>-<tenant>` reads one tenant, and `<signal>--all` reads every tenant.  Mimir now reads the whole shared store (`query_store_after: 0`).  Both changes are merged but not archived, so their delta specs are the current behavior.

The dashboards do not use any of this yet.  On 2026-09-28 there are 43 dashboard files.  Fixed uids: `mimir` in 28 files, `loki` in 11, `tempo` in 1, `pyroscope` in 3.  20 files have a Prometheus `datasource` variable.  26 files have a `cluster` variable, with no stored default.  Every dashboard opens at `now-1h`.

Two install paths exist.  Core dashboards are a file tree: `GrafanaDashboardTreeWriter.render` copies each JSON with one substitution (`__PYROSCOPE_URL__`), and `DefaultGrafanaDashboardTreeUploader` ships it from `ObservabilityStackService`.  Kit dashboards go through the HTTP API: `KitRunnerCommand.installDashboards` renders them with `KitDashboardInstance`, then calls `GrafanaDashboardService.installDashboard`.  `grafana install` is a third path, used by the `dashboard-editor` agent.

Grafana is `grafana/grafana:13.2.2`, `hostNetwork`, with the image renderer on port 8081.

The owner made the decisions below at the design stop on 2026-09-28 (decisions 1-20, 17a, 17b) and after the second critique (F1-F10).  Where they differ from the architect's proposal, the owner's decisions apply.

## Goals / Non-Goals

**Goals:**
- Every core and kit dashboard can select a tenant for metrics, logs and traces, and opens on the current cluster.
- A Tests dashboard lists every test of a tenant with its window and links to it.
- The three comparison dashboards compare two runs of any lengths in three views.
- The owner can attach markdown documents to a test, before or after `down`, and read them in Grafana.
- The cluster cannot delete documents.
- Pay down the nearby debt D1-D8.

**Non-Goals:**
- The tenant datasources themselves (#970).
- A comparison on kit dashboards.  Each kit gets its own issue later.
- A Pyroscope picker.
- Any query change other than the datasource references.
- A `cluster` variable on the 17 dashboards that have none (#983).
- The Infinity plugin (#928).
- Documents of two tenants on one dashboard.

## Decisions

### 1. Picker names (decision 1)

`metrics_datasource`, `logs_datasource` and `traces_datasource`, labels "Metrics", "Logs" and "Traces".  The `${datasource}` refs in the 20 files are renamed.  Rationale: one consistent vocabulary; the rename is mechanical.  ClickHouse's `KeeperDatasource` stays as a second Prometheus picker and is defaulted the same way.

### 2. Picker defaults (decision 2)

The files keep `current` empty.  The install-time pass sets `current` to `mimir`, `loki` and `tempo`.  The guard test has no exception.  Rationale: the acceptance criterion says "anywhere", and relying on Grafana's fallback picks the first datasource by name.

### 3. Run defaults (decision 3, then F8)

The installer sets both `baseline_cluster` and `candidate_cluster` to the current cluster.  Rationale: a query variable without "All" cannot have no default; Grafana picks the first option, which could be any cluster (critique 2, finding 12).

### 4. Test window (decision 4, F9)

A test's window is the first and last `up` sample of the cluster in Mimir, found with a subquery at about a 5m step: start is `1000 * min by (cluster) (min_over_time(timestamp(count by (cluster) (up))[$lookback:$resolution]))`, end is the same with `max`.  The window is the whole cluster life, idle time included.  The Tests listing and the comparison pickers share one `lookback` variable, default `180d` (F9).  The dashboard time range does not limit the listing.

### 5. Tests row links (decision 5, F1)

System Overview and Cassandra Overview, with `var-cluster` set to the row's cluster and `from`/`to` set to the window padded by one step.  "Compare with the current cluster" sets only `baseline_cluster` and no absolute range, because panel `timeFrom` is ignored under an absolute range.  "Show documents" reopens Tests with `var-cluster` set.  Every link carries the pickers and `doc_tenant` (F4).

### 6. Run pickers (decision 6, F1, F9)

`baseline_cluster` and `candidate_cluster` are single-select `query_result` variables over `$lookback`, evaluated at now.  The dashboard range stays relative and ends at now, so an instant query at the range end sees every run.

### 7. Filters in the new views (decision 7)

The new views filter by cluster only.  The existing build and host variables of `ab-comparison` and `system-ab-comparison` apply only to the existing panels.

### 8. Overlay, side by side, summary (decision 8 replaced by F1)

Hidden helper variables, evaluated at now: `base_start`, `base_end`, `cand_start`, `cand_end` (epoch seconds), `base_len`, `cand_len`, `max_len`, and per run the overlay offset and the side-by-side shift.

- **Overlay.**  Panel `timeFrom` = `${max_len}s`, so the panel covers `[now - max_len, now]`.  `axis_start = now - max_len`.  Each run's queries add `offset ${<run>_offset}s`, with `<run>_offset = axis_start - <run>_start`.  A run that started after `axis_start` (the running, shorter current cluster) gets a negative offset.  The shorter run has no samples past its own length, so it ends there.  The whole axis is in the past, so Mimir's `max_query_into_future` never applies.
- **Side by side.**  One panel column per run.  Panel `timeFrom` = `${<run>_len}s` and `timeShift` = `${<run>_since_end}s`, where `<run>_since_end = now - <run>_end`.  The shift is backward, which is the only way `timeShift` moves.  Each axis shows the run's real times.
- **Summary.**  Instant queries with `[${<run>_len}s] @ ${<run>_end}`, one query per figure, and the difference in percent as PromQL `100 * (C - B) / B` on label-free aggregates (architect's recommendation, no transform chains).

Annotations in the overlay are placed at axis time only for a run with offset 0.  The panel description says so.

### 9. Links carry the pickers (decision 9, F2, F4)

A `/d/` link passes every carried variable that its source dashboard declares, either as `${name:queryparam}` or as an explicit `var-<name>=`, and passes no `${name:queryparam}` for a variable the source does not declare.  The guard test enforces both rules.

### 10. All tenants (decision 10)

The criterion reads: the all-tenants datasource AND "All" in `cluster` show every cluster of every tenant.  `label_values(up, cluster)` on `mimir--all` already lists every tenant's clusters.

### 11. Rename (decision 11)

`cluster_a`/`cluster_b` become `baseline_cluster`/`candidate_cluster` in `cluster-comparison`.

### 12. Curated figures (decision 12)

Three new top rows (Overlay, Side by side, Summary + documents).  `cluster-comparison` and `ab-comparison`: throughput, read p99, write p99, error rate, CPU, disk I/O, GC pause.  `system-ab-comparison`: CPU, memory, disk, network.  Existing panels stay below.

### 13-14. Command and names (decisions 13, 14, F6)

`report upload FILE...` in a new `report` group.  Positional parameters are fine; this is not a kit argument.  No `@RequiresProxy`: it needs only `state.json` and the operator's AWS credentials, so it works after `down`.  It accepts only `.md` files named `[A-Za-z0-9._-]+`, rejects `index.md`, and names each rejected file.  It checks every file before it uploads any.  The charset avoids URL-encoding hazards through the web server and the signing proxy.

### 15. S3 access (decision 15)

An `aws-sigv4-proxy` sidecar in the Grafana pod, bound to `127.0.0.1`, signing with the instance role for the bucket's region (`--name s3 --region <bucket region> --host s3.<bucket region>.amazonaws.com`).  The bucket region comes from the helper extracted from `CompactorService.bucketRegion` (D6).  Binding to loopback stops other hosts on the VPC or tailnet from using the cluster role.

### 16-17b. Documents (decisions 16, 17, 17a, 17b, F5, F7)

- `report upload` stores `<stem>.md` and `<stem>.html` and rewrites one `index.html` per test from every `.md` in the folder, each under its own heading, in name order.  Generated HTML declares `<meta charset="utf-8">` and is uploaded from a local `.html` file, so `S3ObjectStore` infers `text/html`.
- Grafana shows the index in an iframe in a Text panel in HTML mode, with `[security] disable_sanitize_html = true`.
- The browser loads the iframe from a read-only web server sidecar on a fixed host port, reachable like Grafana.  It prefixes the fixed bucket, matches the normalized path under `reports/`, accepts `GET` only, and drops the query string.  It forwards to the signing proxy.  The iframe host is filled at install time, as `__PYROSCOPE_URL__` is, with the control node's private IP.
- `doc_tenant` is a custom variable.  The installer fills its options from `TenantDirectory` and defaults it to the home tenant.  One `doc_tenant` per dashboard; no cross-tenant documents (F5).
- `up` rebuilds the index, which says "No documents yet" when the test has none (F7).  Past clusters without an index show the S3 error; the owner accepts that.
- 971 installs no Infinity plugin and does not close #928 (17a).

### 18. IAM (decision 18)

Add `reports/` to the instance role's delete deny in `AWSPolicy.Inline.S3AccessWildcard`.  No `PutObject` deny.  The bucket policy is unchanged, as for `grafana/`.

### 19. Verification (decision 19)

No separate spike.  The PromQL compatibility test covers the negative offset.  A live cluster test covers the rest (see Verification items).

### 20. Debt (decision 20)

Fold in D1-D8.  D8 splits `GrafanaDashboardService` into a Grafana HTTP client (dashboards, folders, annotations; it implements `GrafanaAnnotationSource`) and a deploy service (datasource ConfigMap, dashboard tree upload, K8s apply, `ConfigHashAnnotator`).  The split rewires the `down` annotation mirror and backup path.

### F10. One install-time pass everywhere

`DashboardDefaults` is a pure transform on a kotlinx `JsonObject`.  It runs in the core tree writer, in kit installs, and in `grafana install` with the workspace cluster state.  So the `dashboard-editor` agent reads back what `up` installs.

### Folded in without a question

- Fixed ports in `Constants` and `docs/reference/ports.md`; 8081 is the image renderer's.
- The Tests `cluster` variable is single-select.
- Docs note that two concurrent uploads can drop a document from `index.html` until the next upload.
- A markdown library is a new dependency.
- The web server config joins the `ConfigHashAnnotator` map.

## Risks / Trade-offs

- **Blast radius.**  All 43 dashboard files, the Grafana Deployment (two sidecars, one roll), two install paths plus `grafana install`, the cluster role policy, and the `down` annotation path through the D8 split.  No backend changes.
- **Query cost.**  The `up` subqueries scan up to 180 days, and each comparison load runs several helper variables.  They filter to one cluster.  `lookback` and `resolution` bound the cost.
- **Absolute ranges.**  Panel `timeFrom` is ignored when the dashboard range is absolute.  An operator who sets an absolute range breaks the overlay and side-by-side views.  The Compare link carries no absolute range, and the panel descriptions say so.
- **Window is the cluster life.**  Idle and setup time are part of each run's figures, and the overlay aligns cluster starts, not load starts (critique 1, finding 10; accepted by decision 4).
- **Existing build and host filters.**  They list over the dashboard range, so they may not see an older run.  They apply only to the existing panels (decision 7).
- **Concurrent uploads.**  Two `report upload` runs for one test can drop a document from `index.html` until the next upload.
- **Proxy region.**  The wrong region gives `AuthorizationHeaderMalformed`.  The helper returns the bucket's region.
- **Picker refs must keep `type`.**  `DashboardQueries` and annotation rendering read it.
- **Past clusters without an index.**  They show the S3 error in the iframe.  Accepted (F7).

## Alternatives Considered

- **Decision A, picker names.**  A1 (architect's recommendation): keep `datasource` and add `logs_datasource` and `traces_datasource`.  A2: uniform `metrics_datasource`, `logs_datasource`, `traces_datasource`.  **The owner overrode the architect and chose A2** for one vocabulary.
- **Decision B, picker defaults.**  B1 (recommended, chosen): empty `current` in the files, set at install.  B2: keep `current` in the files and exempt it in the guard; rejected because it breaks "anywhere".  Relying on Grafana's fallback: rejected, it depends on datasource naming.
- **Decision C, run defaults.**  Architect: default `candidate_cluster` to the current cluster, no default for `baseline_cluster` (chosen in decision 3).  Alternative: default only `cluster`.  **F8 replaced the baseline part:** both run variables default to the current cluster, because a query variable with no "All" falls back to the first option.
- **Decision D, window source.**  D1 (recommended, chosen): Mimir subquery over `up`.  D2: `ts_of_first_over_time`/`ts_of_last_over_time`; rejected, experimental in Prometheus 3.x and unconfirmed in Mimir 3.2.1.  D3: the Loki annotation mirror; rejected, no automatic up/down annotations exist.  D4: a window record written by `up`/`down`; rejected, needs a new writer and covers only future clusters.
- **Decision E, row link targets.**  Architect: System Overview, Cassandra Overview and Cluster Comparison.  Alternative: a `target` variable with one link.  The owner chose the three plus "Show documents" (decision 5).
- **Decision F, rename `cluster_a`/`cluster_b`.**  Rename (recommended, chosen) or keep the old names.
- **Decision G, figures.**  Curated set in three top rows (recommended, chosen) or all three views for every existing panel; rejected, it doubles files of 115-200 KB.
- **Decision H, command name.**  `report upload` (recommended, chosen), `document upload`, or `grafana document`; the `grafana` group is about the running Grafana, and the command must work after `down`.
- **Decision I, file names.**  `[A-Za-z0-9._-]+` (recommended, chosen) or any name with percent-encoding; rejected, riskier through a signing proxy.
- **Decision J, S3 auth.**  J1 (recommended): `aws-sigv4-proxy` sidecar.  J2: Infinity's own AWS auth; rejected, 4.0.0 supports only static keys, and the unreleased main build with instance-role auth was rejected.  J3: bucket policy allowing reads from the control node's IP; rejected, a public-read policy rewritten on every `up`.  **The owner kept J1** after dropping Infinity; it now serves the web server.
- **Decision K, document path lookup.**  K1 (recommended): list `reports/` and filter by cluster; superseded, XML listings in Infinity get one page of 1000 keys (critique 1, finding 5).  K2: a `doc_tenant` variable from `reports/` CommonPrefixes (decision 16).  **17b replaced it** with a custom variable filled from `TenantDirectory` at install.
- **Decision L, markdown rendering.**  L1 (architect's recommendation, pending a spike): Infinity fetches the file into one Table cell with the markdown cell type.  L2: a JSON envelope per document read by Infinity.  L3: a Text panel with the body in a variable.  **The owner overrode the architect and chose d2:** `report upload` converts markdown to HTML and rewrites one `index.html`, shown in an iframe through a read-only web server, with no Infinity and no document picker.  Infinity 4.0.0 has no raw-text mode and no instance-role auth.
- **Overlay axis.**  Architect: axis on the later-starting run with positive offsets, plus an "Align runs" dashboard link that sets an absolute range.  Decision 8: axis on the earlier-starting run with a negative offset, plus "Align runs".  **F1 replaced decision 8:** no "Align runs"; the range stays relative and ends at now; the overlay uses panel `timeFrom` and a per-run offset; side by side uses `timeFrom` and a backward `timeShift`.  Reason: after "Align runs" the helper variables and pickers, evaluated at the range end, could not see a run that starts after it (critique 2, blocker 1), the later-run axis ran into the future past Mimir's `max_query_into_future` (critique 1, finding 3), and `timeShift` cannot move forward (critique 2, finding 4).
- **Pickers over the dashboard range.**  Rejected by decision 6: an older test would not be listed at `now-1h` (critique 1, finding 1).
- **Two lookbacks** (Tests `now-90d`, pickers `180d`).  **F9 chose one `lookback`, default 180d** (critique 2, finding 13).
- **Link rule.**  Decision 9 required `${name:queryparam}` for pickers and `cluster` on every `/d/` link.  **F2** also accepts an explicit `var-<name>=`, because the Tests row links must set the row's cluster (critique 2, blocker 2).  **F4** limits the rule to the variables the source dashboard declares and adds the run variables and `doc_tenant` (critique 2, finding 3).
- **Current-cluster criterion on dashboards with no `cluster` variable.**  **F3:** the criterion covers only dashboards with a `cluster` variable; the owner filed #983 for the other 17.
- **Empty test.**  The alternative was to let the web server return S3's error page.  **F7:** `up` writes an empty index ("No documents yet").
- **`PutObject` deny under `reports/`** (architect's optional item).  Rejected by decision 18.
- **Time-boxed spike before the dashboard edit** (architect's recommendation).  Rejected by decision 19: the PromQL compatibility test and the live cluster test cover the unknowns.
- **D8 `GrafanaDashboardService` split.**  The architect recommended a separate issue.  **The owner folded it in** (decision 20).
- **Infinity version pinning.**  Moot; no Infinity.
- **Any file type in documents** (critique 2, finding 6).  **F6:** only `.md`; `index.md` is rejected, so no uploaded name collides with the index or with a generated `.html`.

## Domain Facts

From the research agent (`.spec-flow/research.md`, Grafana `v13.2.2`, scenes, Infinity `v4.0.0`, Prometheus and Mimir docs).  The design depends on these:

- **Datasource variables.**  Options come from the datasource list filtered by `pluginId`.  Each option is `{label: name, value: uid}`, so `${ds}` is the uid.  The regex matches the name, unanchored.  A saved or URL value is matched by uid, then by name; with no match the variable falls back to the first option.  `{"type": "<pluginId>", "uid": "${ds}"}` works in panel targets, annotation queries and query-variable datasources.
- **Panel `timeFrom` and `timeShift`** go through variable interpolation and re-evaluate when a variable changes.  **`timeFrom` is ignored when the dashboard range is absolute.**  `timeShift` works with absolute ranges but is always prefixed with `-`, so it **only moves backward**.
- **PromQL offset.**  Grafana interpolates variables before it sends the query, so `offset ${shift}s` works.  **Negative offsets and `@` are stable, on by default, since Prometheus 2.33**; Mimir removed the `promql-negative-offset` flag as stable.
- **Infinity 4.0.0** supports only static keys (`authType: "keys"`) for AWS; the default credential chain is merged on main but unreleased.  It has **no raw-text mode**; `as-is` works only for JSON.  XML listings get one page.
- **Table markdown cell** ("Markdown + HTML", `cellOptions.type: "markdown"`) renders GitHub-flavored markdown, sanitized unless `[security] disable_sanitize_html = true`.
- **Data links.**  `${__data.fields.x}` gives a column of the same row; `${var:queryparam}` gives `var-var=value`; `?` and `&` are not added automatically.

## Verification items

The four live-test unknowns from decision 19:

1. **Negative offset on the pinned Mimir.**  Covered by `PromQlCompatibilityIntegrationTest` with sample values for the new forms (negative `offset`, `@`, subquery).
2. **Variable interpolation in panel `timeShift` and `timeFrom`** on Grafana 13.2.2, checked on a live cluster.
3. **The iframe under `disable_sanitize_html`** in a Text panel in HTML mode, checked on a live cluster.
4. **Browser reachability of the web server and bucket-region signing by the proxy**, checked on a live cluster, including a bucket in a region other than the cluster's.

## Changes after live QA (2026-09-29, owner-approved)

Live QA on cluster qa971 found five defects in the forms above.  The code now differs from this document as follows; where the two disagree, this section wins.

- **Panel `timeFrom` and `timeShift` use days plus seconds.**  Grafana 13.2.2's date math accepts at most five digits per number (`datemath.ts`, `MAX_MATH_TOKEN_DIGITS = 5`), so `${x}s` failed past 99,999 seconds (27.8 hours).  Each value used in a panel time override is split into two hidden helpers, `x_d = floor(x / 86400)` and `x_s = x % 86400`, and the field is `${x_d}d-${x_s}s`.  PromQL `offset` and range values stay in plain seconds.  `PanelTimeOverrideTest` guards it.
- **The seconds come first, and `x_s` reads `x_d`.**  On first load the overlay and side-by-side panels queried a one-second window until a manual refresh.  Grafana 13.2.2's `PanelTimeRange` recomputes the override when a helper completes, but keeps the new range only when its header text changes, and a `timeFrom` header names only the first number: `0d-s` and `0d-9000s` both read "Last 0 day".  The field is now `${x_s}s-${x_d}d`, and `x_s = x - 86400 * x_d`, so the seconds helper always completes last and its completion changes the header.  This replaces the `${x_d}d-${x_s}s` form in the item above and in tasks 7.3 and 7.4.
- **The summary is one query per panel, pivoted.**  Grafana names value fields `Value #A`, `Value #B` when a panel has several queries, so `groupingToMatrix` never formed.  Each summary panel is one instant query that joins the figures with `or`, then `groupingToMatrix` and `organize` into Figure, Baseline, Candidate and Difference %.
- **The sanitize setting is in `[panels]`.**  Grafana reads `disable_sanitize_html` from `[panels]` (env `GF_PANELS_DISABLE_SANITIZE_HTML`), not `[security]`.  The Text panel sanitizer keeps only `src`, `width` and `height` on an iframe, so every documents iframe is sized with `width` and `height` attributes, not `style`.
- **Mimir's per-tenant query queue is 5000.**  The default of 100 rejected full dashboard loads with 429.  `query_scheduler.max_outstanding_requests_per_tenant` holds two full loads of the heaviest dashboard; parallelism and sharding are unchanged.
- **Also fixed in this change:** every `cluster` variable except the Tests one is multi-select with All on `label_values(up, cluster)`, and trino matches it with `=~`; `clickhouse-overview` rows have distinct positions (`DashboardRowOrderTest`); the postgres extension dashboards link to the uids their instances install, with the time range kept (`DashboardLinkTimeTest`).
