## 1. YACE Deployment

- [x] 1.1 Add YACE constants to `Constants.kt`: container image (`ghcr.io/nerdswords/yet-another-cloudwatch-exporter:v0.63.0`), port (5000), ConfigMap name, Deployment name
- [x] 1.2 Create `YaceManifestBuilder` in `configuration/yace/` using Fabric8. Build a ConfigMap containing the YACE config (CloudWatch namespaces: EMR, S3, EBS, EC2, OpenSearch with tag-based auto-discovery using `easy_cass_lab=1`) and a Deployment running the YACE container. Use `TemplateService` to load the YACE config YAML from a resource file with template variable substitution for region.
- [x] 1.3 Register `YaceManifestBuilder` as a factory in `ServicesModule.kt`
- [x] 1.4 Add YACE resource deployment to `GrafanaUpdateConfig.kt`: inject `YaceManifestBuilder`, call `applyFabric8Resources()` for it alongside the other builders
- [x] 1.5 Add a `yace` Prometheus scrape job to `otel-collector-config.yaml` targeting `localhost:5000/metrics` (YACE exposes Prometheus metrics on this endpoint)
- [x] 1.6 Add `YaceManifestBuilder` tests to `K8sServiceIntegrationTest.kt`: apply test, image pull test (`crictl pull`), no-resource-limits test. Add YACE deployment to `collectAllResources()`.

## 2. EMR Bootstrap Action Support

- [x] 2.1 Add `BootstrapAction` data class to `EMRTypes.kt` with fields: `name: String`, `scriptS3Path: String`, `args: List<String> = emptyList()`
- [x] 2.2 Add `bootstrapActions: List<BootstrapAction> = emptyList()` field to `EMRClusterConfig`
- [x] 2.3 Update `EMRService.createCluster()` to attach bootstrap actions from `EMRClusterConfig` to the `RunJobFlowRequest` using `bootstrapActionConfigs()`
- [x] 2.4 Add OTel Java agent version constant to `Constants.kt`: agent version (`2.25.0`), download URL, install path (`/opt/otel/opentelemetry-javaagent.jar`)
- [x] 2.5 Create a bootstrap script resource (`bootstrap-otel.sh`) that downloads the OTel Java agent JAR to `/opt/otel/opentelemetry-javaagent.jar`. Store in resources under `configuration/emr/` or similar. Use `TemplateService` for the download URL substitution.
- [x] 2.6 Update `EMRSparkService` (or `EMRService`) to upload the bootstrap script to S3 at `clusters/{name}-{id}/spark/bootstrap-otel.sh` before `createCluster()` is called, and add the bootstrap action to `EMRClusterConfig`
- [x] 2.7 Add unit tests for bootstrap action attachment in `EMRService.createCluster()` and bootstrap script upload logic

## 3. OTel Java Agent on Spark JVMs

- [x] 3.1 Update `EMRSparkService.buildSparkSubmitArgs()` to inject OTel Java agent flags into `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` (`-javaagent:/opt/otel/opentelemetry-javaagent.jar`)
- [x] 3.2 Update `EMRSparkService` to add OTel environment variables (`OTEL_LOGS_EXPORTER`, `OTEL_METRICS_EXPORTER`, `OTEL_TRACES_EXPORTER`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`) to the `envVars` map passed to `buildSparkSubmitArgs()`
- [x] 3.3 Update `EMRSparkService.queryVictoriaLogs()` to query using OTel Java agent log attributes (`service.name` matching `spark-<job-name>` and time range) instead of the defunct `step_id` tag
- [x] 3.4 Add unit tests for the updated `buildSparkSubmitArgs()` verifying OTel flags and env vars are present
- [x] 3.5 Add unit tests for the updated `queryVictoriaLogs()` verifying the correct query format

## 4. Dashboard Migration

- [x] 4.1 Rewrite `emr.json` dashboard from CloudWatch datasource to Prometheus/VictoriaMetrics datasource using YACE metric names (`aws_elasticmapreduce_*`)
- [x] 4.2 Rewrite `s3-cloudwatch.json` dashboard from CloudWatch datasource to Prometheus/VictoriaMetrics datasource using YACE metric names (`aws_s3_*`, `aws_ebs_*`, `aws_ec2_*`)
- [x] 4.3 Rewrite `opensearch.json` dashboard from CloudWatch datasource to Prometheus/VictoriaMetrics datasource using YACE metric names (`aws_es_*`)
- [x] 4.4 Remove CloudWatch Grafana datasource from `GrafanaDatasourceConfig.kt` if it is no longer needed by any dashboard

## 5. Documentation and CLAUDE.md Updates

- [x] 5.1 Update `docs/reference/log-infrastructure.md` to document YACE and Spark OTel Java agent log collection
- [x] 5.2 Update `docs/reference/opentelemetry.md` to document Spark JVM instrumentation and YACE scrape job
- [x] 5.3 Update `docs/reference/ports.md` to add YACE port (5001 — changed from 5000 to avoid conflict with Docker Registry)
- [x] 5.4 Update root `CLAUDE.md` Observability section to mention YACE and Spark OTel agent
- [x] 5.5 Update `configuration/CLAUDE.md` to document the new `yace/` subpackage

## 6. Verify

- [x] 6.1 Run `./gradlew ktlintFormat` and `./gradlew ktlintCheck`
- [x] 6.2 Run `./gradlew detekt`
- [x] 6.3 Run `./gradlew :test` (3 K3s integration test failures due to Docker-in-Docker environment constraints — VictoriaMetrics CrashLoopBackOff and OTel Collector ContainerCreating — not related to this change)
- [x] 6.4 Run `./gradlew compileKotlin`
