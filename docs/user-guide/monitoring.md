# Monitoring

## Grafana Dashboards

Grafana is deployed automatically as part of the observability stack (`k8 apply`). It is accessible on port 3000 of the control node.

### Multi-Cluster Support

All dashboards include a **Cluster** dropdown at the top that supports multi-select. You can:

- Select one or more clusters to compare metrics side by side
- Select **All** to view data from every cluster at once
- The cluster list is auto-populated from your metrics data

### Cluster Identification

When running multiple environments side by side, Grafana displays the cluster name in several places to help you identify which environment you're looking at:

- **Browser tab** - Shows the cluster name instead of "Grafana"
- **Sidebar org name** - The organization name in the sidebar shows the cluster name
- **Home dashboard** - The System Overview dashboard is set as the home page instead of the default Grafana welcome page
- **Cluster dropdown** - Every dashboard has a multi-select cluster variable for filtering

### System Dashboard

Shows CPU, memory, disk I/O, network I/O, and load average for all cluster nodes via OpenTelemetry metrics.

### AWS CloudWatch Overview

A combined dashboard showing S3, EBS, and EC2 metrics via CloudWatch. Available after running `easy-db-lab up`.

**S3 metrics:**

- **Throughput:** BytesDownloaded, BytesUploaded
- **Request Counts:** GetRequests, PutRequests
- **Latency:** FirstByteLatency (p99), TotalRequestLatency (p99)

**EBS volume metrics:**

- **IOPS:** VolumeReadOps, VolumeWriteOps (mirrored read/write chart)
- **Throughput:** VolumeReadBytes, VolumeWriteBytes (mirrored read/write chart)
- **Queue Length:** VolumeQueueLength
- **Burst Balance:** BurstBalance (percentage)

**EC2 status checks:**

- **Status Check Failures:** StatusCheckFailed_Instance, StatusCheckFailed_System (red threshold at >= 1)

Use the dropdowns at the top to select S3 bucket, EC2 instances, and EBS volumes.

**How it works:**

- The OpenTelemetry collector's `awscloudwatch` receiver polls CloudWatch API every 5 minutes for S3, EBS, and EC2 metrics
- Metrics are exported to VictoriaMetrics and queried via PromQL
- S3 request metrics are automatically enabled for the cluster's prefix in the account S3 bucket during `easy-db-lab up`
- EBS and EC2 metrics are published automatically by AWS for all instances and volumes
- Authentication uses the EC2 instance's IAM role (no credentials needed)
- During `easy-db-lab down`, the S3 metrics configuration is automatically removed to stop CloudWatch billing

**Note:** S3 request metrics take approximately 15 minutes to appear in CloudWatch after being enabled. EBS and EC2 metrics are available immediately. CloudWatch metrics are polled every 5 minutes.

### EMR Overview

Shows EMR cluster metrics via CloudWatch. Available when an EMR cluster is provisioned.

**Metrics displayed:**

- **Cluster Status:** CoreNodesRunning, CoreNodesPending, TaskNodesRunning, TaskNodesPending, HDFSUtilization
- **HDFS I/O:** HDFSBytesRead, HDFSBytesWritten (mirrored chart)
- **Applications:** AppsRunning, AppsPending, AppsCompleted, AppsFailed
- **Resources:** YARN MemoryAllocatedMB, MemoryAvailableMB, ContainerAllocated, ContainerPending
- **S3 I/O:** S3BytesRead, S3BytesWritten (mirrored chart)

Use the `EMR Cluster` dropdown to select which cluster (JobFlowId) to view.

### OpenSearch Overview

Shows OpenSearch domain metrics via CloudWatch. Available when an OpenSearch domain is provisioned.

**Metrics displayed:**

- **Cluster Health:** ClusterStatus (green/yellow/red), FreeStorageSpace
- **CPU / Memory:** CPUUtilization, JVMMemoryPressure
- **Search Performance:** SearchLatency (p99), SearchRate
- **Indexing Performance:** IndexingLatency (p99), IndexingRate
- **HTTP Responses:** 2xx, 3xx, 4xx, 5xx (color-coded)
- **Storage:** ClusterUsedSpace

Use the `Domain` dropdown to select which OpenSearch domain to view.

### Cassandra Condensed

A single-pane-of-glass summary of the most important Cassandra metrics, powered by the MAAC (Management API for Apache Cassandra) agent. Shows:

- **Cluster Overview:** Nodes up/down, compaction rates, CQL request throughput, dropped messages, connected clients, timeouts, hints, data size, GC time
- **Condensed Metrics:** Request throughput, coordinator latency percentiles, memtable space, compaction activity, table-level latency, streaming bandwidth

Requires the MAAC agent to be loaded (Cassandra 4.0, 4.1, or 5.0). Metrics are exposed on port 9000 and scraped by the OTel collector.

### Cassandra Overview

A comprehensive deep-dive into Cassandra cluster health, also powered by the MAAC agent. Shows:

- **Request Throughput:** Read/write distribution, latency percentiles (P98-P999), error throughput
- **Node Status:** Per-node up/down status (polystat panel), node count, status history
- **Data Status:** Disk space usage, data size, SSTable count, pending compactions
- **Internals:** Thread pool pending/blocked/active tasks, dropped messages, hinted handoff
- **Hardware:** CPU, memory, disk I/O, network I/O, load average
- **JVM/GC:** Application throughput, GC time, heap utilization

## eBPF Observability

The cluster deploys eBPF-based agents on all nodes for deep system observability:

### Beyla (L7 Network Metrics)

Grafana Beyla uses eBPF to automatically instrument network traffic and provide RED metrics (Rate, Errors, Duration) for:

- **Cassandra** CQL protocol (port 9042) and inter-node communication (port 7000)
- **ClickHouse** HTTP (port 8123) and native (port 9000) protocols

Metrics are scraped by the OTel collector and stored in VictoriaMetrics.

### ebpf_exporter (Low-Level Metrics)

Cloudflare's ebpf_exporter provides kernel-level metrics via eBPF:

- **TCP retransmits** — count of retransmitted TCP segments
- **Block I/O latency** — histogram of block device I/O operation latency
- **VFS latency** — histogram of filesystem read/write operation latency

These metrics are scraped by the OTel collector and stored in VictoriaMetrics.

See [Profiling](profiling.md) for continuous profiling with Pyroscope.
