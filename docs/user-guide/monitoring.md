# Monitoring

## Grafana Dashboards

Grafana is deployed automatically as part of the observability stack (`k8 apply`). It is accessible on port 3000 of the control node.

### System Dashboard

Shows CPU, memory, disk I/O, network I/O, and load average for all cluster nodes via OpenTelemetry metrics.

### S3 Dashboard

Shows AWS S3 bucket metrics via CloudWatch. This dashboard is available after the cluster creates its S3 bucket during `easy-db-lab up`.

**Metrics displayed:**

- **Throughput:** BytesDownloaded, BytesUploaded
- **Request Counts:** AllRequests, GetRequests, PutRequests
- **Latency:** FirstByteLatency (avg/p99), TotalRequestLatency (avg/p99)

Use the `bucket` dropdown at the top to select which S3 bucket to view.

**How it works:**

- S3 request metrics are automatically enabled on the cluster's S3 bucket during `easy-db-lab up`
- Metrics are published to CloudWatch under the `AWS/S3` namespace
- Grafana queries CloudWatch using the EC2 instance's IAM role (no credentials needed)
- During `easy-db-lab down`, the metrics configuration is automatically removed to stop CloudWatch billing
- The S3 bucket itself is preserved for data retention

**Note:** S3 request metrics take approximately 15 minutes to appear in CloudWatch after being enabled. The dashboard may show no data immediately after cluster creation.
