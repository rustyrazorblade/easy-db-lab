# Log Infrastructure

This page documents the centralized logging infrastructure in easy-db-lab, including OTel for log collection and Loki for storage and querying. See [Logs (Loki)](../user-guide/loki.md) for the user guide.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                          All Nodes                                │
├──────────────────────────────────────────────────────────────────┤
│  /var/log/system logs     │  /mnt/db1/container-logs/ (NVMe)     │
│  /mnt/db1/cassandra/logs/ │    K8s pod stdout/stderr             │
│  journald                 │    (symlinked from /var/log/pods)    │
│                           │                                       │
└──────────────┬────────────────────────────┬──────────────────────┘
               │                            │
               ▼                            ▼
              ┌────────────────────────────────────────┐
              │  OTel Collector (DaemonSet)             │      ┌──────────────────┐
              │  file_log/system  file_log/containers    │◀─────│  EMR Spark JVMs  │
              │  file_log/cassandra                      │ OTLP │  (OTel Java Agent│
              │  + OTLP receiver                        │      │   v2.25.0)       │
              └───────────────────┬─────────────────────┘      └──────────────────┘
                                  │
┌─────────────────────────────────┼────────────────────────────┐
│   Control Node                  │                             │
├─────────────────────────────────┼────────────────────────────┤
│                                 ▼                             │
│                    ┌──────────────────┐                       │
│                    │      Loki        │                       │
│                    │    (:3100)       │                       │
│                    └────────┬─────────┘                      │
└─────────────────────────────┼──────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │  easy-db-lab     │
                    │  logs query      │
                    └──────────────────┘
```

## Components

### OTel Collector DaemonSet

The OpenTelemetry Collector runs on all nodes as a DaemonSet, collecting logs from four separate pipelines:

- **`logs/local`** — host file-based logs:
  - System logs: `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog` (excludes container log paths)
  - Tool runner logs: `/var/log/easydblab/tools/*.log`
  - Cassandra logs: `/mnt/db1/cassandra/logs/*.log`
- **`logs/containers`** — K8s pod stdout/stderr from all running pods, enriched with Kubernetes metadata (pod name, namespace, container name, kit label). Automatically covers any K8s-native kit without per-kit configuration. Logs are stored on NVMe at `/mnt/db1/container-logs/` (symlinked from `/var/log/pods`) to keep the boot volume free.
- **`logs/otlp`** — logs pushed via OTLP from remote applications (e.g. EMR Spark JVMs)
- **systemd journal** — collected via a separate Fluent Bit DaemonSet (`fluent-bit-journald`)

All pipelines forward to Loki's OTLP endpoint on the control node, with the cluster's tenant in the `X-Scope-OrgID` header. Loki writes chunks and index to the account bucket under `observability/logs/`.

### Spark OTel Java Agent (EMR)

When EMR Spark jobs are running, the Spark driver and executor JVMs are instrumented with the OpenTelemetry Java Agent (v2.25.0) via an EMR bootstrap action. The agent auto-instruments the JVMs and exports logs via OTLP to the control node's OTel Collector.

Logs appear in Loki with a `service_name` label like `spark-<job-name>`, making it easy to filter logs for specific Spark jobs: `{service_name=~"spark-.+"}`. The OTel Collector on each EMR node labels every line `source="emr"` and `node_role` (`spark-master` or `spark-worker`). `spark logs` selects one job's lines by `service_name`; no log line carries the EMR step id.

The data flow is: Spark JVM → OTel Java Agent → OTLP → OTel Collector (EMR node) → OTel Collector (control node) → Loki.

### Loki

Loki runs on the control node as a single process and provides:

- Log storage in S3 that outlives the cluster; nothing is compacted away or deleted
- The LogQL query language
- An HTTP API for querying (port 3100); every request needs the `X-Scope-OrgID` tenant header

## Querying Logs

### Using the CLI

```bash
# Query all logs from last hour
easy-db-lab logs query

# Filter by source
easy-db-lab logs query --source cassandra
easy-db-lab logs query --source journald

# Filter by host
easy-db-lab logs query --source cassandra --host db0

# Filter by systemd unit
easy-db-lab logs query --source journald --unit docker.service

# Search for text
easy-db-lab logs query --grep "OutOfMemory"

# Time range and limit
easy-db-lab logs query --since 30m --limit 500

# Raw LogQL query, sent unchanged
easy-db-lab logs query -q '{source="cassandra", host_name="db0"}'
```

### Labels and Structured Metadata

**Stream labels** (indexed; use them in the `{...}` selector):

| Label | Description |
|-------|-------------|
| `cluster` | `<name>-<clusterId>`; every stream carries it |
| `source` | Log source: `cassandra`, `system`, `tool-runner`, `journald`, `annotation` |
| `host_name` | Hostname (db0, app0, control0) |
| `node_role` | `db`, `app` or `control` |
| `service_name` | OTel service name; `unknown_service` when the sender sets none |
| `k8s_pod_name` | Name of the pod that emitted the log (container logs) |
| `k8s_namespace_name` | Kubernetes namespace (container logs) |
| `k8s_container_name` | Container name within the pod (container logs) |

**Structured metadata** (every other attribute; match it with a label filter after the selector, such as `| systemd_unit="docker.service"`):

| Field | Description |
|-------|-------------|
| `k8s_app_instance` | Value of the `app.kubernetes.io/instance` pod label — identifies the kit (e.g. `presto`, `tidb`) |
| `systemd_unit` | systemd unit name (journald) |
| `trace_id`, `span_id` | Trace context, for OTLP logs from instrumented JVMs |

## Troubleshooting

### No logs appearing

1. **Check Loki is running**:
   ```bash
   kubectl get pods -l app.kubernetes.io/name=loki
   ```

2. **Check OTel Collector is running**:
   ```bash
   kubectl get pods | grep otel
   ```

3. **Verify the cluster-config ConfigMap exists**:
   ```bash
   kubectl get configmap cluster-config -o yaml
   ```

### Connection errors

The `logs query` command uses the internal SOCKS5 proxy to connect to Loki. If you see connection errors:

1. Ensure the cluster is running: `easy-db-lab status`
2. The proxy is started automatically when needed
3. Check that control node is accessible: `ssh control0 hostname`

## Ports

| Port | Service | Location |
|------|---------|----------|
| 3100 | Loki HTTP API | Control node |
| 9098 | Loki gRPC | Control node |
