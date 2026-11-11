## Context

Observability data before this change:

- VictoriaMetrics and VictoriaLogs kept metrics and logs on the control node's disk.  They survived a cluster only as snapshots that `down` and `metrics backup` / `logs backup` copied to `clusters/<name>-<id>/victoriametrics/` and `victorialogs/`, and `down` set a 1-day expiry on that prefix (`--retention-days`).
- Grafana annotations went to an account-level Grafana backup prefix keyed by cluster name.
- Tempo wrote to `clusters/<name>-<id>/tempo/` with `block_retention: 48h`.  Its WAL was an `emptyDir`.
- Pyroscope 1.18 wrote to the per-cluster data bucket, which `down` set to expire.
- The Cassandra JFR shipper pruned chunks by age and size, including chunks that never shipped.
- `ObservabilityStackService.deploy()` restarted every observability workload on `up` and on every `grafana update-config`.

No lifecycle rule existed on the owner's account bucket or on any of its 21 data buckets (checked 2026-09-24), probably because the IAM policy named the lifecycle actions wrongly and `Down.kt` swallowed the failure.  A day-long cluster from 2026-09-06 left only `tempo_cluster_seed.json` under `tempo/`.

Epic #963 builds a shared store where many clusters' data sits side by side, per tenant.  The owner folded #964 (Mimir) and #965 (Loki) into this change on 2026-09-26; #966 (tenant views, compaction, export) stays separate.

Source facts that shaped the Mimir and Loki design (pinned `mimir-3.2.1`, `loki v3.7.8`):

- Mimir reads the S3 block store only through a bucket index, and only the compactor's `BlocksCleaner` writes it (`pkg/compactor/blocks_cleaner.go:486-514`; no flag disables the index, `pkg/storage/tsdb/config.go:541-551`).  Queriers reject an index older than `max_stale_period` (1h).
- Mimir's `storage_prefix` must match `^[\da-zA-Z]+$` (`pkg/storage/bucket/client.go:51,153-156`).
- A Loki target list that names `ingester` makes the index shipper write-only (`pkg/loki/modules.go:1024-1043`); only `target: all`, which includes the compactor, is read-write in one process.
- Loki's ingester writes one multitenant index file per table; only the compactor splits them by tenant (`…/indexshipper/tsdb/manager.go:144,239`).
- Grafana Alloy's `pyroscope.write` has no `tenant_id` setting in v1.19.2 or v1.20.0; the `headers` map is the only way to send a tenant.

## Goals / Non-Goals

**Goals:**
- No code path deletes observability data or profile chunks automatically, and the cluster's IAM role cannot delete it.
- One tenant (the customer) per cluster, carried natively by every backend, writer and reader.
- Every signal's backend writes straight to one layout in the account bucket.
- Data reaches S3 while the cluster is up, survives a pod restart, and the tail is saved before teardown.
- Every observability image runs its latest release, pinned.

**Non-Goals:**
- Moving or deleting past data.
- Removing lifecycle rules that earlier `down` runs set (none exist in the owner's account).
- Reading metrics across clusters or from past clusters inside a running cluster (#966 or #902).
- Grafana tenant views, the tenant picker, offline compaction, `down --compact`, export and the multi-DC live view (#966, #902).
- Removing the data bucket, or S3 request metrics on the account bucket.
- A move of the profiler to async-profiler's OTLP output.
- Splitting `Down.kt`.
- Clusters that use the telemetry redirect, beyond keeping them working.

## Decisions

### 1. Native multi-tenancy, not a tenant path prefix (owner override)
Mimir, Loki, Tempo and Pyroscope run with multi-tenancy on.  The easy-db-lab tenant is the backend tenant ID, sent as `X-Scope-OrgID`.  Each backend lays out its own tenant directories under a fixed root.  Why: this is how the backends are designed; one backend instance can serve every tenant in a bucket, and Tempo, Mimir and Loki can query several tenants with `a|b`.

### 2. The tenant and the cluster name
`init --tenant <name>`, default `default`, stored in `InitConfig.tenant`.  An older state file without the field reads as `default`.  The rule `^[a-z][a-z0-9_-]{0,62}$` keeps the name valid as a directory in every backend; `index` is reserved because a Loki tenant with that name would collide with Loki's index path.  The cluster name must match `^[a-z][a-z0-9-]{0,39}$` at `init` and at state load, because it flows raw into configuration files, Loki file names, S3 keys and labels.

### 3. Tenant source (D8)
The tenant is published in the `cluster-config` ConfigMap and read through `${env:TENANT}` by the collector, Alloy, Tempo, Mimir and Loki, the same way `CLUSTER_NAME` is read.  Producers outside Kubernetes (JFR shipper, Java agents, EMR, Trino and Presto scripts) keep their existing configuration path.

### 4. Mimir: write-only S3, local reads (D1)
Target `distributor,ingester,querier,query-frontend,query-scheduler`: no compactor and no store-gateway.  The ingester ships every block to `observabilitymetrics/<tenant>/` about a minute after cutting it (`ship_interval: 1m`, 2h blocks), and answers every query from its head and local blocks: `limits.query_ingesters_within: 0`, `querier.query_store_after: 87600h`, `blocks_storage.tsdb.retention_period: 87601h` (kept for the cluster's life; retention must exceed `query_store_after`).  The ingester serves local blocks through `db.ChunkQuerier` over the whole TSDB.  There is no compaction, cleaner, retention or `delete_tenant` code in the process, no bucket index, and startup is bounded by one cluster's data.  Consequence: no running cluster reads other clusters' metrics, live or past; reading the shared metrics store needs one controlled index writer in #966 or the #902 local stack.

### 5. Metrics root (D4)
`observabilitymetrics/<tenant>/`, because Mimir's prefix must be letters and digits only.

### 6. Loki: `target: all`, compactor idle (D5)
`compaction_interval: 87600h` (the first run waits a full interval), `retention_enabled: false`, `retention_period: 0`, `deletion_mode: disabled` (the delete API returns 403).  The IAM deny backs this up.  `object_prefix: observability/logs`, index labels `cluster`, `host.name`, `node_role`, `source` (D11; `source` becomes a resource attribute in the collector), ingester id `<tenant>.<name>-<id>` (D11).  `reject_old_samples_max_age: 8760h`, `creation_grace_period: 24h`, `multi_tenant_queries_enabled: true`, raised ingestion and stream limits, `max_global_streams_per_user: 0`.  The `schema_config` (fixed `from` date, `tsdb`, `v13`, `24h`, `index_`) is a permanent contract of the shared store: new periods may only be appended.

### 7. IAM deny (D3)
`Deny s3:DeleteObject, s3:DeleteObjectVersion` for the EC2 instance role on the metrics, logs, traces and annotations prefixes, in `AWSPolicy.Inline.S3AccessWildcard`, re-applied on every `up`.  Profiles are excluded because Pyroscope v2 compaction deletes merged segments.  The owner's workstation deletes are unaffected.  Mimir's shipper deletes a partially uploaded block after a failed upload; the deny blocks that delete harmlessly (the next sync overwrites the block) and shows as AccessDenied in Mimir's log.

### 8. Teardown flush (D2)
Order: annotation mirror → Loki flush and verify → Mimir flush and verify → annotations backup → teardown.
- Loki: `POST /ingester/shutdown?flush=true&terminate=false` with a 600s timeout; its 204 is the completion signal (the handler returns only after every flush queue drains).  Scale Loki to 0 so the Store builds the active and previous heads and the shipper uploads them.  Then, over SSH, require that no index WAL segment is left and that every locally built multitenant index file exists in S3.
- Mimir: scrape the failed-compaction counter and the head's newest timestamp; `POST /ingester/shutdown` (600s); require the counter unchanged and a local block whose `maxTime` covers the head's newest sample; HEAD every shippable block's `meta.json` in S3; scale Mimir to 0.
- Stop on failure (owner override, 2026-09-26, replacing the rollback journal): any failed or timed-out step stops `down` where it is.  No infrastructure is torn down, nothing is scaled up or restarted, and each backend stays as the step left it.  `down` reports the step, its cause, each backend's state (running, ingester stopped, scaled to 0) and that `down --force` tears down without the tail, and exits non-zero.  WALs and local blocks stay on the hostPath.  There is no retry: a retry would POST to a stopped ingester.
- A teardown that fails after a successful flush restores nothing.  A successful flush is recorded in the cluster state (`tailFlush`: completion time, index files, chunks, blocks); a re-run of `down` finds it and skips the flush, going straight to the teardown; `up` clears it.  With no record, a backend at 0 or not ready stops `down` before the flush, pointing at `--force`.  The flush runs only after the confirmation, so declining or `--dry-run` stops nothing.  `--force` skips every step; redirect clusters skip them.

### 9. Annotations (D6)
Grafana stays where annotations are written.  `AnnotationMirror` copies them to Loki: at once after each tool annotation (the command fails if Loki fails), and every annotation inside Loki's accepted window (`MAX_ENTRY_AGE_HOURS` back to `MAX_ENTRY_AHEAD_HOURS` ahead), including UI ones, at `grafana backup` and before teardown; one outside it is reported with `AnnotationsOutsideLokiWindow` and kept in Grafana and the S3 JSON backup (owner decision 2026-09-26).  Each annotation is its own Loki stream (`cluster`, `source="annotation"`, `annotation_id`), so mirroring is idempotent and backdating works.  The core dashboards' tag query becomes a Loki query for global annotations of the selected clusters; the built-in query keeps showing the current cluster's dashboard-scoped annotations, so each renders once.  The annotations JSON backup keeps its location.

### 10. Grafana (D9) and readers (D12)
Metrics datasource `prometheus` → Mimir (uid `mimir`), logs datasource `loki` (uid `loki`), both with the tenant header.  Dashboards keep PromQL; every LogsQL query becomes LogQL scoped by `cluster=~"$cluster"`.  The logs datasource carries a `trace_id` derived field to Tempo; Tempo's trace-to-logs query uses LogQL.  No dashboard metric query needs a cluster matcher because each Mimir holds only its own cluster (D7).  Readers go through one `ObservabilityHttp` path (per-client SOCKS unless Tailscale, tenant header) and default to the current cluster; `logs query -q` stays raw.

### 11. Cadence (D10)
Mimir 2h blocks; Loki default chunk ages.  Under D1 cadence only decides how soon S3 holds a copy; the teardown flush saves the rest.

### 12. Tempo 3.0.3: no compaction, no retention, no dropped spans
The default overrides set `compaction.compaction_disabled: true`; `block_retention: 0` is not used (in Tempo 3 it makes every block eligible for deletion).  `live_store.max_block_duration: 5m`.  Per-tenant ingestion limits that dropped 50% of spans (`max_traces_per_user`) are removed, `live_store.max_live_traces_bytes: 0`, attributes are stored in full (`distributor.max_attribute_bytes: 0`), and a trace reaches the WAL within about 2s (`flush_check_period: 1s`, `max_trace_idle: 1s`, `max_trace_live: 10s`).

### 13. Pyroscope 2.3.1: pure v2, compaction on, retention off (owner override)
`architecture_storage: v2`, `metastore.index.cleanup_interval: 0`, `limits.retention_period: 0`; the metastore state is on a hostPath; a startup probe covers the 45s readiness wait.  Each cluster's metastore compacts only its own blocks.  After teardown, profiles stay in S3 but need #966 to save the index and add Pyroscope to `observability compact`.

### 14. JFR shipping stays; nothing unshipped is deleted (owner choice after facts)
Pruning removes only shipped chunks.  At the size bound with nothing prunable left, recording stops and is reported; it resumes when space is freed.  Every upload carries the tenant.

### 15. Durability and restarts
The WALs of Mimir, Loki and Tempo and the Pyroscope metastore live on hostPaths under `/mnt/db1`.  Each workload's pod template carries a hash of its rendered template and the ConfigMaps it reads, so Kubernetes rolls a workload only when that hash changes.

### 16. Diagnose the missing Tempo blocks on a real cluster
The owner chose to run the branch build only (no cluster from `main`).  On the branch, blocks land and survive restarts; H2 (emptyDir WAL lost on every deploy restart) and the Tempo 3 span-limit loss are fixed and tested.  Whether H1 (no spans) or H2 caused the original Tempo 2 loss is left undetermined, by the owner's choice (issue 967).

### 17. Upgrades
Every observability image moves to its latest release and is pinned: Mimir 3.2.1, Loki 3.7.8, Pyroscope 2.3.1, Tempo 3.0.3, Grafana 13.2.2, renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0 (Java agent injection off), Fluent Bit 5.1.2, collector 0.161.0.

## Alternatives Considered

- **Tempo cause (Q1–Q3).**  Chosen: investigate on a real cluster.  Rejected: importing an old snapshot; a cluster from `main` (**owner override**: branch build only).
- **Old-rule sweep.**  Architect recommended removing easy-db-lab-created rules on every `up`/`down`.  **Owner override:** dropped; no rule exists in the account.
- **Victoria 7-day retention.**  Recommended folding it in.  **Owner override:** left to #902; now moot because VictoriaMetrics and VictoriaLogs are removed.
- **Tempo durability (D6-early).**  Chosen: WAL on a hostPath, verified by a kill test.  Rejected: hostPath only; defer to #966.
- **Restart only changed workloads (Q5).**  Chosen: config hashes over the rendered template and ConfigMaps.  Rejected: leave the forced restarts.
- **Tenancy model (Q7).**  Architect's first design put the tenant in the path of single-tenant backends.  **Owner override:** native multi-tenancy.
- **Pyroscope storage mode (Q20).**  Recommended: `architecture_storage: v1`.  **Owner override:** v2.  Rejected: staying on 1.18.
- **Pyroscope v2 compaction (Q21).**  Chosen: on.  Rejected: off.
- **Upgrade scope (Q19).**  Recommended: Tempo and Pyroscope only.  **Owner override:** every image.
- **JFR pruning (Q14, Q16–Q18).**  Owner first chose async-profiler's OTLP export; after the facts (push unreleased, one event type per session, no tenant header), **owner choice:** keep JFR and never delete unshipped or rejected chunks; no OTLP follow-up.
- **Beyla Java agent (B4).**  Chosen: turn off injection only.  Rejected: leave it on; remove Beyla.
- **Tempo crash window (B6).**  Chosen: 1s/1s/10s live-store settings.  Rejected: defaults; a memory limit.
- **Tempo attribute truncation.**  Chosen: `distributor.max_attribute_bytes: 0` (a per-tenant 0 falls back to 2048).  Rejected: keep 2048.
- **Alloy tenant setting.**  The confirmed design named `tenant_id`; it does not exist in Alloy v1.19.2 or v1.20.0.  **Owner decision:** send `X-Scope-OrgID` through `headers`; upgrade Alloy to v1.20.0.
- **Backup paths.**  Owner decisions on a tenant-in-name backups root and then a per-cluster stopgap were **superseded** by folding in #964/#965: metrics and logs are stored by their backends and no snapshot backup remains.
- **Epic scope.**  Options: fold in all of #964/#965/#966; fold in #964 and #965 only; fold in none.  **Owner decision:** #964 and #965 only.
- **D1 Mimir reads.**  Chosen: write-only S3, local reads.  Rejected: (b) a hardened compactor per cluster as index maintainer, relying on the experimental scheduler client to plan nothing, with 810 dashboard queries needing cluster matchers, `delete_tenant` exposed, the index race and store-gateway startup growth.  Rejected in design review: the neutralized compactor from revision 1, which fails open (compaction runs hourly; a metadata error keeps the job; level ≥2 blocks skip the wait; every cluster owns every tenant).
- **D2 teardown flush.**  Chosen: verified flush that stops on failure.  Rejected: leave to #966 (loses the last 1–3h of metrics and up to 2h of logs); an unverified flush on SIGTERM.  **Owner override (2026-09-26):** the verified flush with a rollback journal (restart or scale up every backend a failed attempt stopped, retry whole attempts, restore the backends after a failed teardown) was replaced: "If I'm taking the cluster down I want it down. Do not try to recover by starting it again."  `down` stops on failure and never scales a backend up.
- **D3 IAM deny.**  Chosen: deny, profiles excluded.  Rejected: deny profiles too; no deny.
- **D4 metrics root.**  Chosen: `observabilitymetrics/<tenant>/`.  Rejected: `mimir/<tenant>/`; a dedicated bucket.  (The owner first asked for `observability/metrics`, which Mimir's prefix rule rejects.)
- **D5 Loki compactor.**  Chosen: `target: all`, compactor idle.  Rejected: two processes in the pod; a target list in one process (breaks reads).
- **D6 annotations.**  Chosen: Grafana first, mirrored to Loki.  Rejected: a continuous mirror sidecar; Loki only with no mirror (misses UI annotations).
- **D7 cluster matchers.**  Not needed under D1 (a).  Rejected with D1 (b): adding matchers to 810 metric expressions.
- **D8 tenant source.**  Chosen: `cluster-config`.  Rejected: render the literal everywhere.
- **D9 datasource uids.**  Chosen: `mimir` and `loki`.  Rejected: keep `VictoriaMetrics` (fails the no-reference criterion).
- **D10 cadence.**  Chosen: defaults.  Rejected: shorter blocks and chunks.
- **D11 Loki labels.**  Chosen: four index labels and a tenant.cluster ingester id.  Rejected: `cluster` only with default ids.
- **D12 reader scope.**  Chosen: current cluster by default.  Rejected: the whole tenant.
- **Fold-ins.**  Chosen: all six (HTTP path, uid constants, MCP scoping, sysbench, self-scrape, name validation).  Rejected: a subset.
- **D11 order against draft PR #913.**  This change lands first; #913 is re-scoped onto the new layout.

## Risks / Trade-offs

- [No cross-cluster metrics reads] → Under D1 no running cluster reads other or past clusters' metrics; they sit in S3 until #966 adds one live index writer or #902's stack reads them.  #964's `a|b` criterion holds only within one Mimir.  Accepted by the owner.
- [Pyroscope v2 index is local] → Profiles are unreadable after teardown until #966 saves the index.  Accepted by the owner.
- [Store growth without compaction] → Tempo pollers and Loki queriers list more objects as tenants grow; #966 compaction mitigates it.
- [Loki `object_prefix` is experimental; the schema is permanent] → Pin the schema; only append periods.
- [Neutral backends rely on config] → The IAM deny is the hard guarantee for metrics, logs, traces and annotations.
- [Teardown flush can abort `down`] → It stops with no infrastructure removed and names the failed step and each backend's state; `--force` tears down without the tail.  A backend it left stopped stays stopped: the owner chose that over restarting it.
- [Annotation display gap] → Dashboard-scoped annotations of other clusters are stored in Loki but not displayed; edits in Grafana add a new Loki entry and leave the old text.
- [Dashboards over a shared tenant] → Logs panels are cluster-scoped; the `cluster` variable still defaults to All.
- [Major-version upgrades] → Config keys moved.  Mitigation: rendered-config tests, container tests on the pinned images, and real-cluster verification.
- [WAL is not fsynced] → A node crash can lose unsynced data in any backend.
- [Region] → Backends reach the account bucket through the cluster's region endpoint, which is the profile region in the normal flow.

## Migration Plan

No migration.  Clusters are ephemeral.  New clusters write to the new layout.  Past data stays where it is.  Draft PR #913 rebases onto this layout.

## Open Questions

- How Grafana 13.2.2 maps Loki annotation metadata (tags, `timeEnd`) — checked on the test cluster.
