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

## Annotations

Annotations mark a moment on the dashboards' timeline. Use them as A/B config-change markers; for example, drop one before and one after you change a Cassandra setting, so a latency shift lines up with the change that caused it.

Create an annotation with `grafana annotate`:

```bash
# A point marker at the current time
easy-db-lab grafana annotate --text "raised concurrent_writes to 128" --tags config

# A region marker spanning a window
easy-db-lab grafana annotate --text "load test" --tags test --time -30m --time-end now
```

A plain global marker (no `--dashboard` and no `--panel` scope) is automatically tagged `easydblab`, in addition to any `--tags` you pass. Every annotation is also mirrored to Loki as it is created (`source="annotation"`), and the core dashboards read their markers from Loki, so a global marker renders on every core dashboard and stays readable from S3 after the cluster is gone. A scoped marker (`--dashboard` or `--panel`) is not auto-tagged; it renders on its target dashboard. See [Command Reference](../reference/commands.md#grafana-annotate) for all options.

### Backing up annotations

The cluster is ephemeral, but the annotations are worth keeping. Back them up to the tenant's annotations directory in the account bucket (`observability/annotations/<tenant>/`):

```bash
easy-db-lab grafana backup
```

This backup also runs automatically before teardown. When you run `easy-db-lab down`, the tool mirrors the annotations to Loki, flushes Loki and Mimir to S3, and backs up the annotations, before it removes any infrastructure. If any of that fails, `down` stops there and removes nothing; it never starts Loki or Mimir again. Pass `--force` to skip these steps and tear down anyway. See [`down`](../reference/commands.md#down) for details.

## Where observability data is stored

Every cluster belongs to an observability **tenant**, chosen at `init --tenant <name>` (default `default`) and fixed for the life of the cluster. Clusters that share a tenant share one store: their metrics, logs, traces, profiles and annotations sit side by side and can be read together.

All of a cluster's observability data lands in the account bucket. Mimir's prefix is `observabilitymetrics`, because Mimir accepts only letters and digits in it; everything else is under `observability/`:

| Location | What | Written by |
|----------|------|------------|
| `observability/traces/<tenant>/` | Tempo blocks | Tempo, while the cluster runs |
| `observability/profiles/` | Pyroscope v2 segments and blocks | the Pyroscope server, while the cluster runs |
| `observabilitymetrics/<tenant>/` | Mimir blocks | Mimir, while the cluster runs, and its flush at `down` |
| `observability/logs/` | Loki chunks (under the tenant) and TSDB index (under `index/`) | Loki, while the cluster runs, and its flush at `down` |
| `observability/annotations/<tenant>/<yyyyMMdd-HHmmss>_<name>-<clusterId>.json` | Grafana annotations | `grafana backup`, and `down` |

An annotations backup never overwrites another: when one of the same cluster already exists under that second's name (a `grafana backup` just before `down`, say), the new one takes the next free second.

Cluster configuration stays under `clusters/<name>-<id>/config/`. The observability stack writes nothing to the per-cluster data bucket. `easy-db-lab status` shows the tenant and every location.

**Native multi-tenancy.** Mimir, Loki, Tempo and Pyroscope run with multi-tenancy on; the tenant is their tenant ID. Every writer sends it in the `X-Scope-OrgID` header — the OTel collector's metrics, logs and trace exporters, Tempo's metrics generator, the Alloy eBPF profiler, the Pyroscope Java agent (stress jobs, the Cassandra sidecar, EMR Spark, the Trino and Presto kits) and the Cassandra JFR shipper — on redirected clusters too. Every Grafana datasource sends it on every query, so a query from the cluster's Grafana returns its tenant's data. A query that sends no tenant, such as one made directly against a backend's HTTP API, reads nothing; add `-H 'X-Scope-OrgID: <tenant>'`. Pyroscope's own UI on port 4040 asks for the tenant once per browser; see [Profiling](profiling.md#pyroscope-ui).

**Nothing is deleted automatically.** No S3 lifecycle, expiry or retention rule is set on any bucket, and `down` deletes no data. The cluster's IAM role is denied deletes on the metrics, logs, traces and annotations prefixes. Mimir runs no compactor and no retention. Loki's compactor is idle, its retention is off and its delete API is not served. Tempo runs with compaction — which is also what runs retention in Tempo 3 — turned off for every tenant, so no block is ever deleted. Pyroscope runs pure v2 storage with the metastore's retention cleanup off and no retention period; its v2 compaction merges this cluster's segments into blocks, which keeps their data.

**Durability across restarts.** Tempo cuts a block at most every five minutes and uploads it while the cluster runs. Both of its write-ahead logs live on the control node's disk (`/mnt/db1/tempo`), so a restart of the Tempo pod does not lose spans it received but has not yet uploaded. Tempo acknowledges a push while the spans are still in memory and writes them to the WAL about 2 seconds later (10 seconds at most for a trace that is still receiving spans). A graceful restart writes every in-memory trace to the WAL first and loses nothing. A Tempo process killed outright loses only the spans from those last 2 seconds. The WAL is not fsynced, so a crash of the control node itself can also lose WAL data the operating system had not yet written to disk. Tempo keeps every span it receives: its per-tenant limits on live traces and trace size are off, and its ingestion rate limit is 1 GB/s, so a stress load is not thinned out. It also stores every attribute value in full: the distributor's `max_attribute_bytes` is 0, where Tempo's default of 2048 bytes would truncate long values such as `process.command_line`. Any span Tempo does drop is counted in `tempo_discarded_spans_total`. The Pyroscope metastore index lives on `/mnt/db1/pyroscope`, so profiles written before a Pyroscope restart are still returned after it.

**Known limit: Pyroscope after teardown.** Pyroscope v2 finds blocks only through the cluster's local metastore index and cannot rebuild it from S3. After `down`, the profiles stay in `observability/profiles/` but cannot be queried until the index is saved at teardown (planned in #966).

**Restarts.** `up` and `grafana update-config` restart an observability workload only when its configuration changed: each workload's pod template carries a hash of the pod template itself (image, probes, environment, volumes) and of the ConfigMaps it reads. A dashboard edit no longer restarts Tempo or Pyroscope. Both commands print, for each workload, whether its configuration changed (and it is rolling) or is unchanged (and it is left running). A new image or probe counts as a change, because Kubernetes rolls the workload for it.

**Versions.** Tempo 3.0.3, Pyroscope 2.3.1, Grafana 13.2.2 with image renderer v5.12.4, Alloy v1.20.0, Beyla 3.36.0, Mimir 3.2.1, Loki 3.7.8, the OpenTelemetry collector 0.161.0 (cluster, stress-job sidecar and EMR), and Fluent Bit 5.1.2. No observability image runs `latest`. The collector also scrapes its own telemetry (`:8888`, job `otel-collector`), so exporter send failures are visible in Mimir. In local mode it also scrapes the Mimir (`:9009`, job `mimir`), Loki (`:3100`, job `loki`), Tempo (`:3200`, job `tempo`) and Pyroscope (`:4040`, job `pyroscope`) servers' own metrics. Only the collector on the node running each server scrapes it, so each job has one target, and `instance` is the server's pod name. A redirect cluster runs none of these servers and renders none of these jobs.

## eBPF Observability

The cluster deploys eBPF-based agents on all nodes for deep system observability:

### Beyla (L7 Network Metrics)

Grafana Beyla uses eBPF to automatically instrument network traffic and provide RED metrics (Rate, Errors, Duration) for:

- **Cassandra** CQL protocol (port 9042) and inter-node communication (port 7000)
- **ClickHouse** HTTP (port 8123) and native (port 9000) protocols

Metrics are scraped by the OTel collector and stored in Mimir.

Beyla observes from outside the process only: its Java agent injection is turned off (`javaagent.enabled: false`), so it never attaches an agent to the database JVM.

### ebpf_exporter (Low-Level Metrics)

Cloudflare's ebpf_exporter provides kernel-level metrics via eBPF:

- **TCP retransmits** — count of retransmitted TCP segments
- **Block I/O latency** — histogram of block device I/O operation latency
- **VFS latency** — histogram of filesystem read/write operation latency

These metrics are scraped by the OTel collector and stored in Mimir.

## kube-state-metrics

kube-state-metrics runs on the control node of every cluster and exposes the state of Kubernetes objects as Prometheus metrics:

- **Pods** — `kube_pod_status_phase`, `kube_pod_container_status_restarts_total`, `kube_pod_container_status_waiting_reason`
- **Workloads** — `kube_deployment_status_replicas_available`, `kube_daemonset_status_number_ready`, `kube_statefulset_status_replicas_ready`, `kube_job_status_succeeded`
- **Nodes and storage** — `kube_node_status_condition`, `kube_persistentvolumeclaim_status_phase`

The OTel collector scrapes it once, through pod discovery on the control node, so each series appears one time. The metrics carry the `cluster` label like every other scrape.

## Cilium metrics

On a Cilium cluster (the default CNI), the OTel collector also scrapes the Cilium agent (`localhost:9962` on every node), Hubble (`localhost:9965` on every node), and the Cilium operator (port 9963 on the node that runs it). A Flannel cluster has none of these jobs. See [Pod Networking (CNI)](networking.md) for the job definitions, the Hubble UI NodePort, and `platform cni`.

See [Profiling](profiling.md) for continuous profiling with Pyroscope.

## Redirecting Telemetry to an External Stack

By default each cluster stands up its own local observability backends on the control node: Mimir, Loki, Tempo, the Pyroscope server, and Grafana.  Redirect mode ships all telemetry to an external observability stack instead.  A redirect cluster stands up no local backends and no local Grafana.

Redirect mode is useful when you run several clusters and want one place to view them all.  A common case is a second data center that reports into the first data center's stack.

### Enabling Redirect Mode

Set the redirect target once, at `init`, with `--redirect-telemetry <host>`:

```bash
easy-db-lab init --redirect-telemetry 10.0.0.9 --up
```

`<host>` is the hostname or IP of the external stack.  The tool derives the four signal endpoints from that host and the known stack ports:

- Metrics: `http://<host>:9009/api/v1/push` (Mimir remote write)
- Logs: `http://<host>:3100/otlp` (Loki OTLP ingest)
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
  --redirect-metrics-endpoint http://metrics.example.com:9009/api/v1/push \
  --redirect-traces-endpoint tempo.example.com:4320 \
  --up
```

The four override options are `--redirect-metrics-endpoint`, `--redirect-logs-endpoint`, `--redirect-traces-endpoint`, and `--redirect-profiles-endpoint`.

### Origin Identity

Every signal carries the source cluster's name as its origin identifier.  Metrics and traces carry a `cluster` label; logs carry a `cluster` field; profiles carry a `cluster` label.  Use that identifier to tell one cluster's telemetry from another on the shared stack.

### Commands That Need the Local Stack

A redirect cluster has no local backends or Grafana to act on.  These commands refuse to run on a redirect cluster and tell you why:

- `grafana update-config`
- `logs query`

`down` also skips its pre-teardown annotation mirror, flushes and annotations backup on a redirect cluster.

Run these commands against the external stack instead.  Kit dashboards are not installed on a redirect cluster; the dashboards live on the external stack's Grafana.

### Validation

The tool validates the four endpoints for well-formedness at `init` and again at the start of `up`, before it creates any AWS resource.  If a signal endpoint is missing or malformed, the tool names the offending signal and stops without standing anything up.  Validation checks structure only; it does not probe the external stack for reachability.  An unreachable but well-formed endpoint surfaces later as a collector send failure.
