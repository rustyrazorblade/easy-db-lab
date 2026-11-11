| Source | Requirement | Covering scenario(s) | Status |
|--------|-------------|----------------------|--------|
| AC #967 | `down` completes → no lifecycle/expiry rule on `clusters/<name>-<id>/`, `observability/` (and `observabilitymetrics/`), or the data bucket | `cluster-lifecycle: Teardown sets no expiry rule` | ✅ Covered |
| AC #967 | `down --retention-days 1` → unknown option | `cluster-lifecycle: The retention option no longer exists` | ✅ Covered |
| AC #967 | Tempo config turns off compaction and retention; no block deleted | `observability-store: Tempo keeps every block` | ✅ Covered |
| AC #967 | Pyroscope uses v2 storage with retention cleanup off; no profile deleted by age | `observability-store: Pyroscope keeps every profile` | ✅ Covered |
| AC #967 | Pyroscope unreachable/rejecting until size bound → shipper stops recording and reports; no unshipped or rejected chunk deleted | `profiling: Unshipped chunks are never pruned`, `profiling: Rejected chunks are never pruned`, `profiling: Recording stops at the size bound instead of deleting data`, `profiling: Recording resumes once space is available` | ✅ Covered |
| AC #967 | `init --tenant acme` → state holds `acme`; every sender and datasource uses `X-Scope-OrgID: acme` | `observability-store: Tenant chosen at init`, `observability-store: Every writer carries the tenant`, `observability-store: Datasources query the cluster's tenant`, `profiling: A shipped chunk carries the tenant` | ✅ Covered |
| AC #967 | `init` without `--tenant` → tenant `default` | `observability-store: Default tenant`, `observability-store: Default tenant location` | ✅ Covered |
| AC #967 | Invalid tenant name → `init` fails naming the rule | `observability-store: Invalid tenant name is refused`, `observability-store: Reserved tenant name is refused` | ✅ Covered |
| AC #967 (rewritten by the #964 fold-in and D4) | Was: `down`/`metrics backup` snapshot under `observability/metrics/<tenant>/`.  Now: Mimir blocks land under `observabilitymetrics/<tenant>/`, and no metrics snapshot is taken | `observability-store: Metric blocks location`, `observability-store: No snapshot backups are written` | ✅ Covered |
| AC #967 (rewritten by the #965 fold-in) | Was: `logs backup` snapshot under `observability/logs/<tenant>/`.  Now: Loki chunks land under `observability/logs/<tenant>/` and index files under `observability/logs/index/` | `observability-store: Log chunks and index location` | ✅ Covered |
| AC #967 | `down` annotations → `observability/annotations/<tenant>/` | `grafana-annotations: Backup uploads annotations and reports the URI` | ✅ Covered |
| AC #967 | Tempo block → `observability/traces/<tenant>/` | `observability-store: Trace blocks location` | ✅ Covered |
| AC #967 | Pyroscope profiles → `observability/profiles/`; nothing in the data bucket | `observability-store: Profiles location` | ✅ Covered |
| AC #967 | Traces > 5m → blocks exist while the cluster is up | `observability-store: Trace blocks exist while the cluster is up` | ✅ Covered |
| AC #967 | Tempo restart with unflushed traces → traces still reach S3 | `observability-store: Restart loses no acknowledged data` | ✅ Covered |
| AC #967 | Cause of missing Tempo blocks recorded; test covers the fix | `observability-store: Cause of the missing Tempo blocks is recorded` (owner decision: branch build only; see issue 967) | ✅ Covered |
| AC #967 | `grafana update-config` with only dashboards → Tempo and Pyroscope not restarted | `observability-store: Dashboard change restarts nothing else`, `observability-store: Config change restarts that workload` | ✅ Covered |
| AC #967 | Two clusters in one tenant → neither overwrites the other | `observability-store: Two clusters write every signal`, `observability-store: Loki index files name their cluster` | ✅ Covered |
| AC #967 (rewritten by the fold-in) | Was: `metrics ls`/`logs ls` list every snapshot in the tenant.  Now: those commands are removed | `observability-store: Removed commands are unknown` | ✅ Covered |
| AC #967 | Every image runs the listed version; none uses `latest` | `observability-store: Stack runs the pinned versions`, `observability: Grafana Dashboards` (renderer scenario) | ✅ Covered |
| AC #964 | Cluster starts → Mimir runs; no VictoriaMetrics resource | `observability-store: No Victoria workload runs` | ✅ Covered |
| AC #964 (path rewritten by D4) | Runs longer than one block period → Mimir blocks exist under `observabilitymetrics/<tenant>/` while up | `observability-store: Metric blocks exist while the cluster is up` | ✅ Covered |
| AC #964 | No Mimir compactor runs | `observability-store: Mimir has no deletion path` | ✅ Covered |
| AC #964 | Collector exports metrics with `X-Scope-OrgID`; Mimir stores them under the tenant; every series has `cluster=<name>-<id>` | `observability-store: Every writer carries the tenant`, `observability-store: Metrics carry the cluster` | ✅ Covered |
| AC #964 (rewritten by D1) | Was: two clusters of different tenants → `a|b` returns both clusters' series.  Now: within one Mimir, `a|b` returns both tenants' series with `__tenant_id__` | `observability-store: Metrics are queried across tenants within one Mimir` | ✅ Covered |
| AC #964 | Cross-cluster `a|b` metrics query (two clusters' Mimirs) | — | ⚠️ Excluded — owner decision D1: a running cluster reads only its own metrics; reading the shared metrics store needs one index writer in #966 or the #902 stack |
| AC #964 | One cluster's flushed block is returned by another running cluster's queriers | — | ⚠️ Excluded — owner decision D1 (as above); moved to #966/#902 |
| AC #964 (rewritten by D1) | Was: `query_store_after` low enough for other clusters' blocks.  Now: the store is never queried in-cluster | `observability-store: No block store is queried`, `observability-store: Local blocks stay queryable` | ✅ Covered |
| AC #964 | Tempo metrics generator remote-writes to Mimir with the tenant | `observability-store: Tempo's metrics generator writes to Mimir` | ✅ Covered |
| AC #964 | Core and kit dashboards' PromQL panels show Mimir data with no query change | `observability-store: Metric panels show Mimir data` | ✅ Covered |
| AC #964 | MCP server and `status` report metrics and endpoints from Mimir | `live-stream-metrics: Instant query returns metric values`, `live-stream-metrics: Only the current cluster is reported`, `server: Metrics collected when Redis configured`, `server: Observability object includes every backend` | ✅ Covered |
| AC #964 | `metrics backup/import/ls` → unknown command | `observability-store: Removed commands are unknown` | ✅ Covered |
| AC #964 | `down` takes no VictoriaMetrics snapshot | `observability-store: No snapshot backups are written`, `cluster-lifecycle: The tail of every signal reaches S3 before teardown` | ✅ Covered |
| AC #964 | No VictoriaMetrics references outside the telemetry redirect | `observability-store: No Victoria references remain` | ✅ Covered |
| AC #965 | Cluster starts → Loki runs; no VictoriaLogs resource | `observability-store: No Victoria workload runs` | ✅ Covered |
| AC #965 (rewritten by source fact) | Was: chunks and index "for the cluster's tenant" under `observability/logs/`.  Loki index files are multitenant per table until a compactor splits them; now: chunks under `observability/logs/<tenant>/`, index files under `observability/logs/index/` named with tenant and cluster | `observability-store: Log chunks exist while the cluster is up`, `observability-store: Log chunks and index location`, `observability-store: Loki index files name their cluster` | ✅ Covered |
| AC #965 (rewritten by D5) | Was: no Loki compactor runs.  Now: no compaction and no deletion runs (the compactor module is loaded but idle) | `observability-store: Loki never compacts or deletes` | ✅ Covered |
| AC #965 | Collector exports logs with `X-Scope-OrgID`; Loki stores them under the tenant | `observability-store: Every writer carries the tenant` | ✅ Covered |
| AC #965 | Collector and Fluent Bit lines carry `cluster=<name>-<id>` | `observability-store: Logs carry the cluster` | ✅ Covered |
| AC #965 | Two clusters of different tenants → `a|b` returns both clusters' lines | `observability-store: Logs are queried across tenants and clusters` | ✅ Covered |
| AC #965 (rewritten by D6) | Was: an annotation created by the operator or the tool is stored in Loki.  Now: tool annotations at once; every annotation inside Loki's accepted window, including UI ones, at `grafana backup` and before teardown | `grafana-annotations: A tool annotation reaches Loki at once`, `grafana-annotations: A UI annotation reaches Loki before teardown`, `grafana-annotations: Mirroring is idempotent`, `grafana-annotations: A backdated annotation reaches Loki` | ✅ Covered |
| Owner (2026-09-26) | Only annotations inside Loki's accepted window (`MAX_ENTRY_AGE_HOURS` back, `MAX_ENTRY_AHEAD_HOURS` ahead) are mirrored; one outside it is reported with `AnnotationsOutsideLokiWindow` and kept in Grafana and the S3 JSON backup | `grafana-annotations: An annotation outside Loki's window is reported and kept` | ✅ Covered |
| AC #965 (rewritten by D6) | Was: dashboards show annotations from the Loki query.  Now: global annotations from Loki; the current cluster's dashboard-scoped ones from Grafana; each once | `grafana-annotations: A global annotation appears on a core dashboard`, `grafana-annotations: An annotation renders once` | ✅ Covered |
| AC #965 | Dashboard-scoped annotations of other or past clusters are displayed | — | ⚠️ Excluded — owner decision D6: they are stored in Loki but not displayed |
| AC #965 | LogsQL panels show the same logs from Loki with LogQL | `observability-store: Log panels show Loki data`, `observability-store: Log queries work on the pinned Loki` | ✅ Covered |
| AC #965 | `logs query` options return matching lines from Loki | `observability-store: logs query options work on Loki` | ✅ Covered |
| AC #965 | `-q` is LogQL | `observability-store: A raw query is LogQL` | ✅ Covered |
| AC #965 | `spark logs` and the EMR step lookup read Loki | `observability-store: Spark and EMR step logs come from Loki`, `spark-emr: Spark logs available via OTel and S3` | ✅ Covered |
| AC #965 | `logs backup/import/ls` → unknown command | `observability-store: Removed commands are unknown` | ✅ Covered |
| AC #965 | No VictoriaLogs references outside the telemetry redirect | `observability-store: No Victoria references remain` | ✅ Covered |
| Scope #967 | Redirected clusters send the tenant; Trino/Presto follow the redirect | `observability-store: Redirected clusters send the tenant too`, `telemetry-redirect: All profile producers ship externally`, `telemetry-redirect: Redirected writes carry the tenant` | ✅ Covered |
| Scope | Tenant read from `cluster-config` (D8) | `observability-store: One source for the tenant` | ✅ Covered |
| Scope | Cluster names validated (fold-in) | `observability-store: Invalid cluster name is refused at init`, `observability-store: Invalid cluster name in state is refused at load` | ✅ Covered |
| Scope | Collector and backend self-telemetry scraped | `observability-store: Collector export failures are queryable`, `observability-store: Backend failures are queryable` | ✅ Covered |
| Scope | Collector `error_mode: propagate`; per-core CPU label | `observability-store: Collector errors are not hidden`, `observability-store: Per-core CPU stays available` | ✅ Covered |
| Scope | Log line ↔ trace links | `observability-store: A log line links to its trace`, `observability-store: A trace links to its logs` | ✅ Covered |
| Risk (D2) | Teardown loses the last 1–3h of metrics and up to 2h of logs without a flush | `cluster-lifecycle: The tail of every signal reaches S3 before teardown` | ✅ Covered |
| Risk (critic) | Loki flush leaves chunks with no index | `cluster-lifecycle: The tail of every signal reaches S3 before teardown` (index files verified in S3) | ✅ Covered |
| Risk (critic) | Mimir flush reports success even when the head did not become blocks or shipping failed | `cluster-lifecycle: The tail of every signal reaches S3 before teardown`, `cluster-lifecycle: A failed flush step stops down` | ✅ Covered |
| Risk (critic) | Flush blocks forever on persistent S3 failure; partial abort leaves a backend down | `cluster-lifecycle: A flush that cannot reach S3 times out and stops down`, `cluster-lifecycle: A failed flush step stops down` (owner override 2026-09-26: a stopped backend stays stopped and the report names its state and `--force`) | ✅ Covered |
| Owner (2026-09-26) | `down` never starts a backend again; a failed teardown restores nothing; a re-run after a successful flush works with the backends at 0; a re-run after a failed flush with a stopped backend points at `--force` | `cluster-lifecycle: A failed teardown after a successful flush restores nothing`, `cluster-lifecycle: A re-run after a successful flush skips the flush`, `cluster-lifecycle: A re-run after a failed flush with a stopped backend stops`, `cluster-lifecycle: Declining the confirmation stops no backend` | ✅ Covered |
| Risk (critic) | Mimir compactor fails open and can delete; `delete_tenant` endpoint | `observability-store: Mimir has no deletion path` | ✅ Covered |
| Risk (D3) | A backend config mistake could delete data | `observability-store: A backend delete is denied`, `observability-store: The deny is re-applied on up` | ✅ Covered |
| Risk (D3) | Pyroscope v2 compaction needs deletes | `observability-store: Pyroscope compaction still works` | ✅ Covered |
| Risk (critic) | Mimir shipper cleanup after a failed upload hits the deny | — | ⚠️ Excluded — harmless: the next sync overwrites the block; noted in design |
| Risk (critic) | Tenant `index` collides with Loki's index path | `observability-store: Reserved tenant name is refused` | ✅ Covered |
| Risk (critic) | Cluster name breaks YAML, paths or Loki file names | `observability-store: Invalid cluster name is refused at init` | ✅ Covered |
| Risk (critic) | Mimir startup exceeds the readiness gate as the store grows | — | ⚠️ Excluded — not applicable under D1: no store-gateway or cleaner; startup is bounded by one cluster's data |
| Risk (critic) | Bucket-index race between clusters | — | ⚠️ Excluded — not applicable under D1: no bucket index is written in-cluster |
| Risk (D1) | No running cluster reads other or past clusters' metrics | — | ⚠️ Excluded — accepted by the owner; #966/#902 |
| Risk | Store growth without compaction (Tempo, Loki) | — | ⚠️ Excluded — inherent to the no-compaction design; offline compaction is #966 |
| Risk | Loki `object_prefix` is experimental; `schema_config` is a permanent contract | — | ⚠️ Excluded — a design constraint recorded in design.md, not observable behavior |
| Risk | Pyroscope v2 index lost at teardown | — | ⚠️ Excluded — accepted by the owner; #966 saves the index |
| Risk | Pyroscope v2 index lost on pod restart | `observability-store: Profiles stay queryable after a restart` | ✅ Covered |
| Risk | Tempo 3 retention runs outside the compactor; `block_retention: 0` deletes everything | `observability-store: Tempo keeps every block` | ✅ Covered |
| Risk | JFR recording pauses at the size bound | `profiling: Recording stops at the size bound instead of deleting data` | ✅ Covered |
| Risk | Account bucket in a different region from the cluster | — | ⚠️ Excluded — both come from the profile region in the normal flow; not changed by this change |
| Risk | Pyroscope 2 embedded UI tenant selection | — | ⚠️ Excluded — verified on a real cluster and documented; not a behavior to specify |
