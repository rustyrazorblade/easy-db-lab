## Context

After removing SQS and Vector, EMR Spark clusters have no real-time log collection and CloudWatch metrics are only accessible live via Grafana's CloudWatch datasource. When clusters are torn down, all CloudWatch metrics and Spark logs are lost. The existing S3 log download in `EMRSparkService` works for post-mortem on failures but produces unsearchable local files.

The control node already runs a full observability stack: OTel Collector (DaemonSet, ports 4317/4318), VictoriaMetrics (8428), VictoriaLogs (9428), Tempo (3200). EMR nodes are in the same VPC and can reach the control node directly.

## Goals / Non-Goals

**Goals:**
- CloudWatch metrics (EMR, S3, EBS, EC2, OpenSearch) stored in VictoriaMetrics and available after teardown
- Spark driver/executor logs sent in real-time to VictoriaLogs, searchable and correlated with other data
- Spark JVM metrics and traces collected alongside logs
- Grafana dashboards work against VictoriaMetrics after teardown (no CloudWatch datasource dependency)

**Non-Goals:**
- Running otelcol-contrib on EMR nodes (future hardening, not this change)
- Collecting YARN daemon logs or Hadoop system logs from EMR nodes
- Real-time CloudWatch metrics (YACE polls at intervals, not sub-second)
- Removing the S3 log download fallback (keep it as insurance)

## Decisions

### 1. YACE for CloudWatch metrics

**Choice:** Deploy YACE (Yet Another CloudWatch Exporter) v0.63.0 as a K8s Deployment on the control node. OTel Collector scrapes its Prometheus endpoint.

**Alternatives considered:**
- *OTel `awscloudwatchreceiver`* — only supports logs, not metrics. A metrics receiver was proposed (issue #15667) but closed with no maintainer.
- *CloudWatch Metric Streams* — push-based via Kinesis Firehose in OTLP format. Heavy infrastructure (Firehose + HTTP endpoint) for what we need.
- *Custom scraper* — YACE already exists, is mature, and is maintained by the Prometheus community.

**Implementation:** New `YaceManifestBuilder` (Fabric8) creates a ConfigMap with the YACE config and a Deployment. The config specifies which CloudWatch namespaces/metrics to scrape, derived from the existing Grafana dashboard metric definitions. OTel collector config gets a new `yace` Prometheus scrape job. YACE uses the control node's IAM role for CloudWatch API access.

**Container image:** `ghcr.io/nerdswords/yet-another-cloudwatch-exporter:v0.63.0`

### 2. OTel Java agent for Spark logs, metrics, and traces

**Choice:** Attach the OTel Java agent (v2.25.0) to Spark driver and executor JVMs via `-javaagent`. The agent auto-instruments Log4j2 at the bytecode level, capturing all log output as OTLP log records. It also collects JVM metrics and traces. All telemetry is sent directly to the control node's OTel Collector at `<control_private_ip>:4317`.

**Alternatives considered:**
- *Log4j2 OTLP Appender (library)* — requires calling `OpenTelemetryAppender.install(sdk)` during application startup. Not feasible for Spark since we don't control Spark's initialization. The OTel maintainers closed a request to make this work from config alone (issue #12468).
- *otelcol-contrib on EMR nodes with filelog receiver* — more robust but adds complexity. Noted as future hardening option. The current approach sends directly to the control node over the VPC network.
- *EMR configuration classifications (`spark-driver-log4j2`)* — only sets log4j2 properties, doesn't help without the `install()` call that the library appender requires.

**Implementation:** A bootstrap action script downloads the agent JAR from GitHub releases to `/opt/otel/opentelemetry-javaagent.jar`. The bootstrap script is uploaded to the cluster's S3 bucket before EMR creation. `EMRSparkService.buildSparkSubmitArgs()` injects the agent and OTel configuration:

```
spark.driver.extraJavaOptions=-javaagent:/opt/otel/opentelemetry-javaagent.jar
spark.executor.extraJavaOptions=-javaagent:/opt/otel/opentelemetry-javaagent.jar
```

Environment variables (via the existing `envVars` parameter):
```
OTEL_LOGS_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_TRACES_EXPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://<control_private_ip>:4317
OTEL_SERVICE_NAME=spark-<job-name>
```

**Agent JAR:** OTel Java Agent v2.25.0, downloaded via `https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.25.0/opentelemetry-javaagent.jar`

### 3. Bootstrap action delivery

**Choice:** Upload a shell script to the cluster's S3 bucket (`clusters/{name}-{id}/spark/bootstrap-otel.sh`) before calling `EMRService.createCluster()`. Pass the S3 URI as a bootstrap action in `EMRClusterConfig`.

**Implementation:** `EMRClusterConfig` gets a new `bootstrapActions: List<BootstrapAction>` field (each has `name`, `scriptS3Path`, `args`). `EMRService.createCluster()` attaches them to the `RunJobFlowRequest`. The bootstrap script is a simple `curl | install` that downloads the agent JAR.

### 4. Dashboard migration strategy

**Choice:** Rewrite EMR, S3/EBS, and OpenSearch dashboard JSON files to query VictoriaMetrics (Prometheus datasource) using YACE metric names instead of CloudWatch datasource queries.

YACE metric names follow the pattern `aws_<service>_<metric>` (e.g., `aws_elasticmapreduce_apps_running_average`, `aws_s3_bytes_downloaded_sum`). Dashboard queries change from CloudWatch `metricName/namespace/dimensions` to PromQL against these metric names with label selectors.

The CloudWatch Grafana datasource can be removed after migration.

### 5. Fix Victoria Logs query for Spark logs

**Choice:** Update `EMRSparkService.queryVictoriaLogs()` to query using attributes set by the OTel Java agent. The agent tags log records with `service.name`, `telemetry.sdk.language`, etc. The query changes from `step_id:{stepId}` to a query matching the Spark service name and time range.

## Risks / Trade-offs

- **[Network reliability]** Spark JVMs send OTLP directly to control node over VPC. If the control node's OTel collector is down or the network path is congested, log/metric delivery fails silently (the Java agent is non-blocking by default). → Mitigation: The S3 log download path remains as fallback. Future hardening: add local otelcol on EMR nodes as a buffer.

- **[Bootstrap action adds startup time]** Downloading the ~20MB agent JAR adds time to EMR cluster bootstrap. → Mitigation: Pre-stage the JAR in S3 during cluster setup (same bucket, fast download within AWS).

- **[YACE CloudWatch API costs]** YACE polls CloudWatch API. Each `GetMetricData` call has a cost. With ~48 metrics across 5 namespaces at 60s intervals, this is roughly 48 API calls/minute. → Mitigation: Use 300s poll interval for less critical metrics (S3 storage size, EBS). CloudWatch API costs are negligible at this scale.

- **[Dashboard migration effort]** Rewriting 3 dashboards from CloudWatch to PromQL is manual and error-prone. → Mitigation: YACE metric naming is predictable. Test dashboards with a running cluster before merging.

- **[YACE needs cluster-specific dimensions]** EMR JobFlowId, S3 BucketName, EBS VolumeId, OpenSearch DomainName are dynamic. → Mitigation: YACE supports tag-based auto-discovery. EMR clusters are tagged with `easy_cass_lab=1`. S3/EBS/EC2 resources can be discovered similarly.

## Open Questions

- What YACE scrape interval should we use? 60s matches most CloudWatch metric periods. Some metrics (S3 BucketSizeBytes) only update daily.
- Should the OTel Java agent be pre-staged in S3 during `spark init` or downloaded from GitHub in the bootstrap script? Pre-staging avoids external dependency but requires upload logic.
