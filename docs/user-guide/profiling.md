# Profiling

Continuous profiling is provided by [Grafana Pyroscope](https://grafana.com/oss/pyroscope/), deployed automatically as part of the observability stack.

## Architecture

Profiling data is collected from two sources and sent to the Pyroscope server on the control node (port 4040):

- **Pyroscope Java agent** — Runs as a `-javaagent` inside the Cassandra JVM. Uses async-profiler to collect CPU, allocation, lock contention, and wall-clock profiles with full method-level resolution.
- **Pyroscope eBPF agent** — Runs as a DaemonSet on all nodes. Profiles all processes (Cassandra, ClickHouse, stress jobs) at the system level using eBPF. Provides CPU flame graphs including kernel stack frames.

## Accessing Profiles

### Grafana Explore

1. Open Grafana (port 3000) and navigate to **Explore**
2. Select the **Pyroscope** datasource
3. Choose a profile type (e.g., `process_cpu`, `memory`, `mutex`)
4. Filter by labels:
   - `service_name` — process or application name
   - `hostname` — node hostname
   - `cluster` — cluster name

### Profile Types (Cassandra Java Agent)

| Profile | Description |
|---------|-------------|
| `cpu` | CPU time spent in each method |
| `alloc` | Memory allocation by method (objects and bytes) |
| `lock` | Lock contention — time spent waiting for monitors |
| `wall` | Wall-clock time — useful for finding I/O bottlenecks |

### Profile Types (eBPF Agent)

| Profile | Description |
|---------|-------------|
| `process_cpu` | CPU usage by process, including kernel frames |

## Configuration

### Cassandra Java Agent

The Pyroscope Java agent is configured via JVM system properties in `cassandra.in.sh`. It activates when the `PYROSCOPE_SERVER_ADDRESS` environment variable is set (configured by easy-db-lab at cluster startup).

The agent JAR is installed at `/usr/local/pyroscope/pyroscope.jar`.

### eBPF Agent

The eBPF agent runs as a privileged DaemonSet (`pyroscope-ebpf`) and profiles all processes on each node. Configuration is in the `pyroscope-ebpf-config` ConfigMap.

### Pyroscope Server

The Pyroscope server runs on the control node with data stored at `/mnt/db1/pyroscope`. Configuration is in the `pyroscope-config` ConfigMap.

## Data Flow

```
Cassandra JVM ──(Java agent)──► Pyroscope Server (:4040)
                                     ▲
All Processes ──(eBPF agent)─────────┘
                                     │
                                     ▼
                              Grafana (:3000)
                           Pyroscope datasource
```
