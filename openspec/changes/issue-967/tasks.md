## 1. Diagnose the missing Tempo blocks on a real cluster (owner decision 2026-09-24: branch build only; see issue 967)

- [x] 1.1 Run `./gradlew installDist` on `main` and bring up a small cluster (i4i.xlarge) from a workspace under `clusters/`.
- [x] 1.2 Send traces for more than 10 minutes: run `cassandra-easy-stress` through a stress job, which attaches the OTel Java agent.
- [x] 1.3 List `clusters/<name>-<id>/tempo/` in the account bucket and check for block directories.
- [x] 1.4 Query VictoriaMetrics for `traces_spanmetrics_calls_total`, `tempo_distributor_spans_received_total`, `tempo_ingester_blocks_flushed_total`, `tempo_ingester_failed_flushes_total`, backend PUT errors, and Tempo restarts.  If collector exporter errors are needed, scrape `:8888` by hand for this run.
- [x] 1.5 Restart Tempo with `grafana update-config` and check whether data in the open block is lost.
- [x] 1.6 Post the cause on issue 967, with the evidence.  If the cause is outside the scope below, stop and report it before continuing.
- [x] 1.7 Tear the cluster down.

## 2. Remove automatic deletion from `down`

- [x] 2.1 Remove `--retention-days`, `setClusterLifecycleRule`, and the data-bucket expiry from `Down.kt` (single cluster and `--all`).  `--all` keeps `deleteEmptyBucket`.
- [x] 2.2 Remove `AWS.setFullBucketLifecycleExpiration`, `AWS.setLifecycleExpirationRule`, and their `AwsS3BucketService` wrappers.  `teardownDataBucket` keeps only `disableBucketRequestMetrics`.
- [x] 2.3 Remove `Event.S3.LifecycleRuleSet` and `Event.S3.DataBucketExpiring`.
- [x] 2.4 Remove `s3:PutBucketLifecycleConfiguration` and `s3:GetBucketLifecycleConfiguration` from `iam-policy-iam-s3.json`.
- [x] 2.5 Test: `down --retention-days 1` fails as an unknown option (PicoCLI parse test).
- [x] 2.6 Integration test (LocalStack): after `down`'s S3 steps run, `GetBucketLifecycleConfiguration` returns `NoSuchLifecycleConfiguration` on the account bucket and the data bucket, and every object is still present.  `--all` deletes an empty data bucket and keeps a non-empty one.

## 3. Tenant

- [x] 3.1 Add `Constants.Observability` (prefix, directory names, default tenant, tenant regex).
- [x] 3.2 Add `InitConfig.tenant` (default `default`) and a `ClusterState.tenant()` accessor.  An old state file without the field reads as `default`.
- [x] 3.3 Add `init --tenant`.  Validate it in `validateParameters()` against `^[a-z][a-z0-9_-]{0,62}$`, failing before any infrastructure is created.
- [x] 3.4 Tests: default tenant; explicit tenant persisted; invalid names (`Acme`, `1acme`, 64 chars, empty) fail with the rule in the message; an old `state.json` without the field loads as `default`.

## 4. Account-bucket layout and snapshot naming

- [x] 4.1 Add one type that builds every `observability/…` path from the account bucket and the tenant, and one value type that builds and parses `<yyyyMMdd-HHmmss>_<name>-<clusterId>`.
- [x] 4.2 `VictoriaBackupService`: snapshots go to `observability/metrics/<tenant>/<snapshot>/` and `observability/logs/<tenant>/<snapshot>/`.  Kubernetes job names keep the timestamp alone.
- [x] 4.3 `GrafanaAnnotationBackupService`: artifacts go to `observability/annotations/<tenant>/<snapshot>.json` for both `down` and `grafana backup`.
- [x] 4.4 Add a snapshot-listing service used by `metrics ls` and `logs ls`.  List the whole tenant, with timestamp, cluster, file count, and size.  Add a `cluster` field to the `BackupEntry` events.
- [x] 4.5 Remove the old helpers in `ClusterS3Path` (`tempo()`, `pyroscope()`, `victoriaMetrics()`, `victoriaLogs()`, `grafanaAnnotationsRoot()`, `grafanaAnnotationsArtifact()`) and their constants.  Update `StatusCache` and `Status` to show the new paths.
- [x] 4.6 Tests: path type and snapshot-name round trip; two clusters with the same name and second produce different keys; job names are valid DNS-1123 names; the lister groups objects by snapshot and reads the cluster back (LocalStack integration test for listing).

## 5. Native multi-tenancy

- [x] 5.1 Tempo: `multitenancy_enabled: true`; storage prefix `observability/traces` in the account bucket; replace `cluster_s3_prefix` in `cluster-config`.
- [x] 5.2 OTel collector: `X-Scope-OrgID: <tenant>` on the Tempo exporter, on local and redirected clusters.
- [x] 5.3 Pyroscope server: `multitenancy_enabled: true`; bucket is the account bucket, prefix `observability/profiles`.  Remove `TemplateService.BUCKET_NAME` and fix the stale prefix comments (`TemplateService.kt`, `configuration/CLAUDE.md`).
- [x] 5.4 Alloy: `X-Scope-OrgID` in the `pyroscope.write` endpoint headers.
- [x] 5.5 Pyroscope Java agent: `PYROSCOPE_TENANT_ID` (or `-Dpyroscope.tenant.id`) for stress jobs, the sidecar, EMR Spark, and the Trino and Presto kits.
- [x] 5.6 Trino and Presto kits: use the cluster's profile endpoint instead of the hard-coded `http://${CONTROL_HOST_PRIVATE}:4040`, so they follow telemetry redirect.
- [x] 5.7 Grafana datasources: add header support to the datasource model and send `X-Scope-OrgID` from the Tempo and Pyroscope datasources.
- [x] 5.8 Tests (real `TemplateService`, parsed output): rendered Tempo, Pyroscope, collector, and Alloy configs carry multitenancy and the tenant header; every Java agent command line carries the tenant; Trino and Presto start scripts use the redirect endpoint when redirect is set; datasource provisioning carries the header.

## 6. Tempo 3.0.3 (owner decision 2026-09-24: branch build only; see issue 967)

- [x] 6.1 Upgrade the image to 3.0.3.  Rewrite `tempo.yaml` for Tempo 3: remove `ingester:` and `compactor:`; set `live_store.max_block_duration: 5m`; set `overrides.defaults.compaction.compaction_disabled: true`; no `block_retention`.
- [x] 6.2 Put both WAL paths (`storage.trace.wal.path`, `live_store.wal.path`) on a hostPath under `/mnt/db1/tempo`.  Prepare the directory the same way Pyroscope's is prepared.
- [x] 6.3 Apply the fix for the cause found in group 1, if it is not already covered by this group.
- [x] 6.4 Unit test on the rendered config: multitenancy on, compaction disabled in the default overrides, no `block_retention`, prefix `observability/traces`, block cut 5m.
- [x] 6.5 Integration test (TestContainers): Tempo at the deployed image constant plus LocalStack.  Push OTLP spans with the tenant header; assert a block appears under `observability/traces/<tenant>/` within the block cut interval.  Then push spans, stop the container without a flush, restart it on the same WAL volume, and assert the spans reach S3.  This test covers the cause from group 1.

## 7. Pyroscope 2.3.1

- [x] 7.1 Upgrade the image to 2.3.1.  Set `architecture_storage: v2`, `metastore.index.cleanup_interval: 0`, and `limits.retention_period: 0`.  Keep v2 compaction on.
- [x] 7.2 Put the metastore data and Raft directories on a hostPath under `/mnt/db1/pyroscope`.
- [x] 7.3 Check the `/ingest?format=jfr` path, Alloy, and the Java agent against 2.3.1.
- [x] 7.4 Unit test on the rendered config: v2 storage, retention cleanup off, retention period 0, multitenancy on, account bucket and prefix, metastore paths on the hostPath.

## 8. JFR shipper: no deletion of unshipped or rejected chunks

- [x] 8.1 `edl-profiling-reconcile`: prune only shipped chunks.  Never delete unshipped or `.rejected` chunks.
- [x] 8.2 When the directory reaches its size bound and nothing prunable is left, stop the recording session, record the reason in the effective state, log it, and export a metric.  Resume recording when the directory is back under the bound.
- [x] 8.3 Send `X-Scope-OrgID` on `upload_chunk`.  Add the tenant to `ProfilingConfig`, written by `SetupInstance`, `ProfilingStart`, and `ProfilingStop`.
- [x] 8.4 `cassandra profile status` renders the stopped-at-bound state and emits a typed `Event.Profiling` event.  Remove the "chunks lost" path.
- [x] 8.5 Update `edl-profiling-reconcile.test.sh` (through `/bin/sh`): unshipped and rejected chunks survive a prune past both bounds; recording stops at the bound and restarts under it; the curl carries the tenant header.
- [x] 8.6 Kotlin tests for the new `ProfilingConfig` field and the new status rendering and event.
- [x] 8.7 Update the `profiler-health` dashboard for the new metric through the `dashboard-editor` agent.

## 9. Restart only changed workloads

- [x] 9.1 Add a hash of each observability workload's consumed configuration (ConfigMaps and relevant settings) to its pod-template annotations.
- [x] 9.2 Remove the unconditional `restartAppliedWorkloads()` from `deploy()`.  Keep the rollout wait and the readiness gate.
- [x] 9.3 Integration test (K3s TestContainers): applying the stack twice with only dashboards changed leaves the Tempo and Pyroscope pods unchanged; changing Tempo's config rolls Tempo only.

## 10. Other upgrades and collector changes

- [x] 10.1 Grafana 13.2.2; image renderer pinned to v5.12.4.
- [x] 10.2 Alloy v1.19.2; Beyla 3.36.0 (confirm the published tag format).
- [x] 10.3 VictoriaMetrics v1.152.0; VictoriaLogs v1.52.0; `vmbackup` v1.152.0; `amazon/aws-cli` 2.37.2.
- [x] 10.4 OTel collector contrib 0.161.0 in `OtelManifestBuilder` and `StressJobService`; EMR collector binary 0.161.0 in `Constants`.
- [x] 10.5 Rename deprecated collector component IDs (`file_log`, `k8s_attributes`, `resource_detection`, `prometheus_remote_write`, `span_metrics`, `service_graph`, `delta_to_cumulative`, `signal_to_metrics`) in the cluster and EMR configs.  Set `error_mode: propagate` on every `transform` processor.  Request the per-core `cpu` attribute in the `hostmetrics` cpu scraper.  Check the `prometheus` receiver for removed options.
- [x] 10.6 Add a scrape job for the collector's own `:8888` telemetry.
- [x] 10.7 Find every LogsQL query in dashboards and code and fix bare filter pipes for VictoriaLogs v1.51+ (through the `dashboard-editor` agent for dashboards).
- [x] 10.8 Test: no observability image reference in `src/main` uses `latest`; the rendered collector config contains the `:8888` job, `error_mode: propagate`, and no deprecated component ID.

## 11. Docs and project notes

- [x] 11.1 `docs/reference/commands.md`: `init --tenant`, `down` without `--retention-days`, `metrics ls`/`logs ls` output.
- [x] 11.2 `docs/user-guide/victoria-metrics.md`, `victoria-logs.md`, `monitoring.md` or `docs/reference/opentelemetry.md`, `pyroscope-configuration.md`, `profiling.md`: the layout, tenancy, no-deletion behavior, recording stop at the bound, versions, and how the Pyroscope UI selects a tenant.
- [x] 11.3 `CLAUDE.md`, `configuration/CLAUDE.md`, `services/aws/CLAUDE.md`, `commands/CLAUDE.md`: layout, tenant, data-bucket use, Tempo and Pyroscope storage, restarts by config hash.
- [x] 11.4 Fix the `ClusterS3Path` KDoc that names `setClusterLifecycleRule`.

## 12. Build and verify on a real cluster (done on verify967, final967 and qa967; evidence in .spec-flow/verification.md)

- [x] 12.1 Run `./gradlew check` (JDK 21) and `./gradlew testCassandraScripts` in a subagent.  Fix every failure.
- [x] 12.2 Run `./gradlew installDist` and bake a new AMI with `build-image`, because the JFR shipper change lives on the node.
- [x] 12.3 Bring up a cluster with `--tenant verify967` on the new AMI.  Send traces for more than 10 minutes.
- [x] 12.4 While the cluster is up, confirm blocks under `observability/traces/verify967/`.
- [x] 12.5 Kill the Tempo pod while it holds unflushed spans; confirm those spans are queryable afterwards.
- [x] 12.6 Confirm profiles land under `observability/profiles/` and nothing new lands in the data bucket; confirm Pyroscope queries return data after a Pyroscope pod restart.
- [x] 12.7 Open the profiling dashboard's Pyroscope UI link and record how it selects the tenant.  If it cannot, report the facts to the owner.
- [x] 12.8 Run `grafana update-config` with only a dashboard change; confirm Tempo and Pyroscope pods did not restart.
- [x] 12.9 Run `metrics backup`, `logs backup`, `metrics ls`, `logs ls`, and `down`; confirm the snapshot and annotation locations, the cluster column, and that no lifecycle rule exists on either bucket afterwards.
- [x] 12.10 Confirm every running observability pod uses the pinned versions.
- [x] 12.11 Upgrade Alloy to v1.20.0 (owner decision; commit 3730ca2a) and confirm eBPF and JFR profiles still land.

## 13. Tenant source and cluster name (D8, fold-ins)

- [x] 13.1 Add `tenant`, `metrics_s3_prefix` and `logs_s3_prefix` to `ClusterConfigData` / the `cluster-config` ConfigMap.
- [x] 13.2 Read the tenant through `${env:TENANT}` from `cluster-config` in the collector, Alloy and Tempo (replace the rendered `__TENANT__` literal and `resolveTenant()`); keep the config-hash rollout correct.  Producers outside Kubernetes keep their current source.
- [x] 13.3 Reserve the tenant name `index` in `Init` validation and in the node reconciler's tenant rule; keep `NodeTenantRuleTest` in step.
- [x] 13.4 Validate the cluster name against `^[a-z][a-z0-9-]{0,39}$` in `Init.validateParameters()` and refuse it in `ClusterStateManager.load`.
- [x] 13.5 Tests: tenant in the rendered ConfigMap and env references; `index` refused; invalid cluster names refused at init and load; a valid 40-character name accepted.

## 14. Mimir (D1, D4)

- [x] 14.1 Add `Constants` for Mimir (image `grafana/mimir:3.2.1`, HTTP 9009, gRPC 9097) and the metrics root `observabilitymetrics`.
- [x] 14.2 Add `configuration/mimir/MimirManifestBuilder` and resource `mimir.yaml` (ConfigMap, Service, Deployment on the control node, hostNetwork, hostPath `/mnt/db1/mimir/tsdb`, `terminationGracePeriodSeconds: 600`, no resource limits).
- [x] 14.3 `mimir.yaml`: target `distributor,ingester,querier,query-frontend,query-scheduler`; multitenancy and tenant federation on; S3 backend in the account bucket with `storage_prefix` from `cluster-config`; `ship_interval: 1m`, `block_ranges_period: [2h]`, `retention_period: 87601h`, `flush_blocks_on_shutdown: true`; `query_store_after: 87600h`, `query_ingesters_within: 0`; raised ingestion and label limits, `max_global_series_per_user: 0`, `out_of_order_time_window: 10m`; `inmemory` rings, replication factor 1; `-config.expand-env=true`.
- [x] 14.4 Add the Mimir stage to `ObservabilityStackService` (replacing the VictoriaMetrics part of the Victoria stage) and prepare its hostPath directory.
- [x] 14.5 Unit test on the rendered config: no `compactor` and no `store-gateway` in the target, query routing values, prefix, tenancy, limits, hostPath.
- [x] 14.6 Integration test (TestContainers, `grafana/mimir:3.2.1` + SharedLocalStack): remote-write for tenants `a` and `b` with cluster labels; queries answered from ingesters, including after head compaction and after a SIGKILL restart on the same bind mount; blocks under `observabilitymetrics/a/`; `a|b` returns both with `__tenant_id__`; the object set only grows.

## 15. Loki (D5, D11)

- [x] 15.1 Add `Constants` for Loki (image `grafana/loki:3.7.8`, HTTP 3100, gRPC 9098).
- [x] 15.2 Add `configuration/loki/LokiManifestBuilder` and resource `loki.yaml` (hostNetwork, hostPath `/mnt/db1/loki/{wal,tsdb-index,tsdb-cache,compactor}` owned by uid 10001, grace 600s, no resource limits).
- [x] 15.3 `loki.yaml`: `auth_enabled: true`, `target: all`; `object_prefix: observability/logs`; tsdb shipper directories on the hostPath; WAL on the hostPath with `flush_on_shutdown`; `ingester.lifecycler.id: ${TENANT}.${CLUSTER_NAME}`; fixed `schema_config` (tsdb, v13, 24h, `index_`); `multi_tenant_queries_enabled: true`; compactor idle (`compaction_interval: 87600h`, `retention_enabled: false`); `retention_period: 0`; `deletion_mode: disabled`; `reject_old_samples_max_age: 8760h`, `creation_grace_period: 24h`; OTLP index labels `cluster`, `host.name`, `node_role`, `source`; raised ingestion, stream, label and query limits, `max_global_streams_per_user: 0`; `-config.expand-env=true`.
- [x] 15.4 Add the Loki stage to `ObservabilityStackService` (replacing the VictoriaLogs part) and prepare its hostPath directory.
- [x] 15.5 Unit test on the rendered config: target, idle compactor, retention and deletion off, prefix, schema, ingester id, labels, limits, hostPaths.
- [x] 15.6 Integration test (TestContainers, `grafana/loki:3.7.8` + SharedLocalStack): OTLP push for two tenants; `cluster` is an index label; two Loki containers on one bucket answer an `a|b` query with both clusters' lines; SIGKILL restart replays the WAL; `/services` shows the compactor never runs; the delete API returns 403.

## 16. Producers

- [x] 16.1 Collector: `prometheus_remote_write` to Mimir `/api/v1/push` and `otlp_http/logs` to Loki `/otlp`, both with `X-Scope-OrgID: ${env:TENANT}`; move `source` to a resource attribute; drop `transform/add_service_name`.
- [x] 16.2 Tempo `metrics_generator.remote_write` points at Mimir (org-id header on).
- [x] 16.3 sysbench kit: push OTLP JSON gauges to the collector at `${CONTROL_HOST_PRIVATE}:4318/v1/metrics` instead of the VictoriaMetrics import API, so series carry the cluster label.
- [x] 16.4 Scrape jobs for Mimir's and Loki's own metrics through node-local pod discovery (local mode only).
- [x] 16.5 Tests: rendered collector config (endpoints, headers, pipelines, scrape jobs); `OtelCollectorConfigValidationIntegrationTest` green; an end-to-end container test (pinned collector → Mimir and Loki containers) proves the tenant and cluster label arrive for metrics and logs.

## 17. Readers (D12, fold-ins)

- [x] 17.1 Add `services/ObservabilityHttp`: one tenant-aware path to control-node services (per-client SOCKS unless Tailscale via `HttpClientFactory`, `X-Scope-OrgID` from `ClusterState.tenant()`); never touch the socks system properties.
- [x] 17.2 `MimirQueryService` replaces `VictoriaMetricsQueryService` (`/prometheus/api/v1/query`); `LokiQueryService` replaces `VictoriaLogsService` (`/loki/api/v1/query_range`, `since`, `limit`, `direction=backward`).
- [x] 17.3 `LogQl` pure builder for `logs query` filters, `spark logs`, the EMR step lookup and the trace-to-logs link; default scope `cluster="<name>-<id>"`; `-q` raw.
- [x] 17.4 MCP `MetricsCollector` queries scoped to the current cluster (bug fix); `status` and `StatusResponse.accessInfo` fields `mimir` and `loki`; remove the snapshot paths from `Status` and `StatusCache`.
- [x] 17.5 Update dev scripts `bin/export-workload-metrics`, `bin/end-to-end-test`, `bin/debug-log-pipeline` to Mimir and Loki with the tenant header.
- [x] 17.6 Tests: `LogQl` strings; reader tenant header and cluster scope; `logs query` options unchanged; MCP queries carry the cluster matcher.

## 18. Grafana datasources (D9)

- [x] 18.1 `GrafanaDatasourceConfig`: `prometheus` datasource on Mimir `:9009/prometheus` (uid `mimir`) and `loki` datasource on `:3100` (uid `loki`), both with the tenant header; `trace_id` derived field to Tempo; Loki `detected_level` replaces `logLevelRules`; Tempo `tracesToLogsV2` query `{cluster=~".+"} | trace_id="$${__trace.traceId}"`; `serviceMap` and `tracesToMetrics` point at `mimir`; drop the `victoriametrics-logs-datasource` plugin.
- [x] 18.2 Datasource uid constants replace the repeated string literals.
- [x] 18.3 Test the rendered datasource provisioning (uids, headers after Grafana's env expansion, derived field, links).

## 19. Dashboards (every edit through the `dashboard-editor` agent)

- [x] 19.1 Rename the datasource uid `VictoriaMetrics` to `mimir` in every dashboard that names it (about 28).
- [x] 19.2 Rewrite LogsQL to LogQL, scoped by `cluster=~"$cluster"`: `cassandra-logs-analysis.json` (36), `log-investigation.json` (6, including 3 variables), `tempo.json` (7 Explore links), `kits/clickhouse/dashboards/clickhouse-logs.json` (7), `kits/postgres/dashboards/postgres.json` (2), `kits/tidb/dashboards/tidb.json` (1).
- [x] 19.3 Replace the "easy-db-lab markers" tag query on the 6 core dashboards with a Loki annotation query for global annotations (`source="annotation"`, `dashboard_uid=""`, `cluster=~"$cluster"`); keep the built-in query for dashboard-scoped ones.
- [ ] 19.4 Deploy and read back every changed dashboard from Grafana on a running cluster.

## 20. Annotations mirror (D6)

- [x] 20.1 Add `LokiPushClient` and `AnnotationMirror` (`push` one annotation, `syncAll`); one stream per annotation (`cluster`, `source="annotation"`, `annotation_id`), text as the line, tags / dashboard / panel / end time as metadata.
- [x] 20.2 `grafana annotate` and `CiliumInstallAnnotator` mirror right after Grafana accepts; the command fails non-zero if the mirror fails.
- [x] 20.3 `grafana backup` runs `syncAll` before the JSON backup.
- [x] 20.4 Tests (Loki container): pushing twice returns one row; a `--time -2h` annotation is accepted after newer ones; `syncAll` picks up an annotation created directly in a Grafana container; region annotations keep their end time.

## 21. Teardown flush, stop on failure (D2)

- [x] 21.1 Add `TeardownFlushService` (its action journal was later removed by 21.6); order annotation `syncAll` → Loki flush → Mimir flush → annotations backup; replace the VictoriaMetrics part of `TeardownBackupService`; keep `--force` (the retry was later removed by 21.6); skip on redirect clusters.
- [x] 21.2 Loki flush: `POST /ingester/shutdown?flush=true&terminate=false&delete_ring_tokens=false` (600s timeout, 204 = done); scale to 0 and wait (660s); over SSH require no index WAL segment and HEAD every `multitenant/<table>/<file>` as `observability/logs/index/<table>/<file>.gz`.
- [x] 21.3 Mimir flush: scrape the failed-compaction counter and head max timestamp; `POST /ingester/shutdown` (600s); require the counter unchanged and a local block `maxTime` ≥ the head max; HEAD every shippable block's `meta.json` (`numSamples > 0`, `level == 1`); scale to 0.
- [x] ~~21.4 Rollback: undo the journal in reverse (delete a stopped-ingester pod with grace 0; scale a scaled-down backend to 1), wait for readiness, report the original failure, keep all infrastructure.~~ Replaced by 21.6 (owner override, 2026-09-26): the rollback, the whole-attempt retry and the restore after a failed teardown are removed.
- [x] 21.5 Tests (containers + LocalStack + bind-mounted hostPath): Loki success including a backdated table; LocalStack errors → Loki timeout; process killed before the build → WAL check fails; failed upload → S3 check fails; Mimir success; forced-compaction failure → counter check fails; blocked PUTs → S3 check fails; a 0-sample block is ignored; Loki succeeds then Mimir fails (see 21.8 for what `down` then does); unit tests for the step order and `--force`.
- [x] 21.6 Stop on failure: a failed or timed-out step stops `down` with no teardown, no scale-up or pod restart, and a report naming the step, its cause, each backend's state and `down --force`; no retry; a failed teardown after a successful flush restores nothing.
- [x] 21.7 Record a successful flush in the cluster state; a re-run of `down` skips the flush; `up` clears the record; with no record, a backend at 0 or not ready stops `down` before the flush and points at `--force`.
- [x] 21.8 Tests: each stop point (Loki shutdown timeout, Loki write-ahead and S3 checks, Mimir compaction and S3 checks) scales nothing up and tears nothing down; a failed teardown restores nothing; a re-run after success skips the flush; a re-run with a stopped backend stops with the `--force` hint.

## 22. IAM deny (D3)

- [x] 22.1 Add `Deny s3:DeleteObject, s3:DeleteObjectVersion` on `observabilitymetrics/*`, `observability/logs/*`, `observability/traces/*` and `observability/annotations/*` to `AWSPolicy.Inline.S3AccessWildcard`; confirm `up` re-applies it.
- [x] 22.2 Test the policy JSON: the deny is present and does not cover `observability/profiles/*`.

## 23. Removals

- [x] 23.1 Remove `configuration/victoria/` (and the vmbackup and aws-cli pins), `VictoriaBackupService`, `VictoriaStreamService`, `SnapshotListingService`, the `metrics` command group, `LogsBackup`, `LogsImport`, `LogsLs`, their Koin, MCP and command registrations, their events (keep `Metrics.Node/System/Cassandra`), `Constants.Victoria` and the Victoria ports (the redirect keeps private 8428/9428 in `TelemetryRedirect`).
- [x] 23.2 Remove the dead `ObservabilityStore` metrics and logs snapshot paths; keep `SnapshotName` only for the annotations backup.
- [x] 23.3 Replace or remove the tests of removed code (`VictoriaBackupS3IntegrationTest`, `SnapshotListingServiceIntegrationTest`, `LogsQlCompatibilityIntegrationTest`).
- [x] 23.4 Tests: PicoCLI reports the removed commands as unknown; a source scan finds no VictoriaMetrics or VictoriaLogs reference outside `TelemetryRedirect`.

## 24. Compatibility and apply tests

- [x] 24.1 `LogQlCompatibilityIntegrationTest`: every Loki panel, variable and annotation query in the dashboard tree, and every `LogQl` string, runs against the pinned Loki; a 400 fails the test.
- [x] 24.2 `PromQlCompatibilityIntegrationTest`: every dashboard expression, with variables substituted, runs against the pinned Mimir.
- [x] 24.3 `K8sServiceIntegrationTest`: apply tests for the Mimir and Loki builders; unit test that no two control-node hostNetwork workloads share a port.

## 25. Docs and project notes

- [x] 25.1 Rewrite `docs/user-guide/victoria-metrics.md` and `victoria-logs.md` as Mimir and Loki pages; update `SUMMARY.md`, `monitoring.md`, `commands.md` (removed commands, `down` flush, `logs query`), `ports.md`, `opentelemetry.md`, `log-infrastructure.md`, `help/observability.md`, and kit docs.
- [x] 25.2 Update the root `CLAUDE.md` (storage backends, CLI commands), `configuration/`, `commands/`, `events/` and `dashboards/CLAUDE.md`; comment-only updates in `edl-profiling-reconcile` and its `.service`.

## 26. Build and verify on a real cluster

- [ ] 26.1 Run `./gradlew check` (JDK 21) in a subagent and fix every failure.
- [ ] 26.2 Bring up two clusters of different tenants from the worktree build (no AMI bake unless `packer/` changed).
- [ ] 26.3 Confirm Mimir blocks under `observabilitymetrics/<tenant>/` and Loki chunks and index under `observability/logs/` while up; no Victoria workload runs; pinned versions.
- [ ] 26.4 Confirm Loki answers an `a|b` query with both clusters' lines, and each cluster's Mimir answers only its own series.
- [ ] 26.5 Confirm dashboards (PromQL and LogQL panels, annotations) show data, log-to-trace and trace-to-log links work, and `logs query`, `spark logs` (if EMR is used), MCP metrics and `status` work.
- [ ] 26.6 Kill the Mimir and Loki pods while they hold unshipped data; confirm nothing acknowledged is lost.
- [ ] 26.7 Watch backend logs for AccessDenied on delete and investigate any hit.
- [ ] 26.8 Run a real `down` on each cluster; confirm the flush report, that the tail is in S3, and that nothing is left running.
