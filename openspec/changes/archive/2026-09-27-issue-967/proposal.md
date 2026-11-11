## Why

easy-db-lab deletes the owner's observability data automatically, which the ABSOLUTE RULE in `CLAUDE.md` forbids.  `down` sets S3 expiry on the metrics and logs backups and on the data bucket that holds profiles, Tempo deletes blocks after 48 hours, and the JFR shipper prunes profile chunks that never reached Pyroscope.  Metrics and logs also live only on the control node's disk: VictoriaMetrics and VictoriaLogs survive a cluster only as snapshots that `down` copies to S3, so the data of many tests cannot be stored together.  A day-long cluster also left no Tempo blocks in S3 at all.

This change stops every automatic deletion, gives every cluster a tenant (the customer), and makes every signal's backend write straight to S3 in one layout in the account bucket: Mimir for metrics and Loki for logs (folded in from #964 and #965), plus Tempo for traces and Pyroscope for profiles.  It fixes the missing Tempo blocks and moves every observability image to its latest release.

## What Changes

**Deletion stops**
- **BREAKING**: `down --retention-days` is removed.  `down` no longer sets any lifecycle or expiry rule, on `clusters/<name>-<id>/`, on the data bucket, or (with `--all`) on any data bucket.  The code that sets S3 lifecycle expiry is removed.  `down --all` still deletes data buckets that are empty.
- Mimir runs with no compactor and no store-gateway, so no compaction, retention, cleanup or tenant-deletion path exists in a cluster.
- Loki runs with its compactor idle (compaction interval of 10 years), retention off, and deletion mode `disabled`.
- Tempo runs with compaction and retention turned off for every tenant.
- Pyroscope runs pure v2 storage with v2 compaction on and the metastore retention cleanup off.
- The EC2 instance role gets an explicit deny of `s3:DeleteObject` and `s3:DeleteObjectVersion` on the metrics, logs, traces and annotations prefixes, re-applied on every `up`.  Profiles are excluded because Pyroscope v2 compaction deletes merged segments.
- The JFR shipper never deletes an unshipped or rejected chunk.  When the size bound is reached and only unshipped or rejected chunks remain, it stops recording and reports it.  Shipped chunks are still pruned by age and size.

**Tenancy and layout**
- New option `init --tenant <name>`, default `default`.  The tenant is the customer.  Names match `^[a-z][a-z0-9_-]{0,62}$`; `index` is reserved.
- Cluster names are validated against `^[a-z][a-z0-9-]{0,39}$` at `init` and when state loads.
- Mimir, Loki, Tempo and Pyroscope run native multi-tenancy.  The tenant is published in the `cluster-config` ConfigMap and read from there by the collector, Alloy, Tempo, Mimir and Loki.  Every writer and reader sends it in `X-Scope-OrgID`, on redirected clusters too.  Alloy sends it through its `headers` map (Alloy has no `tenant_id` setting).
- Layout in the account bucket:
  - `observabilitymetrics/<tenant>/` — Mimir's blocks.  Mimir's storage prefix must be letters and digits only, so `observability/metrics` is impossible.
  - `observability/logs/` — Loki's chunks (per tenant) and index tables.  Each Loki ingester is named `<tenant>.<name>-<id>`, so every index file names its tenant and cluster.
  - `observability/traces/` — Tempo; Tempo makes the tenant directories.
  - `observability/profiles/` — Pyroscope, moved off the data bucket.
  - `observability/annotations/<tenant>/` — the Grafana annotations JSON backup.
  - `config/` stays under `clusters/<name>-<id>/`.

**Mimir and Loki replace VictoriaMetrics and VictoriaLogs (#964, #965)**
- Mimir 3.2.1 runs monolithic on the control node (distributor, ingester, querier, query-frontend, query-scheduler).  It ships every block to S3 within about a minute but answers queries only from its own ingester (head and local blocks, kept for the cluster's life).  A running cluster does not read other clusters' metrics; reading the shared metrics store is later work (#966 or #902).
- Loki 3.7.8 runs as a single binary (`target: all`) on the control node, reads other clusters' logs from S3, and allows queries across tenants.
- The collector sends metrics to Mimir by remote write and logs to Loki by OTLP.  Tempo's metrics generator writes to Mimir.  The sysbench kit moves to OTLP through the collector.  Every series and line carries `cluster=<name>-<id>`.
- Grafana gets a `prometheus` datasource on Mimir (uid `mimir`) and a `loki` datasource (uid `loki`).  Dashboards keep their PromQL; every LogsQL query becomes LogQL.  Log lines link to their traces and traces link to their logs.
- Readers (`logs query`, `spark logs`, the EMR step lookup, MCP metrics, `status`) go through one tenant-aware HTTP path and are scoped to the current cluster by default.
- Annotations stay in Grafana and are mirrored to Loki: at once for the tool's annotations, and every annotation inside Loki's accepted window (including UI ones) at `grafana backup` and before teardown; one outside the window is reported and kept in Grafana and the S3 backup.
- **BREAKING**: VictoriaMetrics, VictoriaLogs, the `metrics` command group (`backup`, `import`, `ls`), and `logs backup`, `logs import`, `logs ls` are removed.  No metrics or logs snapshot backup exists any more.  `logs query` stays with the same options.

**Teardown saves the tail**
- Before teardown, `down` mirrors annotations to Loki, flushes Loki and verifies that every chunk and every index file is in S3, flushes Mimir and verifies that its head became blocks and that every block is in S3, then backs up the annotations.  Any failed step stops `down` with nothing torn down and no backend restarted, and reports the step and each backend's state; `--force` tears down without the tail.  A successful flush is recorded, so a re-run of `down` skips it.

**Missing Tempo blocks and restarts**
- The missing Tempo blocks were investigated on a real cluster; the emptyDir WAL loss (H2) and a Tempo 3 span-limit loss were fixed and tested.
- The write-ahead data of Mimir, Loki and Tempo, and the Pyroscope metastore, live on hostPaths under `/mnt/db1`, so a pod restart loses nothing it acknowledged.
- `up` and `grafana update-config` restart an observability workload only when its own configuration changed.
- The collector's own telemetry and the metrics of Mimir, Loki, Tempo and Pyroscope are scraped.

**Upgrades (every observability image to its latest release; no image uses `latest`)**
- Mimir 3.2.1, Loki 3.7.8, Pyroscope 2.3.1, Tempo 3.0.3, Grafana 13.2.2, Grafana image renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0 (Java agent injection off), Fluent Bit 5.1.2.
- The OTel collector (cluster DaemonSet, stress-job sidecar, EMR binary) pins 0.161.0.
- The collector's deprecated component IDs are renamed, `transform` processors set `error_mode: propagate`, and the host `cpu` scraper requests the per-core label.

**Fold-ins**
- One tenant-aware HTTP path for readers and flushers; datasource uid constants; MCP metrics scoped to the cluster; sysbench on OTLP with a cluster label; self-scrape jobs for the backends; cluster name validation.
- The Trino and Presto kits send profiles to the cluster's profile endpoint, so they follow telemetry redirect.
- Stale comments are fixed; the dead `TemplateService.BUCKET_NAME` and the unused IAM lifecycle actions are removed.
- User docs and the `CLAUDE.md` files describe the new layout, tenancy, backends and versions.

## Capabilities

### New Capabilities
- `observability-store`: the tenant, cluster name rules, the account-bucket layout, native multi-tenancy for all four backends, the tenant source, Mimir's local reads, no automatic deletion, the IAM deny, data in S3 while up and across restarts, the cluster label and reader scope, config-driven restarts, backend self-metrics, Grafana datasources, pinned versions, and the removal of VictoriaMetrics and VictoriaLogs.

### Modified Capabilities
- `cluster-lifecycle`: teardown sets no expiry, runs the verified flush and stops on failure, and takes no snapshot backups.
- `grafana-annotations`: annotations are mirrored to Loki; the core dashboards render global annotations from Loki; the backup lands in the tenant's annotations directory.
- `profiling`: shipped chunks carry the tenant; retention never deletes an unshipped or rejected chunk, and recording stops at the size bound instead.
- `telemetry-redirect`: every signal carries the tenant; Trino, Presto and EMR Spark follow the redirect; no Mimir or Loki is deployed; `logs query` and the teardown steps refuse or skip.
- `observability`: renderer pin; datasource and pipeline wording moves to Mimir and Loki.
- `multi-cluster-dashboards`, `cloudwatch-metrics-export`, `k8s-container-log-collection`, `kit-metrics-catalog`, `kit-metrics-declaration`, `ignite3`, `end-to-end-testing`, `server`, `networking`, `tool-execution`, `spark-emr`, `tailscale-direct-connect`, `live-stream-metrics`: references to VictoriaMetrics, VictoriaLogs and the removed commands move to Mimir, Loki and the teardown flush.

## Impact

- Commands: `init` (new `--tenant`, name validation), `down` (option removed, no expiry, verified flush), `grafana annotate`, `grafana backup`, `grafana update-config`, `up`, `logs query`, `spark logs`, `status`; the `metrics` group and `logs backup/import/ls` are removed.
- Code: new Mimir and Loki manifest builders, `ObservabilityHttp`, Mimir and Loki query services, `LogQl`, `AnnotationMirror`, `TeardownFlushService`; changes across `Down`, `Init`, `Up`, `ClusterState`, `ClusterConfigData`, `ObservabilityStore`, `ObservabilityStackService`, `OtelManifestBuilder`, `GrafanaDatasourceConfig`, `AWSPolicy`, `MetricsCollector`, `StatusCache`, `Status`, `EMRSparkService`, `SparkLogs`, `TelemetryRedirect`; removal of `configuration/victoria/`, `VictoriaBackupService`, `VictoriaStreamService`, `SnapshotListingService` and the removed commands and events.
- Dashboards: datasource uid renames and LogsQL-to-LogQL rewrites, through the `dashboard-editor` agent.
- Node scripts: `packer/cassandra/bin/edl-profiling-reconcile` and its test (already baked); comment-only updates.
- S3: new data lands only under `observability/` and `observabilitymetrics/`.  Past data stays where it is.
- IAM: the instance role gains a delete deny; users re-apply nothing.
