# OpenTelemetry Instrumentation

easy-db-lab includes optional OpenTelemetry (OTel) instrumentation for distributed tracing and metrics. When enabled, traces and metrics are exported to an OTLP-compatible collector.

## CLI Tool Instrumentation

The easy-db-lab CLI tool runs with the OpenTelemetry Java Agent, which automatically instruments:

- **AWS SDK calls** - EC2, S3, IAM, EMR, STS, OpenSearch operations
- **HTTP clients** - OkHttp and other HTTP libraries
- **JDBC/Cassandra driver** - Database operations
- **JVM metrics** - Memory, threads, garbage collection

### Enabling Instrumentation

Set the `OTEL_EXPORTER_OTLP_ENDPOINT` environment variable to your OTLP collector endpoint:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
easy-db-lab up
```

When this environment variable is:
- **Set**: Traces and metrics are exported via gRPC to the specified endpoint
- **Not set**: The agent is still loaded but no telemetry is exported (minimal overhead)

The agent uses automatic instrumentation only - there is no custom manual instrumentation in the CLI tool code.

## Cluster Node Instrumentation

The following instrumentation applies to cluster nodes (Cassandra, stress, Spark) and is separate from the CLI tool:

### Node Role Labeling

The OTel Collector on cluster nodes uses the `k8sattributes` processor to read the K8s node label `type` and set it as the `node_role` resource attribute. This label is used by Grafana dashboards (e.g., System Overview) for hostname and service filtering.

| Node Type | K8s Label | `node_role` Value | Source |
|-----------|-----------|-------------------|--------|
| Cassandra | `type=db` | `db` | K3s agent config |
| Stress | `type=app` | `app` | K3s agent config |
| Control | `type=control` | `control` | `Up` command node labeling |
| Spark/EMR | N/A | `spark` | EMR OTel Collector `resource/role` processor |

The `k8sattributes` processor runs in the `metrics/local` and `logs/local` pipelines only. Remote metrics arriving via OTLP (e.g., from Spark nodes) already carry `node_role` and are not modified.

The processor requires RBAC access to the K8s API. The OTel Collector DaemonSet runs with a dedicated ServiceAccount (`otel-collector`) that has read-only access to pods and nodes.

### Stress Job Metrics

When running cassandra-easy-stress as K8s Jobs, metrics are automatically collected via an OTel collector sidecar container. The sidecar scrapes the stress process's Prometheus endpoint (`localhost:9500`) and forwards metrics via OTLP to the node's OTel DaemonSet, which then exports them to VictoriaMetrics.

The Prometheus scrape job is named `cassandra-easy-stress`. The following labels are available in Grafana:

| Label | Source | Description |
|-------|--------|-------------|
| `host_name` | DaemonSet `resourcedetection` processor | K8s node name where the pod runs |
| `instance` | Sidecar `relabel_configs` | Node name with port (e.g., `ip-10-0-1-50:9500`) |
| `cluster` | Sidecar `relabel_configs` | Cluster name from `cluster-config` ConfigMap |

Short-lived stress commands (`list`, `info`, `fields`) do not include the sidecar since they complete quickly and don't produce meaningful metrics.

### Spark JVM Instrumentation

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

### Cassandra Sidecar Instrumentation

The Cassandra Sidecar process is instrumented with the OpenTelemetry Java Agent and Pyroscope Java Agent, matching the pattern used for Cassandra itself. Both agents are loaded via `-javaagent` flags set in `/etc/default/cassandra-sidecar`, which is written by the `setup-instances` command.

Key configuration:
- **OTel Agent JAR**: Installed by Packer to `/usr/local/otel/opentelemetry-javaagent.jar`
- **Pyroscope Agent JAR**: Installed by Packer to `/usr/local/pyroscope/pyroscope.jar`
- **Service name**: `cassandra-sidecar` (both OTel and Pyroscope)
- **Export endpoint**: `localhost:4317` (local OTel Collector DaemonSet)
- **Profiling**: CPU, allocation (512k threshold), lock (10ms threshold) profiles sent to Pyroscope server
- **Activation**: Gated on `/etc/default/cassandra-sidecar` — the systemd `EnvironmentFile=-` directive makes it optional, so the sidecar starts normally without instrumentation if the file doesn't exist

### Tool Runner Log Collection

Commands run via `exec run` are executed through `systemd-run`, which captures stdout and stderr to log files under `/var/log/easydblab/tools/`. The OTel Collector's `filelog/tools` receiver watches this directory and ships log entries to VictoriaLogs with the attribute `source: tool-runner`.

This provides automatic log capture for ad-hoc debugging tools (e.g., `inotifywait`, `tcpdump`, `strace`) run during investigations. Logs are queryable in VictoriaLogs and preserved in S3 backups via `logs backup`.

Key details:
- **Log directory**: `/var/log/easydblab/tools/`
- **Source attribute**: `tool-runner` (for filtering in VictoriaLogs queries)
- **Foreground commands**: Output displayed after completion, also logged to file
- **Background commands** (`--bg`): Output logged to file only, tool runs as a systemd transient unit

### YACE CloudWatch Scrape

YACE (Yet Another CloudWatch Exporter) runs on the control node and scrapes AWS CloudWatch metrics for services used by the cluster. It uses tag-based auto-discovery with the `easy_cass_lab=1` tag to find relevant resources.

YACE scrapes metrics for:
- **S3** — bucket request/byte counts
- **EBS** — volume read/write ops and latency
- **EC2** — instance CPU, network, disk
- **OpenSearch** — domain health, indexing, search metrics

EMR metrics are collected directly via OTel Collectors on Spark nodes (see Spark JVM Instrumentation above).

YACE exposes scraped metrics as Prometheus-compatible metrics on port 5001, which are then scraped by the OTel Collector and forwarded to VictoriaMetrics. This replaces the previous CloudWatch datasource in Grafana with a Prometheus-based approach, giving dashboards access to CloudWatch metrics through VictoriaMetrics queries.

## Resource Attributes

Traces from the CLI tool and cluster nodes include the following resource attributes:
- `service.name`: Service identifier (e.g., `easy-db-lab`, `cassandra-sidecar`, `spark-<job-name>`)
- `service.version`: Application version (CLI tool only)
- `host.name`: Hostname

## Configuration

The following environment variables are supported:

| Variable | Description | Default |
|----------|-------------|---------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP gRPC endpoint | None (no export) |
| `OTEL_SERVICE_NAME` | Override service name | `easy-db-lab` |
| `OTEL_RESOURCE_ATTRIBUTES` | Additional resource attributes | None |

Additional standard OTel environment variables are supported by the agent. See the [OpenTelemetry Java Agent documentation](https://opentelemetry.io/docs/instrumentation/java/automatic/agent-config/) for details.

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
3. Look for OpenTelemetry agent logs on startup (use `-Dotel.javaagent.debug=true` to enable debug logging)

### High Latency

Traces are batched before export (default 1 second delay). This is normal and reduces overhead.
