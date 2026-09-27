## Overrides existing behavior

### cluster-lifecycle: Cluster Teardown
**Currently:** "Data bucket cleanup MUST use lifecycle expiration" with a scenario that expires the data bucket; all per-cluster data buckets are deleted under `--all`; before teardown an automatic backup captures VictoriaMetrics and the Grafana annotations as one coupled operation, retried, aborting teardown on failure; `--force` skips it.
**This change:** Teardown MUST NOT set any lifecycle, expiry or retention rule and MUST NOT delete owner data; `down` MUST NOT accept `--retention-days`; `--all` deletes only empty data buckets.  The pre-teardown step becomes: mirror annotations to Loki, flush and verify Loki, flush and verify Mimir, back up annotations — with timeouts; any failure stops `down` with all infrastructure kept and no backend restarted, a successful flush is recorded so a re-run skips it, and there is no retry.  No VictoriaMetrics or VictoriaLogs snapshot is taken.  `--force` skips it all; redirect clusters skip it.

### grafana-annotations: Create a Grafana annotation from the CLI
**Currently:** `grafana annotate` POSTs to Grafana and emits `AnnotationCreated`.
**This change:** after Grafana accepts, the annotation is mirrored to Loki; the command exits non-zero if the mirror fails.  A backdated annotation reaches Loki.

### grafana-annotations: Global annotations render on the core dashboards
**Currently:** a tag-based Grafana annotation query on the core dashboards renders tagged global annotations.
**This change:** a Loki annotation query renders global annotations of the selected clusters; the built-in query keeps the current cluster's dashboard-scoped annotations; each renders once.

### grafana-annotations: Back up Grafana annotations to an account-level S3 location
**Currently:** the artifact goes to an account-level Grafana backup location, not a per-cluster prefix that teardown sets to expire.
**This change:** `grafana backup` first mirrors every annotation inside Loki's accepted window to Loki (one outside it is reported and kept in Grafana and the backup); the artifact goes to `observability/annotations/<tenant>/`, named `<yyyyMMdd-HHmmss>_<name>-<clusterId>.json`, and never overwrites an earlier one.

### profiling: Completed JFR chunks are shipped to Pyroscope
**Currently:** chunks are shipped labelled with hostname and cluster name; the final chunk of a stopped session is shipped "so the last interval of the run is not lost to retention".
**This change:** every upload also carries the tenant in `X-Scope-OrgID`; the final-chunk scenario no longer refers to retention.

### profiling: Local JFR retention is bounded
**Currently:** pruning applies to unshipped chunks too; unshipped chunks are pruned when Pyroscope is unreachable; losses are counted and reported.
**This change:** pruning applies only to shipped chunks; unshipped and rejected chunks are never deleted; at the size bound recording stops and is reported, and resumes when space is free.  The "lost to pruning" scenarios are removed.

### telemetry-redirect: All four signals export to the external stack under redirect
**Currently:** the listed profile producers ship externally; no tenant is sent.
**This change:** EMR Spark and the Trino and Presto kits follow the redirect; every metric, log, trace and profile write carries `X-Scope-OrgID`.

### telemetry-redirect: A redirecting cluster deploys collectors but no local backends
**Currently:** no VictoriaMetrics, VictoriaLogs, Tempo, Pyroscope server or Grafana workload on a redirect cluster.
**This change:** no Mimir, Loki, Tempo, Pyroscope server or Grafana workload.

### telemetry-redirect: Stack-dependent commands refuse cleanly on a redirect cluster
**Currently:** covers `grafana update-config`, `metrics query/backup/import/ls`, `logs query/backup/import/ls`, and the pre-teardown backup.
**This change:** covers `grafana update-config`, `logs query`, and the pre-teardown annotation mirror, flushes and annotations backup (the other commands are removed).

### observability: Grafana Dashboards
**Currently:** the renderer runs `grafana/grafana-image-renderer:latest`; "VictoriaMetrics-backed" panels are cluster-scoped; the cluster variable queries the VictoriaMetrics datasource.
**This change:** the renderer is pinned to v5.12.4; metric panels are cluster-scoped; the variable queries the Mimir datasource (uid `mimir`).

### observability: Cilium replaces Flannel as the K3s CNI
**Currently:** Hubble metrics flow into VictoriaMetrics.
**This change:** they flow into Mimir.

### observability: OTel Collector collects logs from multiple sources including K8s pods
**Currently:** logs appear in VictoriaLogs; the receiver is named `filelog/system`.
**This change:** logs are exported to Loki; the receiver is `file_log/system` (collector 0.161.0 rename).

### observability: kube-state-metrics reports Kubernetes object state
**Currently:** `kube_pod_status_phase` is queryable in VictoriaMetrics.
**This change:** it is queryable in Mimir.

### cloudwatch-metrics-export: CloudWatch metrics scraped into VictoriaMetrics → renamed "CloudWatch metrics scraped into Mimir"
**Currently:** YACE metrics are forwarded to VictoriaMetrics; they "survive teardown" through a VictoriaMetrics backup and restore.
**This change:** they are forwarded to Mimir; after `down` they are in Mimir blocks in S3 under `observabilitymetrics/<tenant>/`.

### cloudwatch-metrics-export: Grafana dashboards use VictoriaMetrics for CloudWatch data → renamed "Grafana dashboards use Mimir for CloudWatch data"
**Currently:** the EMR and S3/EBS dashboards show VictoriaMetrics data "after restoring a backup".
**This change:** they show Mimir data on a running cluster.  Reading a torn-down cluster's metrics moves to #966/#902.

### end-to-end-testing: Observability stack validation
**Currently:** checks VictoriaMetrics and VictoriaLogs health, runs the metrics and logs backups, and queries logs from VictoriaLogs.
**This change:** checks Mimir and Loki health, verifies the teardown flush put Mimir blocks and Loki index files in S3, and queries logs from Loki.

### ignite3: OTLP Metrics
**Currently:** Ignite metrics appear in VictoriaMetrics.
**This change:** they appear in Mimir.

### k8s-container-log-collection: both requirements
**Currently:** pod logs appear in VictoriaLogs; receivers named `filelog/containers`, `filelog/system`.
**This change:** Loki; receivers `file_log/containers`, `file_log/system`.

### kit-metrics-catalog: Global export script queries VictoriaMetrics for any scrape-type kit → renamed "Global export script queries Mimir for any scrape-type kit"
**Currently (as amended by the in-flight `issue-819`):** the script queries VictoriaMetrics at `:8428`.
**This change:** it queries Mimir at `:9009/prometheus` with the tenant header; the rest of issue-819's text is kept.

### kit-metrics-catalog: Grafana dashboard committed per kit, built from real catalog data
**Currently:** panels query the VictoriaMetrics datasource.
**This change:** the Mimir datasource.

### kit-metrics-declaration: Integration tests verify metrics flow after a kit starts
**Currently:** queries `http://$CONTROL_HOST_PRIVATE:8428/api/v1/series`.
**This change:** queries `:9009/prometheus/api/v1/series` with the tenant header.

### live-stream-metrics: VictoriaMetrics PromQL query capability → renamed "Mimir PromQL query capability"
**Currently:** PromQL against VictoriaMetrics `/api/v1/query` through the SOCKS proxy.
**This change:** against Mimir `/prometheus/api/v1/query` with the tenant header, through the SOCKS proxy.

### live-stream-metrics: System metrics streaming
**Currently:** the queries are not scoped to a cluster.
**This change:** they are scoped to the current cluster; empty results come from Mimir.

### multi-cluster-dashboards: all four requirements
**Currently:** the cluster variable, cluster scoping, the ad hoc filters and the ClickHouse note name VictoriaMetrics.
**This change:** they name Mimir.  Behavior is unchanged.

### networking: SOCKS Proxy Routes Only Cluster-Internal Traffic
**Currently:** the example private service is "VictoriaMetrics/VictoriaLogs on the control node".
**This change:** the example is "Mimir or Loki on the control node".  Every other word of the requirement, including the ban on the global `socksProxyHost`/`socksProxyPort` properties, is unchanged.

### server: REST Status Endpoints
**Currently:** `accessInfo.observability` has `grafana`, `victoriaMetrics`, `victoriaLogs`, `tempo`, `pyroscope`.
**This change:** `grafana`, `mimir`, `loki`, `tempo`, `pyroscope`, with the Mimir and Loki URLs.

### server: Optional Metrics Collection
**Currently:** the MetricsCollector polls VictoriaMetrics.
**This change:** it polls Mimir with the tenant, scoped to the current cluster.

### spark-emr: Spark Job Submission
**Currently:** Spark logs are in VictoriaLogs.
**This change:** in Loki, scoped to the cluster.

### tailscale-direct-connect: SOCKS proxy bypassed when Tailscale active
**Currently:** names "Victoria metrics and logs".
**This change:** names Mimir and Loki.

### tool-execution: Journal entries have proper timestamps
**Currently:** correlation in VictoriaLogs.
**This change:** in Loki.

## Conflicts with other in-flight changes

- `issue-819` (merged, waiting for archive) also modifies `kit-metrics-catalog: Global export script queries VictoriaMetrics for any scrape-type kit`.  This change renames that requirement and rewrites it starting from issue-819's text, so the two agree **only if `issue-819` is archived first**.  If this change were archived first, issue-819's MODIFIED block would target a requirement name that no longer exists.  The archive must run in order: issue-819, then issue-967.
- `issue-819` adds new capabilities and requirements whose text names VictoriaMetrics: `memcached-kit` ("memcached metrics reach VictoriaMetrics and Grafana"), `neo4j-kit` ("Neo4j metrics are pushed by the OpenTelemetry Java agent", scenario wording), and `networking` ("hostPort works on a Cilium cluster", scenario wording).  They do not exist in the baseline yet, so this change cannot modify them.  No behavior conflicts; after both archive, those scenarios should read "Mimir" in a wording sweep at archive time.
- `issue-819`'s other deltas (`dynamic-otel-scrape-config`, `kafka-kit`, `tidb`, `typed-install-steps`, `vpc-cidr-allocation`, the other `networking` requirements) touch requirements this change does not modify — no conflict.
