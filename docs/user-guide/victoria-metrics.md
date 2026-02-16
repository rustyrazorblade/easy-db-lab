# Victoria Metrics

Victoria Metrics is a time-series database that stores metrics from all nodes in your easy-db-lab cluster. It receives metrics via OTLP from the OpenTelemetry Collector.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     All Nodes (DaemonSet)                    │
├─────────────────────────────────────────────────────────────┤
│   System metrics (CPU, memory, disk, network)               │
│   Cassandra metrics (via JMX)                               │
│   Application metrics                                        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   OTel Collector       │
              │   (DaemonSet)          │
              └───────────┬────────────┘
                          │
┌─────────────────────────┼─────────────────────────┐
│   Control Node          │                          │
├─────────────────────────┼─────────────────────────┤
│                         ▼                          │
│              ┌──────────────────┐                  │
│              │ Victoria Metrics │                  │
│              │    (:8428)       │                  │
│              └────────┬─────────┘                  │
└───────────────────────┼────────────────────────────┘
                        │
                        ▼
              ┌──────────────────┐
              │     Grafana      │
              │    (:3000)       │
              └──────────────────┘
```

## Configuration

Victoria Metrics runs on the control node as a Kubernetes deployment:

- **Port**: 8428 (HTTP API)
- **Storage**: Persistent at `/mnt/db1/victoriametrics`
- **Retention**: 7 days (configurable via `-retentionPeriod` flag)

## Accessing Metrics

### Grafana

1. Access Grafana at `http://control0:3000` (via SOCKS proxy)
2. Victoria Metrics is pre-configured as the Prometheus datasource
3. System dashboards show node metrics

### Direct API

Query metrics directly using the Prometheus-compatible API:

```bash
source env.sh

# Get all metric names
with-proxy curl "http://control0:8428/api/v1/label/__name__/values"

# Query specific metric
with-proxy curl "http://control0:8428/api/v1/query?query=up"

# Query with time range
with-proxy curl "http://control0:8428/api/v1/query_range?query=node_cpu_seconds_total&start=$(date -d '1 hour ago' +%s)&end=$(date +%s)&step=60"
```

### Common Queries

```promql
# CPU usage by node
100 - (avg by(instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)

# Memory usage percentage
100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)

# Disk usage
100 - (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"} * 100)

# Network received bytes
rate(node_network_receive_bytes_total[5m])
```

## Backup

Backup Victoria Metrics data to S3:

```bash
# Backup to cluster's default S3 bucket
easy-db-lab metrics backup

# Backup to a custom S3 location
easy-db-lab metrics backup --dest s3://my-backup-bucket/victoriametrics
```

By default, backups are stored at:
`s3://{cluster-bucket}/victoriametrics/{timestamp}/`

Use `--dest` to override the destination bucket and path

### Features

- Uses native vmbackup tool with snapshot support
- Non-disruptive; metrics collection continues during backup
- Direct S3 upload (no intermediate storage needed)
- Incremental backup support for faster subsequent backups

## Listing Backups

List available VictoriaMetrics backups in S3:

```bash
easy-db-lab metrics ls
```

This displays a summary table of all backups grouped by timestamp, showing the number of files and total size for each.

## Importing Metrics to an External Instance

Stream metrics from the running cluster's VictoriaMetrics to an external VictoriaMetrics instance via the native export/import API:

```bash
# Import all metrics
easy-db-lab metrics import --target http://victoria:8428

# Import only specific metrics
easy-db-lab metrics import --target http://victoria:8428 --match '{job="cassandra"}'
```

This is useful for exporting metrics at the end of test runs when running easy-db-lab from a Docker container. Unlike binary backups, this approach streams data via HTTP and can target any reachable VictoriaMetrics instance.

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--target` | Target VictoriaMetrics URL (required) | - |
| `--match` | Metric selector for filtering | All metrics |

## Troubleshooting

### No metrics appearing

1. Verify Victoria Metrics pod is running:
   ```bash
   kubectl get pods -l app.kubernetes.io/name=victoriametrics
   kubectl logs -l app.kubernetes.io/name=victoriametrics
   ```

2. Check OTel Collector is forwarding metrics:
   ```bash
   kubectl get pods -l app=otel-collector
   kubectl logs -l app=otel-collector
   ```

3. Verify the cluster-config ConfigMap exists:
   ```bash
   kubectl get configmap cluster-config -o yaml
   ```

### Connection errors

If you see connection errors when querying metrics:

1. Ensure the cluster is running: `easy-db-lab status`
2. The proxy is started automatically when needed
3. Check that control node is accessible: `ssh control0 hostname`

### High memory usage

Victoria Metrics is configured with memory limits. If you see OOM kills:

1. Check current memory usage:
   ```bash
   kubectl top pod -l app.kubernetes.io/name=victoriametrics
   ```

2. Consider adjusting the memory limits in the deployment manifest

### Backup failures

If backup fails:

1. Check the backup job logs:
   ```bash
   kubectl logs -l app.kubernetes.io/name=victoriametrics-backup
   ```

2. Verify S3 bucket permissions (IAM role should have S3 access)

3. Ensure there's sufficient disk space on the control node
