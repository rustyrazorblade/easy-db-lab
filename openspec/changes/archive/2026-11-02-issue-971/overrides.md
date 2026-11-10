## Overrides existing behavior

### multi-cluster-dashboards: All dashboards have a cluster multi-select variable (MODIFIED)
**Currently** (`openspec/specs/multi-cluster-dashboards/spec.md`): "Every Grafana dashboard SHALL include a `cluster` template variable (lowercase) that queries all available cluster values from Mimir. The variable SHALL support multi-select and SHALL include an "All" option that defaults to all clusters."  Scenario "Cluster variable present on all dashboards": "the variable SHALL be populated by querying `label_values(up, cluster)` against the Mimir datasource".
**This change:** the variable queries `${metrics_datasource}`, never a fixed datasource.  The file stores no default; the installer defaults it to the current cluster, and "All" is not the default.  The Tests dashboard's `cluster` is single-select.  #983 is folded in (2026-09-29): every dashboard that queries metrics or logs gets the variable, and every query filters by it.  Adds the scenario "All tenants and All clusters show every cluster".  This also fixes the stale text (D7): "All" as the default and the fixed Mimir datasource.

### multi-cluster-dashboards: Metric dashboards include an ad hoc filters variable (MODIFIED)
**Currently:** "All Mimir-backed dashboards SHALL include a Grafana `adhocfilters` variable pointing to the Mimir datasource."
**This change:** the `adhocfilters` variable's datasource is `${metrics_datasource}`.  Adds the scenario "Ad hoc filters follow the metrics picker".

### cluster-comparison-dashboard: Dashboard uses shared cluster variable pattern (MODIFIED)
**Currently** (`openspec/specs/cluster-comparison-dashboard/spec.md`): "It SHALL also include a `cluster2` single-select variable for the side-by-side heatmap comparison panels."  (The file actually has `cluster_a` and `cluster_b`.)
**This change:** `baseline_cluster` and `candidate_cluster` replace `cluster_a` and `cluster_b` and serve the heatmaps and the run comparison views.  The `cluster` scenario excludes the run views.  Adds the scenario "The run variables replace cluster_a and cluster_b".  This fixes the stale `cluster2` (D7).

### observability-store: The cluster cannot delete observability objects (MODIFIED)
**Currently:** issue-970's delta is the effective text: "The EC2 instance role of every cluster SHALL carry an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` under `mimir/`, `loki/`, `tempo/` and `grafana/` in the account bucket.  The account bucket policy SHALL carry one Deny ... under `mimir/`, `loki/` and `tempo/` ...  Deletes made by the owner from a workstation SHALL NOT be affected."  (The main spec still names the pre-966 prefixes.)
**This change:** the instance role deny also covers `reports/`.  The bucket policy is unchanged.  Adds the scenario "A document delete is denied".  Every other rule of issue-970's text is unchanged.

### observability-store: Observability images are pinned to released versions (MODIFIED)
**Currently:** "Every image the observability stack runs SHALL be pinned to a released version. ... The versions SHALL be: Mimir 3.2.1, Loki 3.7.8, Pyroscope 2.3.1, Tempo 3.0.3, Grafana 13.2.2, Grafana image renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0, Fluent Bit 5.1.2, and OTel collector contrib 0.161.0 ..."
**This change:** the `aws-sigv4-proxy` and documents web server images are also pinned to released versions.  Adds the scenarios "Documents sidecars are pinned" and "Comparison queries work on the pinned Mimir" (negative `offset`, `@`, subquery).

### observability: Grafana Dashboards (MODIFIED)
**Currently** (`openspec/specs/observability/spec.md`): "every occurrence of `__PYROSCOPE_URL__` in any dashboard SHALL be replaced with the cluster's Pyroscope URL; no other substitution SHALL be made".  "All dashboards SHALL include a `cluster` multi-select variable".  Scenario "All dashboards have a cluster variable": "the variable SHALL query `label_values(up, cluster)` against the Mimir datasource (uid `mimir`)".  The pod holds only the image renderer sidecar.
**This change:** the install-time pass is the one other allowed change.  The pod also holds the `aws-sigv4-proxy` and documents web server sidecars, and Grafana sets `disable_sanitize_html`.  The `cluster` scenario becomes "Cluster variables read the metrics picker" (`${metrics_datasource}`), applies to dashboards that have a `cluster` variable, and exempts the single-select Tests dashboard.  Adds the scenario "Documents sidecars run alongside Grafana".

### grafana-install-dashboard: grafana install command uploads a dashboard JSON (MODIFIED)
**Currently** (`openspec/specs/grafana-install-dashboard/spec.md`): "The `grafana install <path>` command SHALL read a dashboard JSON file and upload it to the running Grafana instance via `POST /api/dashboards/db` with `overwrite: true`."
**This change:** `grafana install` applies the same install-time pass as `up` and kit `start`, from the workspace cluster state (F10).  Adds the scenario "Install applies the install-time pass".

### tool-execution: Foreground command execution with logging (MODIFIED)
**Currently** (`openspec/specs/tool-execution/spec.md`): "The `exec run` command SHALL execute commands on remote hosts via `systemd-run --wait`, routing stdout and stderr to the systemd journal. After the command completes, its output SHALL be displayed to the user."
**This change:** if the unit fails, `exec run` still prints the unit's journal for that host, and then reports the failure (owner decision 2026-09-29).  Adds the scenario "A failed foreground run prints the unit's journal".  The two existing scenarios are unchanged.

### tool-execution: Unit naming (MODIFIED)
**Currently:** "Background and foreground commands SHALL be run as systemd transient units with predictable names following the pattern `edl-exec-<name>`."
**This change:** every character that systemd does not allow in a unit name becomes `-`, and `exec run` and `exec stop` build the name the same way (owner decision 2026-09-29).  Adds the scenarios "A name with characters systemd does not allow is sanitized" and "exec stop sanitizes the name the same way".  The two existing scenarios are unchanged.

## Conflicts with other in-flight changes

**issue-966** (merged, not archived) modifies `observability-store` ("Observability data lands in one layout in the account bucket", "The cluster cannot delete observability objects", "Data reaches S3 while the cluster is up and survives a restart"), `cluster-lifecycle`, `grafana-annotations` and `cloudwatch-metrics-export`.

**issue-970** (merged, not archived) adds `account-compactor`, modifies `cluster-lifecycle`, and in `observability-store` adds two requirements, modifies "No observability backend deletes data automatically" and "The cluster cannot delete observability objects", and removes "Mimir reads only the cluster's own data".

- **Actual conflict: "The cluster cannot delete observability objects".**  All three changes modify it.  This change's MODIFIED block is written from issue-970's text and adds only `reports/`.  Archive issue-966, then issue-970, then this change.  Archiving this change earlier would let an older text overwrite the `reports/` deny.
- **No actual conflict: "Observability images are pinned to released versions".**  Neither issue-966 nor issue-970 touches it.
- **No actual conflict: "Grafana has a datasource per tenant and one for all tenants"** (issue-970).  This change uses those datasources and does not modify the requirement.
- **No actual conflict: `grafana-annotations`** (issue-966).  This change moves the annotation query onto `${logs_datasource}` and rewires the annotation client in the D8 split, but the behavior that requirement states does not change, so it has no delta here.
- **No actual conflict** on `multi-cluster-dashboards`, `cluster-comparison-dashboard`, `observability`, `grafana-install-dashboard`, `tests-dashboard` or `test-documents`: neither in-flight change touches them.
- **No actual conflict: `cloudwatch-metrics-export`** (issue-966 modifies "CloudWatch metrics scraped into Mimir").  This change only adds two requirements, "Each cluster's YACE discovers only its own resources" and "S3 panels count the shared account bucket once", and does not modify that requirement.
- **No actual conflict** on `tool-execution`, `server` or `spark-emr`: neither in-flight change touches them.
