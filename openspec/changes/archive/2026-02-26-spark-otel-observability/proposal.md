## Why

Spark/EMR nodes currently send OTel telemetry directly to the control node's collector across the network, with no local buffering or host-level metrics. The OTEL_EXPORTER_OTLP_ENDPOINT env var override bypasses localhost, and the EMR dashboard relies entirely on YACE/CloudWatch data which adds latency and provides no node-level visibility. Pyroscope stores profiles on local disk with no backup path, and neither Tempo nor Pyroscope S3 locations are exposed via the /status endpoint.

## What Changes

- Install an OTel Collector as a systemd service on every EMR node via bootstrap action, collecting host metrics (CPU, memory, disk, network) and forwarding all OTLP telemetry to the control node's collector
- Install the Pyroscope Java agent on EMR nodes and attach it to Spark driver/executor JVMs for CPU, allocation, and lock profiling
- Remove the `OTEL_EXPORTER_OTLP_ENDPOINT` env var from Spark job submission (Java agent defaults to localhost:4317 where the local collector runs)
- Switch Pyroscope server from filesystem storage to S3 backend (matching Tempo's pattern)
- Remove the `AWS/ElasticMapReduce` job from YACE config (replaced by direct OTel collection)
- Rebuild the EMR Grafana dashboard around OTel host metrics and Spark JVM metrics instead of CloudWatch data
- Add Tempo and Pyroscope S3 paths to the `/status` endpoint

## Capabilities

### New Capabilities

- `spark-node-otel-collector`: OTel Collector installed as systemd service on EMR nodes, collecting host metrics and forwarding OTLP to control node
- `spark-profiling`: Pyroscope Java agent attached to Spark driver/executor JVMs for continuous profiling

### Modified Capabilities

- `emr-otel-bootstrap`: Bootstrap script expanded to install OTel Collector binary, config, and systemd unit, plus Pyroscope Java agent JAR
- `spark-emr`: Remove OTEL_EXPORTER_OTLP_ENDPOINT env var override from Spark job submission; add Pyroscope agent flags to extraJavaOptions
- `observability`: Switch Pyroscope to S3 storage; remove YACE EMR scraping; rebuild EMR dashboard; add Tempo/Pyroscope S3 paths to /status

## Impact

- **Bootstrap script**: `bootstrap-otel.sh` expanded significantly — installs collector binary + config + systemd unit + Pyroscope JAR
- **EMRSparkService**: Remove endpoint env var; add Pyroscope `-javaagent` flags to driver/executor options
- **Pyroscope config**: `config.yaml` switches from filesystem to S3 backend; `PyroscopeManifestBuilder` needs S3 bucket/region env vars
- **YACE config**: `yace-config.yaml` loses `AWS/ElasticMapReduce` section
- **Grafana dashboard**: `emr.json` completely rebuilt with new metric sources
- **Status endpoint**: `StatusResponse.kt` and `StatusCache.kt` updated with new S3 paths
- **OTel collector config**: New config resource for Spark nodes (simpler than control node config)
- **Constants**: New constants for Pyroscope Java agent version/path
