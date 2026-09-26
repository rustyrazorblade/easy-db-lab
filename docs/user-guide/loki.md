# Logs (Loki)

Grafana Loki stores the logs from every node in the cluster. It runs on the control node and writes its chunks and index to the account bucket, so a cluster's logs outlive the cluster and can be read from any later cluster in the same tenant.

## How logs get there

The OpenTelemetry Collector runs on every node and reads:

| Source | Path | `source` label |
|--------|------|----------------|
| Cassandra | `/mnt/db1/cassandra/logs/*.log` | `cassandra` |
| System logs | `/var/log/**/*.log`, `/var/log/messages`, `/var/log/syslog` | `system` |
| Tools run with `exec run` | `/var/log/easydblab/tools/*.log` | `tool-runner` |
| Pod stdout and stderr (including ClickHouse) | `/var/log/pods/**` | none |

Fluent Bit reads the systemd journal on every node and forwards it to the collector with `source="journald"`. OTLP logs (the OpenTelemetry Java agent, Spark jobs) arrive at the collector directly.

The collector sends everything to Loki's OTLP endpoint, `http://loki.default.svc.cluster.local:3100/otlp`, with the cluster's tenant in the `X-Scope-OrgID` header.

## Labels

Four resource attributes become stream labels. Loki adds `service_name`, and for pod logs the Kubernetes labels (`k8s_namespace_name`, `k8s_pod_name`, `k8s_container_name`):

| Label | Value |
|-------|-------|
| `cluster` | `<name>-<clusterId>`; every stream carries it |
| `host_name` | the node (`db0`, `app0`, `control0`) |
| `node_role` | `db`, `app` or `control` |
| `source` | see the table above |
| `service_name` | the OTel service name; `unknown_service` when the sender sets none |

Every other attribute (the systemd unit, the log file, the trace and span ids, the severity) is stored as structured metadata. LogQL reads it with a label filter after the selector, such as `| systemd_unit="docker.service"`.

## Configuration

Loki 3.7.8 runs as one process (`target: all`) on the control node:

- **Ports**: 3100 (HTTP), 9098 (gRPC)
- **Local data**: `/mnt/db1/loki` on the control node (write-ahead log, open chunks and the index not yet shipped)
- **Object storage**: `s3://<account-bucket>/observability/logs/`; chunks sit under the tenant, the TSDB index under `index/`
- **Tenancy**: native multi-tenancy; the tenant is the cluster's observability tenant

Loki uploads its index to S3 as it rotates it, and reads the other clusters' index from S3 every 5 minutes. A line from another cluster in the tenant can therefore take up to 5 minutes to become readable here.

**Nothing is deleted.** Loki's compactor is idle, retention is off, and the delete API is not served. Every chunk and index file stays in S3 until you delete it yourself.

## Querying logs

### The CLI

`easy-db-lab logs query` reads this cluster's logs:

```bash
# The last hour
easy-db-lab logs query

# By source, host or systemd unit
easy-db-lab logs query --source cassandra --host db0
easy-db-lab logs query --source journald --unit docker.service

# Lines containing text
easy-db-lab logs query --grep "OutOfMemory"

# Time range and limit
easy-db-lab logs query --since 30m --limit 500

# A raw LogQL query, sent unchanged
easy-db-lab logs query -q '{source="cassandra"} |= "Exception"'
```

| Option | Description | Default |
|--------|-------------|---------|
| `--source`, `-s` | Log source: `cassandra`, `journald`, `system`, `tool-runner`, `emr` | All sources |
| `--host`, `-H` | Hostname (`db0`, `app0`, `control0`) | All hosts |
| `--unit` | systemd unit | All units |
| `--since` | Time range (`1h`, `30m`, `1d`) | `1h` |
| `--limit`, `-n` | Most lines to return | 100 |
| `--grep`, `-g` | Only lines containing this text | None |
| `--query`, `-q` | Raw LogQL query | None |

Every option but `--query` is scoped to this cluster. A raw query is sent as written, so it reads every cluster in the tenant unless it names a `cluster`.

### Grafana

The **Loki** datasource (uid `loki`) sends the tenant on every query.

The **Log Investigation** dashboard (Dashboards → Log Investigation) filters by:

| Filter | Description |
|--------|-------------|
| Cluster | One cluster, or all of them in the tenant |
| Service | The `service_name` label |
| Source | The `source` label |
| Severity | Log severity |
| Search | Free text |
| Filters | Ad-hoc `label = value` filters |

For anything else, open **Explore**, choose the **Loki** datasource, and write LogQL:

```
{cluster="<name>-<clusterId>", source="cassandra"} |= "Exception"
{cluster=~".+", host_name="db0"} | systemd_unit="cassandra.service"
sum by (source) (count_over_time({cluster="<name>-<clusterId>"}[5m]))
```

A LogQL selector needs at least one matcher that cannot match an empty value, which is why "all clusters" is written `cluster=~".+"`. See the [LogQL documentation](https://grafana.com/docs/loki/latest/query/).

A trace in Tempo links to its logs through the `trace_id`.

### HTTP API

A query that carries no tenant reads nothing, so always send `X-Scope-OrgID`:

```bash
source env.sh
with-proxy curl -G -H 'X-Scope-OrgID: <tenant>' \
  --data-urlencode 'query={source="cassandra"}' \
  "http://control0:3100/loki/api/v1/query_range"
```

## Annotations

Every Grafana annotation is also written to Loki as its own stream (`source="annotation"`, with the `cluster` label and an `annotation_id`), so the annotations stay readable from S3 after the cluster and its Grafana are gone. The core dashboards read them back from Loki. Loki accepts entries only from the last 8760 hours to 24 hours ahead, so an annotation outside that window is not mirrored; the tool warns with its id, and the annotation stays in Grafana's JSON backup.

## Teardown

`down` flushes Loki before it removes anything: it stops the ingester, which writes every open chunk to S3, then checks that each index file Loki built is in S3. If any step fails, `down` stops there with the cluster intact and Loki left as that step left it: it is never started again, and the report names the step, each backend's state, and `down --force`. See [`down`](../reference/commands.md#down).

## Troubleshooting

### No logs appearing

1. Check that Loki is running and ready:
   ```bash
   kubectl get pods -l app.kubernetes.io/name=loki
   kubectl logs -l app.kubernetes.io/name=loki
   ```

2. Check that the collector is sending. A failing exporter shows up in `otelcol_exporter_send_failed_log_records_total` in Mimir, and in the collector's log:
   ```bash
   kubectl logs -l app.kubernetes.io/name=otel-collector
   ```

3. Check that the `cluster-config` ConfigMap carries the tenant:
   ```bash
   kubectl get configmap cluster-config -o yaml
   ```

### Connection errors from `logs query`

`logs query` reaches Loki through the cluster's SOCKS proxy, which it starts when needed. Check that the cluster is up (`easy-db-lab status`) and that the control node answers (`ssh control0 hostname`).
