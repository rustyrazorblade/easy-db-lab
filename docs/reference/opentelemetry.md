# OpenTelemetry Instrumentation

easy-db-lab includes optional OpenTelemetry (OTel) instrumentation for distributed tracing and metrics. When enabled, traces and metrics are exported to an OTLP-compatible collector.

## Enabling OpenTelemetry

Set the `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable to your OTLP collector endpoint:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
easy-db-lab up
```

When this environment variable is:
- **Set**: Traces and metrics are exported via gRPC to the specified endpoint
- **Not set**: OpenTelemetry is completely disabled with zero overhead

## Instrumented Operations

### Commands

All CLI commands are automatically instrumented with:
- A span for each command execution
- Duration metrics
- Command count metrics
- Success/failure attributes

### SSH Operations

Remote SSH operations are instrumented including:
- Command execution (`ssh.execute`)
- File uploads (`ssh.upload`, `ssh.upload_directory`)
- File downloads (`ssh.download`, `ssh.download_directory`)

Span attributes include host alias, target IP, and (for non-secret commands) the command text.

### Kubernetes Operations

K8s operations via the fabric8 client are instrumented:
- Manifest application (`k8s.apply_manifests`, `k8s.apply_yaml`)
- Namespace deletion (`k8s.delete_namespace`)
- StatefulSet scaling (`k8s.scale_statefulset`)

### AWS SDK Calls

When OTel is enabled, AWS SDK calls are automatically instrumented using the OpenTelemetry AWS SDK instrumentation library. This includes:
- EC2 operations
- S3 operations
- IAM operations
- EMR operations
- STS operations
- OpenSearch operations

## Resource Attributes

Traces include the following resource attributes:
- `service.name`: `easy-db-lab`
- `service.version`: Application version
- `host.name`: Local hostname

## Metrics

The following metrics are exported:

| Metric | Type | Description |
|--------|------|-------------|
| `easydblab.command.duration` | Histogram | Command execution duration (ms) |
| `easydblab.command.count` | Counter | Number of commands executed |
| `easydblab.ssh.operation.duration` | Histogram | SSH operation duration (ms) |
| `easydblab.ssh.operation.count` | Counter | Number of SSH operations |
| `easydblab.k8s.operation.duration` | Histogram | K8s operation duration (ms) |
| `easydblab.k8s.operation.count` | Counter | Number of K8s operations |

## Stress Job Metrics

When running cassandra-easy-stress as K8s Jobs, metrics are automatically collected via an OTel collector sidecar container. The sidecar scrapes the stress process's Prometheus endpoint (`localhost:9500`) and forwards metrics via OTLP to the node's OTel DaemonSet, which then exports them to VictoriaMetrics.

The Prometheus scrape job is named `cassandra-easy-stress`. The following labels are available in Grafana:

| Label | Source | Description |
|-------|--------|-------------|
| `host_name` | DaemonSet `resourcedetection` processor | K8s node name where the pod runs |
| `instance` | Sidecar `relabel_configs` | Node name with port (e.g., `ip-10-0-1-50:9500`) |
| `cluster` | Sidecar `relabel_configs` | Cluster name from `cluster-config` ConfigMap |

Short-lived stress commands (`list`, `info`, `fields`) do not include the sidecar since they complete quickly and don't produce meaningful metrics.

## Spark JVM Instrumentation

EMR Spark jobs are auto-instrumented with the OpenTelemetry Java Agent (v2.25.0) and Pyroscope Java Agent (v2.3.0), both installed via an EMR bootstrap action. The OTel agent is activated through `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions`.

Each EMR node also runs an OTel Collector as a systemd service, collecting host metrics (CPU, memory, disk, network) and receiving OTLP from the Java agents. The collector forwards all telemetry to the control node's OTel Collector via OTLP gRPC.

Key configuration:
- **OTel Agent JAR**: Downloaded by bootstrap action to `/opt/otel/opentelemetry-javaagent.jar`
- **Pyroscope Agent JAR**: Downloaded by bootstrap action to `/opt/pyroscope/pyroscope.jar`
- **OTel Collector**: Installed at `/opt/otel/otelcol-contrib`, runs as `otel-collector.service`
- **Export protocol**: OTLP/gRPC to `localhost:4317` (local collector), which forwards to control node
- **Logs exporter**: OTLP (captures JVM log output)
- **Service name**: `spark-<job-name>` (set per job)
- **Profiling**: CPU, allocation (512k threshold), lock (10ms threshold) profiles in JFR format sent to Pyroscope server

## YACE CloudWatch Scrape

YACE (Yet Another CloudWatch Exporter) runs on the control node and scrapes AWS CloudWatch metrics for services used by the cluster. It uses tag-based auto-discovery with the `easy_cass_lab=1` tag to find relevant resources.

YACE scrapes metrics for:
- **S3** — bucket request/byte counts
- **EBS** — volume read/write ops and latency
- **EC2** — instance CPU, network, disk
- **OpenSearch** — domain health, indexing, search metrics

EMR metrics are collected directly via OTel Collectors on Spark nodes (see Spark JVM Instrumentation above).

YACE exposes scraped metrics as Prometheus-compatible metrics on port 5001, which are then scraped by the OTel Collector and forwarded to VictoriaMetrics. This replaces the previous CloudWatch datasource in Grafana with a Prometheus-based approach, giving dashboards access to CloudWatch metrics through VictoriaMetrics queries.

## Configuration

The following environment variables are supported:

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint | None (OTel disabled) |

Additional standard OTel environment variables may work depending on the SDK defaults.

## Example: Using with Jaeger

Start Jaeger with OTLP support:

```bash
docker run -d --name jaeger \
  -p 16686:16686 \
  -p 4317:4317 \
  jaegertracing/all-in-one:latest
```

Export traces to Jaeger:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
easy-db-lab up
```

View traces at http://localhost:16686

## Example: Using with Grafana Tempo

If you have Grafana Tempo running with OTLP gRPC ingestion:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317
easy-db-lab up
```

## Troubleshooting

### No Traces Appearing

1. Verify the endpoint is correct and reachable
2. Check that the collector accepts gRPC OTLP (port 4317 is standard)
3. Look for OpenTelemetry initialization logs on startup

### High Latency

Traces are batched before export (default 1 second delay). This is normal and reduces overhead.

### Shutdown Warnings

A shutdown hook flushes remaining telemetry on exit. Brief delays during shutdown are expected.
