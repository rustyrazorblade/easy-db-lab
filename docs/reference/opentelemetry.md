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
| Cassandra host metrics | `type=db` | `db` | K3s agent config |
| Cassandra JVM | N/A | `db` | `otel.resource.attributes` in `cassandra.in.sh` |
| Stress | `type=app` | `app` | K3s agent config |
| Control | `type=control` | `control` | `Up` command node labeling |
| Spark/EMR | N/A | `spark` | EMR OTel Collector `resource/role` processor |

The `k8sattributes` processor runs in the `metrics/local` and `logs/local` pipelines only. Metrics arriving over OTLP take the `metrics/otlp` pipeline, which does not run it, so each OTLP source sets `node_role` itself: the Cassandra JVM agent and the stress sidecar declare it as a resource attribute, and Spark nodes set it in their own collector.

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

### Cassandra JVM Instrumentation

The Cassandra JVM runs the OpenTelemetry Java Agent (v2.31.1).  Its JMX Metric Insight module reads Cassandra's own MBeans in process and exports them over OTLP.  The agent replaces the k8ssandra management-api (MCAC/MAAC) agent, which is removed.

`packer/cassandra/cassandra.in.sh` adds the agent flags at JVM start.  Cassandra's `bin/cassandra` sources that file under `/bin/sh` (dash), so it must stay POSIX sh.  A bashism there does not degrade to "no metrics": dash fails to parse the file, and Cassandra does not start.

Key configuration:
- **OTel Agent JAR**: installed by Packer to `/usr/local/otel/opentelemetry-javaagent.jar`
- **Service name**: `cassandra`, which the collector turns into the Prometheus label `job="cassandra"`
- **Export endpoint**: `http://localhost:4318` (local OTel Collector DaemonSet)
- **Export interval**: 5 seconds
- **JMX discovery delay**: 5 seconds.  The agent default is 60 seconds, which holds the first metrics back for about a minute and delays picking up a new table by as much again.
- **Rule file**: `/etc/easy-db-lab/cassandra-jmx-rules.yaml`, written by `setup-instances`

Use port 4318, not 4317.  The agent's default OTLP protocol is `http/protobuf`, which the collector serves on 4318.  Port 4317 is the collector's gRPC port, and every export fails there.

The agent goes on `JVM_EXTRA_OPTS`, never on `JVM_OPTS`.  `bin/nodetool` sources `cassandra.in.sh` and puts `$JVM_OPTS` on its own command line, so an agent on `JVM_OPTS` starts again for every `nodetool`, `sstableloader` and `cassandra-stress` run.  `nodetool` discards `JVM_EXTRA_OPTS`, which is what an agent wants.

#### How a node labels its own metrics

`cassandra.in.sh` derives one value at JVM start and interpolates it into an OTel resource attribute:

```sh
EDL_CASSANDRA_BUILD=$(basename "$(readlink -f /usr/local/cassandra/current 2>/dev/null)" 2>/dev/null)

edl_add_jvm_extra_opt \
    "-javaagent:${EDL_OTEL_AGENT_JAR}" \
    "-Dotel.service.name=cassandra" \
    "-Dotel.resource.attributes=service.instance.id=$(hostname),node_role=db,cassandra_build=${EDL_CASSANDRA_BUILD}" \
    "-Dotel.exporter.otlp.endpoint=http://localhost:4318" \
    "-Dotel.metric.export.interval=5s" \
    "-Dotel.jmx.discovery.delay=5000"
```

`otel.resource.attributes` declares the SDK's **Resource**: comma-separated `key=value` pairs that the agent attaches once, at startup.  The Resource is stamped on every metric, span and log that JVM exports.  It is identity attached at the source, not bookkeeping added per metric.

The collector turns Resource attributes into Prometheus labels.  The `prometheusremotewrite` exporter sets `resource_to_telemetry_conversion: enabled: true` in `otel-collector-config.yaml`.  That step is what makes `sum by (cassandra_build)` work.

The value is read at JVM start from the symlink that `cassandra use` moves, so a node describes itself.  Run `cassandra use --hosts=db2 <version>` and restart that node, and it reports its new build.  Nothing is pushed, and no other node is touched.  This is what makes a mixed-version comparison work when the split changes between runs.

An info metric carrying the version was rejected.  Such a metric sits on its own series, so `sum by (cassandra_build) (<any other metric>)` returns nothing and every panel needs a `group_left` join.  A resource attribute lands on every series instead.

`ReleaseVersion` from the `StorageService` MBean was also rejected.  It reports `5.0.9` against a `5.0.9-SNAPSHOT` build, which separates a stock release from a branch but collapses every branch build into one value.  Two branches under test would be indistinguishable.  The symlink target carries the commit sha.

The same hook is open for anything else the node knows about itself: instance type, availability zone, disk layout, or a test-run identifier.  Add a shell variable, interpolate it into the same list, and it becomes a dimension you can group and filter by across every metric that JVM emits.

Two rules protect the value:

- Keep the whole attribute value as one shell word.  It contains `=` and `,`, so it must stay quoted.
- Keep the file POSIX sh, for the dash reason above.

If the symlink cannot be read, the value falls back to `cassandra_build=unknown` and the script warns on stderr.  The attribute is never dropped.  Dropping it would silently merge that node into another variant's series.

#### The JMX rule file

The agent reads every rule from `/etc/easy-db-lab/cassandra-jmx-rules.yaml`.  `otel.jmx.target.system` is deliberately not set, so the built-in `experimental-cassandra` target supplies nothing.  That target is experimental upstream: its metric names can move between agent releases and empty every panel that reads them.  Owning the rules pins each name to this repo.

The file ships from the CLI, not from the AMI.  It is a Kotlin classpath resource at `src/main/resources/com/rustyrazorblade/easydblab/configuration/cassandra/cassandra-jmx-rules.yaml`.  `init` extracts it into the cluster workspace, and `setup-instances` uploads it to each Cassandra node.  Changing a rule therefore costs `./gradlew installDist` and a Cassandra restart, never an AMI rebake.

The rules define these families:

| Metric | Attributes | Source MBean |
|--------|-----------|--------------|
| `cassandra.client.request.latency.{p50,p99,p999,max}` | `cassandra.operation` | `type=ClientRequest,scope={Read,Write,RangeSlice},name=Latency` |
| `cassandra.client.request.count` | `cassandra.operation` | the same three beans, `Count` |
| `cassandra.client.request.error` | `cassandra.operation`, `cassandra.status` | `name={Unavailables,Timeouts,Failures}` |
| `cassandra.compaction.tasks.{completed,pending}` | — | `type=Compaction` |
| `cassandra.storage.{load,hints.count,hints.in_progress}` | — | `type=Storage` |
| `cassandra.table.disk.space.{live,total}` | `keyspace`, `table` | `type=Table` |
| `cassandra.table.sstable.count.live` | `keyspace`, `table` | `type=Table,name=LiveSSTableCount` |
| `cassandra.table.compaction.pending` | `keyspace`, `table` | `type=Table,name=PendingCompactions` |
| `cassandra.table.{read,write}.latency.{p50,p99,p999,max}` | `keyspace`, `table` | `type=Table,name={Read,Write}Latency` |
| `cassandra.table.sstables.per.read.{p50,p99,max}` | `keyspace`, `table` | `type=Table,name=SSTablesPerReadHistogram` |
| `cassandra.thread_pool.tasks.{active,pending,blocked}` | `pool`, `pool_path` | `type=ThreadPools` |
| `cassandra.messages.dropped` | `message_type` | `type=DroppedMessage` |

Latency is reported in microseconds.  The rules set `unit: us` and no `sourceUnit`, so the raw MBean value passes through unconverted.  That is the unit `nodetool proxyhistograms` and `nodetool tablehistograms` print, so a dashboard and the oracle compare directly: 86 against 86, not 86 against 0.000086.  The Prometheus suffix follows the unit, so these series end `_microseconds`, for example `cassandra_client_request_latency_p99_microseconds`.

#### Latency is a percentile gauge, not a histogram

The MAAC agent emitted Prometheus histogram buckets.  Every bucket carried the same cumulative count, so the histogram held no distribution and `histogram_quantile()` returned a constant.

The rules read Cassandra's own `EstimatedHistogram` instead, the same reservoir `nodetool proxyhistograms` prints, and report each percentile as a gauge.  Two consequences follow:

- `histogram_quantile()` does not apply to these series.  There are no `_bucket` series to give it.
- A pre-aggregated percentile cannot be re-aggregated, so there is no true cluster-wide p99.  Show the spread across nodes with `max by (cluster)` and `min by (cluster)` instead.

#### Migrating from MAAC

Metric names and the job label both change, and nothing translates between them.

| | Before | After |
|---|---|---|
| Metric prefix | `org_apache_cassandra_metrics_*` | `cassandra_*` |
| Job label | `job="cassandra-maac"` | `job="cassandra"` |
| Transport | Prometheus scrape of `localhost:9000` | OTLP to `localhost:4318` |
| Latency shape | histogram buckets | percentile gauges |

A query that spans the change returns two disjoint sets of series.  A metrics backup taken before the change is not comparable with one taken after.

An agent version bump is a dashboard-affecting change.  Re-verify every Cassandra panel under load after one.

### Spark JVM Instrumentation

EMR Spark jobs are auto-instrumented with the OpenTelemetry Java Agent (v2.31.1) and Pyroscope Java Agent (v2.3.0), both installed via an EMR bootstrap action. The OTel agent is activated through `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions`.

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

A resource attribute is declared once, when the SDK starts, and is stamped on every metric, span and log that process exports. The `prometheusremotewrite` exporter sets `resource_to_telemetry_conversion: enabled: true`, so each one also becomes a Prometheus label.

Telemetry from the CLI tool and cluster nodes carries these resource attributes:
- `service.name`: service identifier (e.g. `easy-db-lab`, `cassandra`, `cassandra-sidecar`, `spark-<job-name>`). The collector maps it to the Prometheus label `job`.
- `service.instance.id`: instance identifier, mapped to the Prometheus label `instance`. Cassandra pins it to the hostname, because the agent default is a fresh UUID per JVM start and would mint a new series on every restart.
- `service.version`: application version (CLI tool only)
- `host.name`: hostname
- `node_role`: node type — `db`, `app`, `control` or `spark`
- `cassandra_build`: the build a Cassandra node is running, read from its own `current` symlink (Cassandra JVM only)

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
