# Metrics (Mimir)

Grafana Mimir stores the metrics from every node in the cluster. It runs on the control node and writes its blocks to the account bucket, so a cluster's metrics outlive the cluster and can be read from any later cluster in the same tenant.

## How metrics get there

The OpenTelemetry Collector runs on every node. It gathers host metrics, scrapes every database, kit and infrastructure exporter, and receives OTLP pushes (the OpenTelemetry Java agent, stress jobs, kits). It sends everything to Mimir with Prometheus remote write, at `http://mimir.default.svc.cluster.local:9009/api/v1/push`, with the cluster's tenant in the `X-Scope-OrgID` header. Tempo's metrics generator writes the span metrics (`traces_spanmetrics_*`) and the service graph to the same place.

Every series carries a `cluster` label (`<name>-<clusterId>`), so clusters that share a tenant stay distinguishable.

## Configuration

Mimir 3.2.1 runs as one process on the control node:

- **Ports**: 9009 (HTTP), 9097 (gRPC), 7947 (memberlist, loopback only)
- **Local data**: `/mnt/db1/mimir` on the control node (write-ahead log and the blocks not yet shipped)
- **Object storage**: `s3://<account-bucket>/observabilitymetrics/<tenant>/`
- **Tenancy**: native multi-tenancy; the tenant is the cluster's observability tenant

Mimir cuts a two-hour block and ships it to S3 within a minute. Queries for recent data are served from the ingester; older data is read from S3.

**Nothing is deleted.** Mimir runs no compactor and no retention. Every block it ships stays in S3 until you delete it yourself.

## Querying metrics

### Grafana

Grafana's default datasource is **Mimir** (uid `mimir`). It sends the tenant on every query, so it returns this tenant's data from every cluster in it. Dashboards narrow to one cluster with their `cluster` variable.

### HTTP API

Mimir serves the Prometheus API under `/prometheus`. A query that carries no tenant reads nothing, so always send `X-Scope-OrgID`:

```bash
source env.sh

# Every metric name
with-proxy curl -H 'X-Scope-OrgID: <tenant>' \
  "http://control0:9009/prometheus/api/v1/label/__name__/values"

# An instant query
with-proxy curl -H 'X-Scope-OrgID: <tenant>' \
  "http://control0:9009/prometheus/api/v1/query?query=up"
```

`easy-db-lab status` prints the tenant and the Mimir URL.

## Teardown

`down` flushes Mimir before it removes anything: it stops the ingester, which cuts and ships every block it holds, then checks that each local block is in S3. If any step fails, `down` stops there with the cluster intact and Mimir left as that step left it: it is never started again, and the report names the step, each backend's state, and `down --force`. See [`down`](../reference/commands.md#down).

## Troubleshooting

### No metrics appearing

1. Check that Mimir is running and ready:
   ```bash
   kubectl get pods -l app.kubernetes.io/name=mimir
   kubectl logs -l app.kubernetes.io/name=mimir
   ```

2. Check that the collector is sending. It scrapes its own telemetry into Mimir (job `otel-collector`); a failing exporter shows up in `otelcol_exporter_send_failed_metric_points_total`, and in the collector's log:
   ```bash
   kubectl logs -l app.kubernetes.io/name=otel-collector
   ```

3. Check that the `cluster-config` ConfigMap carries the tenant:
   ```bash
   kubectl get configmap cluster-config -o yaml
   ```

### An empty result from the HTTP API

The request had no `X-Scope-OrgID` header, or named another tenant.
