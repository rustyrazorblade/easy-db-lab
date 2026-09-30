# Metrics (Mimir)

Grafana Mimir stores the metrics from every node in the cluster. It runs on the control node and writes its blocks to the account bucket, so a cluster's metrics outlive the cluster. Every cluster's Mimir reads the whole shared store, so each cluster's Grafana shows the metrics of every tenant and of every past cluster.

## How metrics get there

The OpenTelemetry Collector runs on every node. It gathers host metrics, scrapes every database, kit and infrastructure exporter, and receives OTLP pushes (the OpenTelemetry Java agent, stress jobs, kits). It sends everything to Mimir with Prometheus remote write, at `http://mimir.default.svc.cluster.local:9009/api/v1/push`, with the cluster's tenant in the `X-Scope-OrgID` header. Tempo's metrics generator writes the span metrics (`traces_spanmetrics_*`) and the service graph to the same place.

Every series carries a `cluster` label (`<name>-<clusterId>`), so clusters that share a tenant stay distinguishable.

## Configuration

Mimir 3.2.1 runs as one process on the control node:

- **Ports**: 9009 (HTTP), 9097 (gRPC), 7947 (memberlist, loopback only), 11211 (memcached, loopback only)
- **Local data**: `/mnt/db1/mimir` on the control node (the write-ahead log, the last 15 minutes of blocks, and the store-gateway's index headers)
- **Object storage**: `s3://<account-bucket>/mimir/<tenant>/`
- **Tenancy**: native multi-tenancy; the tenant is the cluster's observability tenant

Mimir cuts a one-minute block and ships it within seconds, so a sample is in S3 about 2 minutes after it is written, while the cluster runs. A head with no writes for 2 minutes is compacted, so the last partial block ships too.

## The read path

Queries read the ingester and, through the store-gateway, every tenant's blocks in S3. The store-gateway finds blocks through each tenant's bucket index, which the [account compactor](compactor.md) rewrites every minute, and it syncs every minute. The ingester keeps local blocks for 15 minutes; older data is read from S3. At 15 minutes a query opens far fewer one-minute local blocks than it did at 2 hours, and the local copies it drops are already in S3. A block the compactor merged away is no longer read 10 minutes after its deletion mark (5 minutes for a running query), so a read opens the merged block instead of its many 1-minute sources.

Mimir queues up to 5000 queries per tenant (`query_scheduler.max_outstanding_requests_per_tenant`; the default is 100). One load of the heaviest dashboard sends about 150 queries, and the query frontend splits each by day and runs as many as 14 parts at once. Queries are not sharded: on one Mimir process, sharding split each query into about 20 parts and made a dashboard load several times slower. With the default, a full dashboard load filled the queue and Mimir refused the rest with HTTP 429, so panels showed errors. The limit of 5000 holds two full loads of that dashboard at once.

A memcached sidecar in the Mimir pod (2 GB, on the control node's loopback, port 11211) holds Mimir's caches. The store-gateway caches the index, chunks and bucket metadata it reads from S3, and the query frontend caches query results. So a dashboard refresh reads from memory what the last load already read from S3. Mimir also keeps its S3 connections open and reuses them, instead of opening a new TLS connection for each read. The caches only hold copies; a restart empties them, and nothing is lost.

A tenant that has no bucket index yet returns no stored data rather than an error. A stale bucket index is accepted for about 10 years, so metrics queries still succeed while the compactor is stopped; blocks shipped after it stopped become readable once it runs again.

**Nothing in the cluster deletes a block.** Mimir on a cluster runs no compactor and no retention. The account compactor merges blocks and removes the sources only after it wrote the merged block. Every sample stays in S3 until you delete it yourself.

## Querying metrics

### Grafana

Grafana's default datasource is **Mimir** (uid `mimir`). It sends the cluster's own tenant on every query, so it returns this tenant's data from every cluster in it. Dashboards narrow to one cluster with their `cluster` variable.

Grafana also has one metrics datasource per tenant in the shared store, **Mimir (&lt;tenant&gt;)** (uid `mimir-<tenant>`), and one for all of them, **Mimir (all tenants)** (uid `mimir--all`), which sends every tenant joined with `|`. `up` and `grafana update-config` list the tenants under `mimir/` in the account bucket and rebuild these datasources, so run `grafana update-config` to pick up a tenant that appeared after `up`. A UID longer than 40 characters is shortened to `mimir-<prefix>-<8 hex characters>`.

Dashboards reach these datasources through their **Metrics** picker, not a fixed uid. The picker opens on **Mimir** (the cluster's own tenant); pick another tenant, or all tenants, and every metric panel, variable and link on the dashboard follows. See [Monitoring](monitoring.md#tenant-pickers-and-the-current-cluster).

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

`down` flushes Mimir before it removes anything, at the same time as it saves the other signals: it stops the ingester, which cuts and ships every block it holds before it answers, then scales Mimir to 0. It checks nothing else. The metrics are recorded as saved the moment this succeeds, so a later `down` skips them. If a step fails, `down` removes nothing and Mimir stays as that step left it: it is never started again, and the report names the step, each backend's state, and `down --force`. See [`down`](../reference/commands.md#down).

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
