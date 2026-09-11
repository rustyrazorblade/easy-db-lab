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

Shows Spark/EMR node metrics via OpenTelemetry. Available when an EMR cluster is provisioned. Each EMR node runs an OTel Collector that collects host metrics and receives JVM telemetry from the OTel and Pyroscope Java agents.

**Host Metrics:**

- **CPU Usage:** Per-node CPU utilization percentage
- **Memory Usage:** Used and cached memory per node
- **Disk I/O:** Read/write throughput per node (mirrored chart)
- **Network I/O:** Receive/transmit throughput per node (mirrored chart)
- **Load Average:** 1m and 5m load per node
- **Filesystem Usage:** Root filesystem utilization percentage

**Spark JVM Metrics:**

- **JVM Heap Memory:** Used and committed heap per node/pool
- **GC Duration Rate:** Garbage collection duration rate per collector
- **JVM Threads:** Thread count per node
- **JVM Classes Loaded:** Class count per node

Use the `Hostname` dropdown to filter by specific EMR nodes.

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

### Cassandra Overview

A deep-dive into Cassandra cluster health. Shows:

- **Cluster Overview:** Request throughput, errors by status, read and write latency spread across nodes, pending compactions, active tasks by pool
- **Hardware / Operating System:** CPU, load average, memory, disk throughput, network I/O
- **Per-Node Latency:** Read and write latency per node
- **Data Status:** Data size, SSTable count, compaction backlog against completion rate, SSTables per read
- **Cassandra Internals:** Thread pool pending and blocked tasks, dropped messages, hinted handoff
- **JVM / Garbage Collection:** Application throughput, GC time and pause percentiles, heap memory
- **Storage & Capacity:** Live against total disk space per table, storage growth rate per node

The metrics come from the OpenTelemetry Java Agent, which runs inside the Cassandra JVM and reads
Cassandra's own MBeans. It exports over OTLP to the node's OTel collector on port 4318, so no
Prometheus scrape port is involved. One agent serves every Cassandra release, and it needs no
per-release selection: a version installed with `cassandra install --url` or `--branch` reports the
same metrics as a stock release.

Series carry the Prometheus label `job="cassandra"`, and each node also reports a `cassandra_build`
label naming the build it is running. That label is what lets a dashboard compare two versions in
one cluster. See [OpenTelemetry](../reference/opentelemetry.md) for the metric names, the rule file,
and how a node derives its own labels.

If the agent jar or the rule file is missing, Cassandra says so on stderr at startup — check
`journalctl -u cassandra` on the node when a dashboard is empty.

Latency arrives as pre-aggregated percentile gauges read from Cassandra's own `EstimatedHistogram`,
the same reservoir `nodetool proxyhistograms` prints. `histogram_quantile()` does not apply to them,
and they cannot be re-aggregated into a cluster-wide percentile.

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

## Redirecting Telemetry to an External Stack

By default each cluster stands up its own local observability backends on the control node: VictoriaMetrics, VictoriaLogs, Tempo, the Pyroscope server, and Grafana.  Redirect mode ships all telemetry to an external observability stack instead.  A redirect cluster stands up no local backends and no local Grafana.

Redirect mode is useful when you run several clusters and want one place to view them all.  A common case is a second data center that reports into the first data center's stack.

### Enabling Redirect Mode

Set the redirect target once, at `init`, with `--redirect-telemetry <host>`:

```bash
easy-db-lab init --redirect-telemetry 10.0.0.9 --up
```

`<host>` is the hostname or IP of the external stack.  The tool derives the four signal endpoints from that host and the known stack ports:

- Metrics: `http://<host>:8428/api/v1/write` (VictoriaMetrics remote-write)
- Logs: `http://<host>:9428/insert/opentelemetry` (VictoriaLogs OTLP ingest)
- Traces: `<host>:4320` (Tempo OTLP gRPC receiver)
- Profiles: `http://<host>:4040` (Pyroscope ingest)

The traces endpoint uses the OTLP receiver port 4320, not Tempo's query port 3200.

```admonish info
Redirect mode is a life-of-cluster choice made at `init`.  All four signals move together; you cannot redirect one signal and keep the rest local.  You cannot switch a running cluster between local and redirect mode.
```

### Overriding Individual Endpoints

If the external stack does not use the easy-db-lab port layout, override any signal.  An override wins over the derived value; the other signals still derive from the base host.

```bash
easy-db-lab init --redirect-telemetry 10.0.0.9 \
  --redirect-metrics-endpoint http://metrics.example.com:8428/api/v1/write \
  --redirect-traces-endpoint tempo.example.com:4320 \
  --up
```

The four override options are `--redirect-metrics-endpoint`, `--redirect-logs-endpoint`, `--redirect-traces-endpoint`, and `--redirect-profiles-endpoint`.

### Origin Identity

Every signal carries the source cluster's name as its origin identifier.  Metrics and traces carry a `cluster` label; logs carry a `cluster` field; profiles carry a `cluster` label.  Use that identifier to tell one cluster's telemetry from another on the shared stack.

### Commands That Need the Local Stack

A redirect cluster has no local backends or Grafana to act on.  These commands refuse to run on a redirect cluster and tell you why:

- `grafana update-config`
- `metrics backup`, `metrics import`, `metrics ls`
- `logs query`, `logs backup`, `logs import`, `logs ls`

Run these commands against the external stack instead.  Kit dashboards are not installed on a redirect cluster; the dashboards live on the external stack's Grafana.

### Validation

The tool validates the four endpoints for well-formedness at `init` and again at the start of `up`, before it creates any AWS resource.  If a signal endpoint is missing or malformed, the tool names the offending signal and stops without standing anything up.  Validation checks structure only; it does not probe the external stack for reachability.  An unreachable but well-formed endpoint surfaces later as a collector send failure.
