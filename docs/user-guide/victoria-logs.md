# Victoria Logs

Victoria Logs is a centralized log aggregation system that collects logs from all nodes in your easy-db-lab cluster. It provides a unified way to search and analyze logs from Cassandra, ClickHouse, system services, and EMR/Spark jobs.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     All Nodes (DaemonSet)                    │
├─────────────────────────────────────────────────────────────┤
│   /var/log/*              journald                          │
│   /mnt/db1/cassandra/logs/*.log                             │
│   /mnt/db1/clickhouse/logs/*.log                            │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   Vector (DaemonSet)   │
              │   file + journald      │
              └───────────┬────────────┘
                          │
┌─────────────────────────┼─────────────────────────┐
│   Control Node          │                          │
├─────────────────────────┼─────────────────────────┤
│                         │                          │
│   ┌─────────────┐       │                          │
│   │ Vector      │       │                          │
│   │ (S3 logs)   │───────┤                          │
│   └─────────────┘       │                          │
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

### Victoria Logs Server

Victoria Logs runs on the control node as a Kubernetes deployment:

- **Port**: 9428 (HTTP API)
- **Storage**: Local ephemeral storage
- **Retention**: 7 days (configurable)
- **Location**: Control node only (`node-role.kubernetes.io/master`)

### Vector Log Collector

[Vector](https://vector.dev/) collects logs from all sources and forwards them to Victoria Logs.

#### Node DaemonSet

Runs on every node (Cassandra, stress, control) to collect:

| Source | Path | Description |
|--------|------|-------------|
| Cassandra | `/mnt/db1/cassandra/logs/*.log` | Cassandra database logs |
| ClickHouse | `/mnt/db1/clickhouse/logs/*.log` | ClickHouse server logs |
| ClickHouse Keeper | `/mnt/db1/clickhouse/keeper/logs/*.log` | ClickHouse Keeper logs |
| System logs | `/var/log/**/*.log` | General system logs |
| journald | `cassandra.service`, `docker.service`, `k3s.service` | systemd service logs |

#### S3 Log Collector (EMR/Spark)

A separate Vector deployment on the control node ingests EMR logs from S3:

1. EMR writes logs to S3 bucket (`emr-logs/` prefix)
2. S3 sends event notifications to SQS queue
3. Vector polls SQS and downloads/processes log files
4. Logs are forwarded to Victoria Logs

## Log Sources

Each log entry is tagged with a `source` field:

| Source | Description | Additional Fields |
|--------|-------------|-------------------|
| `cassandra` | Cassandra database logs | `host` |
| `clickhouse` | ClickHouse server logs | `host`, `component` (server/keeper) |
| `systemd` | systemd journal logs | `host`, `unit` |
| `system` | General /var/log files | `host` |
| `emr` | EMR/Spark step logs | `emr_cluster_id`, `step_id`, `log_type` |

## Querying Logs

### Using the CLI

The `easy-db-lab logs query` command provides a unified interface:

```bash
# Query all logs from the last hour
easy-db-lab logs query

# Filter by source
easy-db-lab logs query --source cassandra
easy-db-lab logs query --source emr
easy-db-lab logs query --source clickhouse
easy-db-lab logs query --source systemd

# Filter by host
easy-db-lab logs query --source cassandra --host db0

# Filter by systemd unit
easy-db-lab logs query --source systemd --unit docker.service

# Search for text
easy-db-lab logs query --grep "OutOfMemory"
easy-db-lab logs query --grep "ERROR"

# Time range and limit
easy-db-lab logs query --since 30m --limit 500
easy-db-lab logs query --since 1d

# Raw LogsQL query
easy-db-lab logs query -q 'source:cassandra AND host:db0'
```

### Query Options

| Option | Description | Default |
|--------|-------------|---------|
| `--source`, `-s` | Log source filter | All sources |
| `--host`, `-H` | Hostname filter (db0, app0, control0) | All hosts |
| `--unit` | systemd unit name | All units |
| `--since` | Time range (1h, 30m, 1d) | 1h |
| `--limit`, `-n` | Max entries to return | 100 |
| `--grep`, `-g` | Text search filter | None |
| `--query`, `-q` | Raw LogsQL query | None |

### Using the HTTP API

Victoria Logs exposes a REST API on port 9428. Access it through the SOCKS proxy:

```bash
source env.sh
with-proxy curl "http://control0:9428/select/logsql/query?query=source:cassandra&time=1h&limit=100"
```

### Using Grafana

Victoria Logs is configured as a datasource in Grafana:

1. Access Grafana at `http://control0:3000` (via SOCKS proxy)
2. Navigate to Explore
3. Select "VictoriaLogs" datasource
4. Use LogsQL syntax for queries

## LogsQL Query Syntax

Victoria Logs uses LogsQL for querying. Basic syntax:

```
# Simple field match
source:cassandra

# Multiple conditions (AND)
source:cassandra AND host:db0

# Text search
"OutOfMemory"

# Combine field match with text search
source:emr AND "Exception"

# Time filter (in addition to --since)
_time:1h
```

For full LogsQL documentation, see the [Victoria Logs documentation](https://docs.victoriametrics.com/victorialogs/logsql/).

## Deployment

Victoria Logs and Vector are automatically deployed when you run:

```bash
easy-db-lab k8 apply
```

This deploys:
- Victoria Logs server on the control node
- Vector DaemonSet on all nodes
- Vector S3 deployment for EMR logs (if Spark is enabled)
- Grafana datasource configuration

## Verifying the Setup

Check that all components are running:

```bash
source env.sh
kubectl get pods -l app.kubernetes.io/name=victorialogs
kubectl get pods -l app=vector
kubectl get pods -l app=vector-s3
```

Test connectivity:

```bash
# Check Victoria Logs health
with-proxy curl http://control0:9428/health

# Query recent logs
easy-db-lab logs query --limit 10
```

## Troubleshooting

### No logs appearing

1. Verify Vector pods are running:
   ```bash
   kubectl get pods -l app=vector
   kubectl logs -l app=vector
   ```

2. Check Victoria Logs is healthy:
   ```bash
   with-proxy curl http://control0:9428/health
   ```

3. Verify the cluster-config ConfigMap exists:
   ```bash
   kubectl get configmap cluster-config -o yaml
   ```

### EMR logs not appearing

1. Verify the SQS queue was created:
   ```bash
   # Check cluster state
   cat state.json | jq '.sqsQueueUrl'
   ```

2. Check Vector S3 pod logs:
   ```bash
   kubectl logs -l app=vector-s3
   ```

3. Verify S3 bucket notifications are configured (done during `easy-db-lab up`)

### Connection errors

The `logs query` command uses the internal SOCKS5 proxy to connect to Victoria Logs. If you see connection errors:

1. Ensure the cluster is running: `easy-db-lab status`
2. The proxy is started automatically when needed
3. Check that control node is accessible: `ssh control0 hostname`
