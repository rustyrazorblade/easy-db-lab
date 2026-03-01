# Victoria Logs

Victoria Logs is a centralized log aggregation system that collects logs from all nodes in your easy-db-lab cluster. It provides a unified way to search and analyze logs from Cassandra, ClickHouse, and system services.

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
              │  OTel Collector        │
              │  (DaemonSet)           │
              │  filelog + journald    │
              └───────────┬────────────┘
                          │
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

### Victoria Logs Server

Victoria Logs runs on the control node as a Kubernetes deployment:

- **Port**: 9428 (HTTP API)
- **Storage**: Local ephemeral storage
- **Retention**: 7 days (configurable)
- **Location**: Control node only (`node-role.kubernetes.io/control-plane`)

### OTel Collector

The [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) collects logs from all sources and forwards them to Victoria Logs.

The OTel Collector runs as a DaemonSet on every node (Cassandra, stress, control) to collect:

| Source | Path | Description |
|--------|------|-------------|
| Cassandra | `/mnt/db1/cassandra/logs/*.log` | Cassandra database logs |
| ClickHouse | `/mnt/db1/clickhouse/logs/*.log` | ClickHouse server logs |
| ClickHouse Keeper | `/mnt/db1/clickhouse/keeper/logs/*.log` | ClickHouse Keeper logs |
| System logs | `/var/log/**/*.log` | General system logs |
| journald | `cassandra`, `docker`, `k3s`, `sshd` | systemd service logs |

## Log Sources

Each log entry is tagged with a `source` field:

| Source | Description | Additional Fields |
|--------|-------------|-------------------|
| `cassandra` | Cassandra database logs | `host` |
| `clickhouse` | ClickHouse server logs | `host`, `component` (server/keeper) |
| `systemd` | systemd journal logs | `host`, `unit` |
| `system` | General /var/log files | `host` |

## Querying Logs

### Using the CLI

The `easy-db-lab logs query` command provides a unified interface:

```bash
# Query all logs from the last hour
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

Victoria Logs is configured as a datasource in Grafana. You can use it in two ways:

#### Log Investigation Dashboard

The **Log Investigation** dashboard is designed for interactive log analysis during investigations. Access it at Grafana → Dashboards → Log Investigation.

**Filter variables** (dropdowns at the top):

| Filter | Options | Description |
|--------|---------|-------------|
| Node Role | All, db, app, control | Filter by server type |
| Source | All, cassandra, clickhouse, system, tool-runner | Filter by log source |
| Level | All, Error, Warning, Info, Debug | Filter by log severity |
| Search | (text input) | Free-text search across log messages |
| Filters | (ad-hoc) | Add arbitrary field:value filters (e.g., `host = db0`) |

**Panels:**

- **Log Volume** — time-series bar chart showing log count over time, broken down by source. Helps identify spikes and anomalies at a glance.
- **Logs** — scrollable log viewer with timestamps, source labels, and expandable log details. Click any log entry to see all available fields.

**Tips:**

- Use the **ad-hoc Filters** variable to filter by `host`, `unit`, `component`, or any other field without needing a dedicated dropdown.
- The dashboard auto-refreshes every 10 seconds by default. Adjust or disable via the refresh picker in the top-right corner.
- Combine multiple filters to narrow down — e.g., set Node Role to `db`, Source to `cassandra`, Level to `Error` to see only Cassandra errors on database nodes.
- To search for exec job logs, set Source to `tool-runner` and use the Search box for the job name.

#### Explore Mode

For ad-hoc queries beyond what the dashboard provides:

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
source:cassandra AND "Exception"

# Time filter (in addition to --since)
_time:1h
```

For full LogsQL documentation, see the [Victoria Logs documentation](https://docs.victoriametrics.com/victorialogs/logsql/).

## Deployment

Victoria Logs and the OTel Collector are automatically deployed when you run:

```bash
easy-db-lab k8 apply
```

This deploys:
- Victoria Logs server on the control node
- OTel Collector DaemonSet on all nodes
- Grafana datasource configuration

## Verifying the Setup

Check that all components are running:

```bash
source env.sh
kubectl get pods -l app.kubernetes.io/name=victorialogs
kubectl get pods -l app.kubernetes.io/name=otel-collector
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

1. Verify OTel Collector pods are running:
   ```bash
   kubectl get pods -l app.kubernetes.io/name=otel-collector
   kubectl logs -l app.kubernetes.io/name=otel-collector
   ```

2. Check Victoria Logs is healthy:
   ```bash
   with-proxy curl http://control0:9428/health
   ```

3. Verify the cluster-config ConfigMap exists:
   ```bash
   kubectl get configmap cluster-config -o yaml
   ```

### Connection errors

The `logs query` command uses the internal SOCKS5 proxy to connect to Victoria Logs. If you see connection errors:

1. Ensure the cluster is running: `easy-db-lab status`
2. The proxy is started automatically when needed
3. Check that control node is accessible: `ssh control0 hostname`

## Listing Backups

List available VictoriaLogs backups in S3:

```bash
easy-db-lab logs ls
```

This displays a summary table of all backups grouped by timestamp, showing the number of files and total size for each.

## Importing Logs to an External Instance

Stream logs from the running cluster's VictoriaLogs to an external VictoriaLogs instance via the jsonline API:

```bash
# Import all logs
easy-db-lab logs import --target http://victorialogs:9428

# Import only specific logs
easy-db-lab logs import --target http://victorialogs:9428 --query 'source:cassandra'
```

This is useful for exporting logs at the end of test runs when running easy-db-lab from a Docker container. Unlike binary backups, this approach streams data via HTTP and can target any reachable VictoriaLogs instance.

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--target` | Target VictoriaLogs URL (required) | - |
| `--query` | LogsQL query for filtering | All logs (`*`) |

## Backup

Victoria Logs data can be backed up to S3 for disaster recovery using consistent snapshots.

### Creating a Backup

```bash
# Backup to cluster's default S3 bucket
easy-db-lab logs backup

# Backup to a custom S3 location
easy-db-lab logs backup --dest s3://my-backup-bucket/victorialogs
```

By default, backups are stored at:
`s3://{cluster-bucket}/victorialogs/{timestamp}/`

Use `--dest` to override the destination bucket and path.

### How It Works

The backup uses VictoriaLogs' snapshot API to create consistent, point-in-time copies:

1. **Create snapshots** — calls the VictoriaLogs snapshot API to create read-only snapshots of all active log partitions
2. **Sync to S3** — uploads each snapshot directory to S3 using `aws s3 sync`
3. **Cleanup** — deletes the snapshots from disk to free space (runs even if the sync step fails)

Using snapshots ensures data consistency, since VictoriaLogs may be actively writing to its data directory during the backup.

### What Gets Backed Up

- All log partitions (organized by date)
- Complete log history up to retention period (7 days default)

### Notes

- The process is non-disruptive; log ingestion continues during backup
- Snapshot cleanup always runs, even if the S3 upload fails, to avoid filling disk
- Persistent storage at `/mnt/db1/victorialogs` ensures logs survive pod restarts
