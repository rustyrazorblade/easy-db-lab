# Log Infrastructure

This page documents the centralized logging infrastructure in easy-db-lab, including Vector for log collection and Victoria Logs for storage and querying.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     All Nodes                                │
├─────────────────────────────────────────────────────────────┤
│   /var/log/*          │   journald                          │
│   /mnt/db1/clickhouse/logs/*                                │
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
│   │ Vector      │───────┤                          │
│   │ (S3 logs)   │       │                          │
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

### Vector DaemonSet

A Vector DaemonSet runs on all nodes to collect:

- **File logs**: `/var/log/**/*.log`, Cassandra logs, ClickHouse logs
- **systemd journal**: `cassandra.service`, `docker.service`, `k3s.service`

Logs are forwarded to Victoria Logs on the control node.

### Vector S3 Deployment

A separate Vector deployment on the control node collects logs from S3:

- **EMR/Spark logs**: Step stdout/stderr from `s3://{bucket}/emr-logs/`
- **Other cloud service logs**: Any logs written to the configured S3 bucket

This deployment polls an SQS queue for S3 event notifications.

### Victoria Logs

Victoria Logs runs on the control node and provides:

- Log storage with efficient compression
- LogsQL query language
- HTTP API for querying (port 9428)

## AWS Infrastructure

### SQS Queue

An SQS queue is **always created** during `easy-db-lab up`, regardless of which services are enabled. This queue is used for:

1. **S3 event notifications** - When logs are written to S3, notifications are sent to this queue
2. **Vector S3 source** - Vector polls the queue to discover new log files
3. **Future services** - The queue is available for any service that needs S3 event-driven processing

The queue is named `{profile}-log-ingest` and is automatically configured with:

- S3 bucket notification configuration
- Appropriate IAM permissions for Vector to poll messages
- Dead letter queue for failed processing (if configured)

### S3 Bucket Notifications

The S3 bucket is configured to send notifications to the SQS queue for:

- `s3:ObjectCreated:*` events in the `emr-logs/` prefix
- Other configured prefixes as needed

## Querying Logs

### Using the CLI

```bash
# Query all logs from last hour
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

# Time range and limit
easy-db-lab logs query --since 30m --limit 500

# Raw Victoria Logs query (LogsQL syntax)
easy-db-lab logs query -q 'source:cassandra AND host:db0'
```

### Log Stream Fields

**Common fields** (all sources):

| Field | Description |
|-------|-------------|
| `source` | Log source: emr, cassandra, clickhouse, systemd, system |
| `host` | Hostname (db0, app0, control0) |
| `timestamp` | Log timestamp |
| `message` | Log message content |

**Source-specific fields**:

| Source | Field | Description |
|--------|-------|-------------|
| emr | `emr_cluster_id` | EMR cluster ID |
| emr | `step_id` | EMR step ID |
| emr | `log_type` | stdout or stderr |
| clickhouse | `component` | server or keeper |
| systemd | `unit` | systemd unit name |

## Troubleshooting

### No logs appearing

1. **Check Victoria Logs is running**:
   ```bash
   kubectl get pods | grep victoria
   ```

2. **Check Vector is running**:
   ```bash
   kubectl get pods | grep vector
   ```

3. **Verify SQS queue exists** (for S3 logs):
   ```bash
   aws sqs list-queues --queue-name-prefix easy-db-lab
   ```

### EMR logs not appearing

EMR logs may take several minutes to appear because:

1. EMR writes logs to S3 periodically (not in real-time)
2. S3 sends notification to SQS
3. Vector polls SQS and ingests logs
4. Query results are available

Try increasing the time range:
```bash
easy-db-lab logs query --source emr --since 1h
```

## Ports

| Port | Service | Location |
|------|---------|----------|
| 9428 | Victoria Logs HTTP API | Control node |
