## Why

EMR Spark clusters produce metrics and logs that are lost on teardown. CloudWatch metrics (EMR, S3, EBS, EC2, OpenSearch) are only accessible via Grafana's live CloudWatch datasource — once infrastructure is torn down, that data disappears. Spark driver/executor logs have no real-time collection path after the SQS/Vector removal; the only remaining mechanism is post-hoc S3 download on job failure, which isn't searchable or correlated with other observability data.

For a tool designed to run hundreds of test iterations with post-teardown analysis, all observability data must be archived in Victoria before teardown.

## What Changes

- **Add YACE (Yet Another CloudWatch Exporter)** as a K8s deployment on the control node, scraping CloudWatch metrics for EMR, S3, EBS, EC2, and OpenSearch namespaces. OTel collector scrapes YACE's Prometheus endpoint, forwarding to VictoriaMetrics.
- **Add EMR bootstrap action** that downloads the OTel Java agent JAR to `/opt/otel/` on every EMR node. This is a single small shell script uploading to S3 before cluster creation.
- **Attach OTel Java agent to Spark JVMs** via `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` on spark-submit. The agent auto-instruments Log4j2 at the bytecode level (no appender config or code changes), sending logs via OTLP directly to the control node's OTel collector at port 4317. Also provides JVM metrics (heap, GC, threads) and traces.
- **Fix `queryVictoriaLogs()` in EMRSparkService** to query the correct log attributes produced by the OTel Java agent instead of the defunct Vector `step_id` tags.
- **Update EMRClusterConfig and EMRService** to support bootstrap actions (script S3 path + args).
- **Update EMRSparkService** to inject OTel Java agent flags and environment variables into spark-submit conf.
- **Migrate Grafana CloudWatch dashboards** (EMR, S3/EBS, OpenSearch) from CloudWatch datasource to VictoriaMetrics (Prometheus) queries against YACE-scraped metrics, so dashboards work after teardown.

**Future hardening (not in scope):** If the direct-to-control-node OTLP path proves unreliable at scale, an otelcol-contrib instance can be added to EMR nodes via the bootstrap action as a local relay. The Java agent would then point at localhost:4317 instead of the control node. No other changes needed.

## Capabilities

### New Capabilities

- `cloudwatch-metrics-export`: YACE deployment on control node scraping CloudWatch metrics into VictoriaMetrics via OTel. Covers EMR, S3, EBS, EC2, and OpenSearch namespaces.
- `emr-otel-bootstrap`: Bootstrap action for EMR clusters that installs the OTel Java agent JAR, enabling real-time log, metric, and trace collection from Spark driver and executor JVMs sent directly to the control node's OTel collector.

### Modified Capabilities

- `observability`: REQ-OB-001 (metrics) expands to include CloudWatch-sourced metrics via YACE. REQ-OB-002 (logs) expands to include Spark/EMR logs via OTel Java agent auto-instrumentation of Log4j2.
- `spark-emr`: REQ-SE-002 (job submission) changes to include OTel Java agent attachment on Spark JVMs and bootstrap action configuration on EMR cluster creation.

## Impact

- **New K8s manifest builder**: `YaceManifestBuilder` in `configuration/yace/` — ConfigMap + Deployment.
- **New bootstrap resources**: Bootstrap shell script and OTel Java agent JAR version constant.
- **Modified files**: `EMRClusterConfig` (bootstrap actions field), `EMRService.createCluster()` (attach bootstrap actions), `EMRSparkService` (inject OTel Java agent conf + fix Victoria Logs query), `GrafanaUpdateConfig` (deploy YACE), `OtelManifestBuilder` or otel-collector-config.yaml (YACE scrape job), `AWSModule.kt` or `ServicesModule.kt` (YACE builder registration).
- **Dashboard migration**: EMR, S3/EBS, and OpenSearch Grafana dashboard JSON files change from CloudWatch datasource to Prometheus/VictoriaMetrics datasource queries against YACE metric names.
- **IAM**: EMR EC2 role needs S3 read access for bootstrap script path.
- **Dependencies**: YACE container image (`ghcr.io/nerdswords/yet-another-cloudwatch-exporter`). OTel Java agent JAR (downloaded by bootstrap from GitHub releases or pre-staged in S3).
- **Tests**: YaceManifestBuilder integration test in K8sServiceIntegrationTest. Bootstrap script generation unit tests. Updated EMR service tests for bootstrap action and spark-submit conf injection.
- **Docs**: Update observability docs, EMR/Spark docs, log-infrastructure reference.
