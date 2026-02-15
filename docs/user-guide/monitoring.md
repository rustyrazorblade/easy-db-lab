# Monitoring

## Grafana Dashboards

Grafana is deployed automatically as part of the observability stack (`k8 apply`). It is accessible on port 3000 of the control node.

### Cluster Identification

When running multiple environments side by side, Grafana displays the cluster name in several places to help you identify which environment you're looking at:

- **Browser tab** - Shows the cluster name instead of "Grafana"
- **Dashboard titles** - Each dashboard title is prefixed with the cluster name
- **Sidebar org name** - The organization name in the sidebar shows the cluster name
- **Home dashboard** - The System Overview dashboard is set as the home page instead of the default Grafana welcome page

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

- S3 request metrics are automatically enabled for the cluster's prefix in the account S3 bucket during `easy-db-lab up`
- EBS and EC2 metrics are published automatically by AWS for all instances and volumes
- Grafana queries CloudWatch using the EC2 instance's IAM role (no credentials needed)
- During `easy-db-lab down`, the S3 metrics configuration is automatically removed to stop CloudWatch billing

**Note:** S3 request metrics take approximately 15 minutes to appear in CloudWatch after being enabled. EBS and EC2 metrics are available immediately.

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
