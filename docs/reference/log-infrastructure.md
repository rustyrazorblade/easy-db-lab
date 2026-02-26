# Log Infrastructure

This page documents the centralized logging infrastructure in easy-db-lab, including OTel for log collection and Victoria Logs for storage and querying.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     All Nodes                                │
├─────────────────────────────────────────────────────────────┤
│   /var/log/*          │   journald                          │
│   /mnt/db1/cassandra/logs/*.log                             │
│   /mnt/db1/clickhouse/logs/*.log                            │
│   /mnt/db1/clickhouse/keeper/logs/*.log                     │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │  OTel Collector        │
              │  (DaemonSet)           │      ┌──────────────────┐
              │  filelog + journald    │◀─────│  EMR Spark JVMs  │
              │  + OTLP receiver       │ OTLP │  (OTel Java Agent│
              └───────────┬────────────┘      │   v2.25.0)       │
                          │                   └──────────────────┘
┌─────────────────────────┼─────────────────────────┐
│   Control Node          │                          │
├─────────────────────────┼─────────────────────────┤
│                         ▼                          │
│              ┌──────────────────┐                  │
│              │  Victoria Logs   │                  │
│              │    (:9428)       │                  │
│              └────────┬─────────┘                  │
└───────────────────────┼────────────────────────────┘
                        │
                        ▼
              ┌──────────────────┐
              │  easy-db-lab     │
              │  logs query      │
              └──────────────────┘
```

## Components

### OTel Collector DaemonSet

The OpenTelemetry Collector runs on all nodes as a DaemonSet, collecting:

- **System file logs**: `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog`
- **Cassandra logs**: `/mnt/db1/cassandra/logs/*.log`
- **ClickHouse server logs**: `/mnt/db1/clickhouse/logs/*.log`
- **ClickHouse Keeper logs**: `/mnt/db1/clickhouse/keeper/logs/*.log`
- **systemd journal**: `cassandra`, `docker`, `k3s`, `sshd` units
- **OTLP**: Receives logs from applications via OTLP protocol

Logs are forwarded to Victoria Logs on the control node via the Elasticsearch-compatible sink.

### Spark OTel Java Agent (EMR)

When EMR Spark jobs are running, the Spark driver and executor JVMs are instrumented with the OpenTelemetry Java Agent (v2.25.0) via an EMR bootstrap action. The agent auto-instruments the JVMs and exports logs via OTLP to the control node's OTel Collector.

Logs appear in VictoriaLogs with a `service.name` attribute like `spark-<job-name>`, making it easy to filter logs for specific Spark jobs.

The data flow is: Spark JVM → OTel Java Agent → OTLP → OTel Collector (control node) → VictoriaLogs.

### Victoria Logs

Victoria Logs runs on the control node and provides:

- Log storage with efficient compression
- LogsQL query language
- HTTP API for querying (port 9428)

## Querying Logs

### Using the CLI

```bash
# Query all logs from last hour
easy-db-lab logs query

# Filter by source
easy-db-lab logs query --source cassandra
easy-db-lab logs query --source clickhouse
easy-db-lab logs query --source systemd

# Filter by host
easy-db-lab logs query --source cassandra --host db0

# Filter by systemd unit
easy-db-lab logs query --source systemd --unit docker.service

# Search for text
easy-db-lab logs query --grep "OutOfMemory"

# Time range and limit
easy-db-lab logs query --since 30m --limit 500

# Raw Victoria Logs query (LogsQL syntax)
easy-db-lab logs query -q 'source:cassandra AND host:db0'
```

### Log Stream Fields

**Common fields** (all sources):

| Field | Description |
|-------|-------------|
| `source` | Log source: cassandra, clickhouse, systemd, system |
| `host` | Hostname (db0, app0, control0) |
| `timestamp` | Log timestamp |
| `message` | Log message content |

**Source-specific fields**:

| Source | Field | Description |
|--------|-------|-------------|
| clickhouse | `component` | server or keeper |
| systemd | `unit` | systemd unit name |

## Troubleshooting

### No logs appearing

1. **Check Victoria Logs is running**:
   ```bash
   kubectl get pods | grep victoria
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

The `logs query` command uses the internal SOCKS5 proxy to connect to Victoria Logs. If you see connection errors:

1. Ensure the cluster is running: `easy-db-lab status`
2. The proxy is started automatically when needed
3. Check that control node is accessible: `ssh control0 hostname`

## Ports

| Port | Service | Location |
|------|---------|----------|
| 9428 | Victoria Logs HTTP API | Control node |
