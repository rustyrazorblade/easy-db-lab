# TiDB

The `tidb` kit deploys a [TiDB](https://www.pingcap.com/tidb/) HTAP cluster using the
TiDB Operator. TiDB combines a MySQL-compatible SQL layer with two storage engines:
TiKV (row store, for OLTP) and TiFlash (columnar store, for analytics) — letting you run
transactional and analytical queries against the same data.

## Prerequisites

- Cluster is up (`easy-db-lab up`)
- At least 1 db node (runs TiKV and TiFlash)
- At least 1 app node (runs TiDB and PD)

## Quick Start

```bash
easy-db-lab kit install tidb
easy-db-lab tidb start
easy-db-lab tidb sql "SELECT tidb_version()"
```

`tidb start` deploys the TiDB Operator-managed cluster and waits for each component
(PD, TiKV, TiDB, TiFlash) to become Ready. TiFlash takes the longest — expect a few
minutes on first start while images pull.

## Flags

| Flag | Default | Description |
|---|---|---|
| `--version` | `v8.5.2` | TiDB version to deploy |
| `--replicas` | db node count | Number of TiKV and TiFlash replicas (one per db node) |

## Cluster Layout

| Component | Node type | Replicas | Role |
|-----------|-----------|----------|------|
| PD | app | 1 | Placement driver / metadata |
| TiDB | app | one per app node | MySQL-compatible SQL layer |
| TiKV | db | `--replicas` | Row store (Raft) |
| TiFlash | db | `--replicas` | Columnar store (HTAP) |

## Connecting

TiDB speaks the MySQL wire protocol, exposed as NodePort 30400 on every cluster node:

```bash
mysql -h <node-ip> -P 30400 -u root
```

Or use the built-in SQL command, which resolves the endpoint for you:

```bash
easy-db-lab tidb sql "SHOW DATABASES"
```

## Using TiFlash

Tables are not automatically replicated to TiFlash. Enable replication per table:

```sql
ALTER TABLE my_table SET TIFLASH REPLICA 1;
```

Once the replica is in place, TiDB's optimizer routes analytical queries to TiFlash
automatically. To force it for a specific query:

```sql
SELECT /*+ read_from_storage(tiflash[my_table]) */ count(*) FROM my_table;
```

## Lifecycle

```bash
easy-db-lab tidb start       # deploy the TiDB cluster
easy-db-lab tidb status      # show running state and endpoints
easy-db-lab tidb stop        # tear down the TiDB cluster
easy-db-lab tidb uninstall   # remove the TiDB Operator (requires stop first)
```

`uninstall` refuses to run while the TiDB cluster is still up — run `tidb stop` first.

## Monitoring

The kit registers four Prometheus scrape jobs with the cluster's observability stack
automatically — no configuration needed:

| Job label | Component | What it covers |
|-----------|-----------|----------------|
| `tidb-sql` | TiDB | Connections, query throughput, plan cache, errors |
| `pd` | PD | Cluster health, TSO, region scheduling |
| `tikv` | TiKV | Raft, RocksDB storage, coprocessor |
| `tiflash` | TiFlash | MPP tasks, data exchange, storage throughput |

Metrics are available in Grafana and VictoriaMetrics as soon as the kit starts.

TiDB also exports traces to Tempo (via the OTel Collector's Jaeger receiver, since TiDB
v8.x has no native OTLP support). Search for them in Grafana with `service.name=TiDB` —
the tag is case-sensitive.

## Benchmarking

TiDB declares the `sql` capability, so bench kits can target it directly. See
[Sysbench](sysbench.md):

```bash
easy-db-lab kit install sysbench --target tidb
```
