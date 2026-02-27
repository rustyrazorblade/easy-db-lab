# Profiling

Continuous profiling is provided by [Grafana Pyroscope](https://grafana.com/oss/pyroscope/), deployed automatically as part of the observability stack.

## Architecture

Profiling data is collected from multiple sources and sent to the Pyroscope server on the control node (port 4040):

- **Pyroscope Java agent (Cassandra)** — Runs as a `-javaagent` inside the Cassandra JVM. Uses async-profiler to collect CPU, allocation, lock contention, and wall-clock profiles with full method-level resolution.
- **Pyroscope Java agent (Stress jobs)** — Runs as a `-javaagent` inside cassandra-easy-stress K8s Jobs. Collects the same profile types as Cassandra (CPU, allocation, lock). The agent JAR is mounted from the host via a hostPath volume.
- **Pyroscope Java agent (Spark/EMR)** — Runs as a `-javaagent` on Spark driver and executor JVMs. Installed via EMR bootstrap action to `/opt/pyroscope/pyroscope.jar`. Collects CPU, allocation (512k threshold), and lock (10ms threshold) profiles in JFR format. Profiles appear under `service_name=spark-<job-name>`.
- **Grafana Alloy eBPF profiler** — Runs as a DaemonSet on all nodes via [Grafana Alloy](https://grafana.com/docs/alloy/latest/). Profiles all processes (Cassandra, ClickHouse, stress jobs) at the system level using eBPF. Provides CPU flame graphs including kernel stack frames.

## Accessing Profiles

### Profiling Dashboard

A dedicated **Profiling** dashboard is available in Grafana with flame graph panels for each profile type:

1. Open Grafana (port 3000)
2. Navigate to **Dashboards** and select the **Profiling** dashboard
3. Use the **Service** dropdown to select a service (e.g., `cassandra`, `cassandra-easy-stress`, `clickhouse-server`)
4. Use the **Hostname** dropdown to filter by specific nodes
5. Select a time range to view profiles for that period

The dashboard includes panels for:
- **CPU Flame Graph** — CPU time spent in each method
- **Memory Allocation Flame Graph** — Heap allocation hotspots
- **Lock Contention Flame Graph** — Time spent waiting for monitors
- **Mutex Contention Flame Graph** — Mutex delay analysis

### Grafana Explore

For ad-hoc profile exploration:

1. Open Grafana (port 3000) and navigate to **Explore**
2. Select the **Pyroscope** datasource
3. Choose a profile type (e.g., `process_cpu`, `memory`, `mutex`)
4. Filter by labels:
   - `service_name` — process or application name
   - `hostname` — node hostname
   - `cluster` — cluster name

## Profile Types

### Java Agent (Cassandra, Stress Jobs)

| Profile | Description |
|---------|-------------|
| `cpu` | CPU time spent in each method |
| `alloc` | Memory allocation by method (objects and bytes) |
| `lock` | Lock contention — time spent waiting for monitors |
| `wall` | Wall-clock time — useful for finding I/O bottlenecks (Cassandra only, see below) |

### eBPF Agent (All Processes)

| Profile | Description |
|---------|-------------|
| `process_cpu` | CPU usage by process, including kernel frames |

The eBPF agent profiles **all processes** on every node, including ClickHouse. Since ClickHouse is written in C++, only CPU profiles are available (no allocation or lock profiles). ClickHouse profiles appear in Pyroscope under the `clickhouse-server` service name when ClickHouse is running.

## Stress Job Profiling

Stress jobs are automatically profiled via the Pyroscope Java agent. No additional configuration is needed — when you start a stress job, the agent is mounted from the host node and configured to send profiles to the Pyroscope server.

Profiles appear under `service_name=cassandra-easy-stress` with labels for `cluster` and `job_name`.

## Wall-Clock vs CPU Profiling

By default, the Cassandra Java agent profiles CPU time. You can switch to **wall-clock profiling** to find I/O bottlenecks and blocking operations.

```admonish warning
Wall-clock and CPU profiling are mutually exclusive — you cannot use both simultaneously.
```

To enable wall-clock profiling:

1. SSH to each Cassandra node
2. Add `PYROSCOPE_PROFILER_EVENT=wall` to `/etc/default/cassandra`
3. Restart Cassandra

To switch back to CPU profiling, either remove the line or set `PYROSCOPE_PROFILER_EVENT=cpu`.

## Configuration

### Cassandra Java Agent

The Pyroscope Java agent is configured via JVM system properties in `cassandra.in.sh`. It activates when the `PYROSCOPE_SERVER_ADDRESS` environment variable is set (configured by easy-db-lab at cluster startup).

The agent JAR is installed at `/usr/local/pyroscope/pyroscope.jar`.

| Environment Variable | Set In | Description |
|---------------------|--------|-------------|
| `PYROSCOPE_SERVER_ADDRESS` | `/etc/default/cassandra` | Pyroscope server URL (set automatically) |
| `CLUSTER_NAME` | `/etc/default/cassandra` | Cluster name for labeling (set automatically) |
| `PYROSCOPE_PROFILER_EVENT` | `/etc/default/cassandra` | Profiler event type: `cpu` (default) or `wall` |

### eBPF Agent

The eBPF profiler runs as a privileged Grafana Alloy DaemonSet (`pyroscope-ebpf`) and profiles all processes on each node. Configuration is in the `pyroscope-ebpf-config` ConfigMap (Alloy River format). It uses `discovery.process` to discover host processes and `pyroscope.ebpf` to collect CPU profiles.

### Pyroscope Server

The Pyroscope server runs on the control node with data stored in S3 (`s3://<data-bucket>/pyroscope/`). Configuration is in the `pyroscope-config` ConfigMap.

## Data Flow

```
Cassandra JVM ──(Java agent)──────► Pyroscope Server (:4040)
                                         ▲
Stress Jobs ──(Java agent)──────────────┘
                                         ▲
Spark JVMs ──(Java agent)──────────────┘
                                         ▲
All Processes ──(eBPF agent)────────────┘
                                         │
                                         ▼
                                    S3 storage
                                  Grafana (:3000)
                             Pyroscope datasource
                            + Profiling dashboard
```
