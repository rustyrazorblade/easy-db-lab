## 1. Constants and Configuration Groundwork

- [x] 1.1 Add Pyroscope Java agent constants to `Constants.kt` (version, download URL, install path `/opt/pyroscope/pyroscope.jar`)
- [x] 1.2 Add OTel Collector contrib constants to `Constants.kt` (version, download URL, install path `/opt/otel/otelcol-contrib`)
- [x] 1.3 Create OTel Collector config resource for Spark nodes at `src/main/resources/.../configuration/emr/otel-collector-config.yaml` (OTLP receivers, host metrics, batch processor, OTLP gRPC exporter with `__CONTROL_NODE_IP__` placeholder)

## 2. Bootstrap Script Expansion

- [x] 2.1 Update `bootstrap-otel.sh` to accept control node IP as a script argument
- [x] 2.2 Add OTel Collector binary download and installation to `bootstrap-otel.sh`
- [x] 2.3 Add Pyroscope Java agent download and installation to `bootstrap-otel.sh`
- [x] 2.4 Add OTel Collector config file generation to `bootstrap-otel.sh` (substitute control node IP into config)
- [x] 2.5 Add systemd unit creation and service start for `otel-collector.service` in `bootstrap-otel.sh`
- [x] 2.6 Update `EMRProvisioningService` to pass control node IP as bootstrap action argument

## 3. Spark Job Submission Changes

- [x] 3.1 Remove `OTEL_EXPORTER_OTLP_ENDPOINT` from `EMRSparkService.buildOtelEnvVars()` (keep the other four vars)
- [x] 3.2 Add Pyroscope agent flags to `EMRSparkService.buildOtelSparkConf()` — add `-javaagent:/opt/pyroscope/pyroscope.jar` with system properties to driver and executor `extraJavaOptions`
- [x] 3.3 Update tests for `EMRSparkService` to verify endpoint env var is removed and Pyroscope flags are present

## 4. Pyroscope S3 Storage

- [x] 4.1 Update `src/main/resources/.../configuration/pyroscope/config.yaml` to use S3 backend with `${S3_BUCKET}` and `${AWS_REGION}` env expansion (replace filesystem backend)
- [x] 4.2 Update `PyroscopeManifestBuilder` to inject `S3_BUCKET` and `AWS_REGION` env vars from cluster-config ConfigMap into the Pyroscope server container
- [x] 4.3 Remove hostPath volume for `/mnt/db1/pyroscope` from `PyroscopeManifestBuilder`
- [x] 4.4 Update K8sServiceIntegrationTest for Pyroscope manifest changes (apply test, no-resource-limits test)

## 5. YACE EMR Removal

- [x] 5.1 Remove the `AWS/ElasticMapReduce` job section from `yace-config.yaml`

## 6. EMR Dashboard Rebuild

- [x] 6.1 Replace `emr.json` Grafana dashboard with new panels based on OTel host metrics (CPU, memory, disk, network per EMR node)
- [x] 6.2 Add Spark JVM metrics panels (heap usage, GC activity, threads) from OTel Java agent
- [x] 6.3 Update dashboard tags and variables (remove YACE/CloudWatch references, add hostname filter)

## 7. Status Endpoint

- [x] 7.1 Add `tempo` and `pyroscope` fields to `S3Paths` data class in `StatusResponse.kt`
- [x] 7.2 Update `StatusCache` to populate Tempo path (`tempo/` prefix in data bucket) and Pyroscope path from cluster state
- [x] 7.3 Add `ClusterS3Path` helper methods for Tempo and Pyroscope paths if needed

## 8. Documentation and CLAUDE.md Updates

- [x] 8.1 Update `configuration/CLAUDE.md` Pyroscope section to reflect S3 storage backend
- [x] 8.2 Update observability section in root `CLAUDE.md` to document Spark node OTel collector and Pyroscope profiling
- [x] 8.3 Update `docs/` user documentation for Spark observability changes
- [ ] 8.4 Update `emr-otel-bootstrap` spec in `openspec/specs/` after archiving
