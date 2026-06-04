## Why

easy-db-lab supports ClickHouse and Presto but has no OLAP-optimized distributed SQL database with a split coordinator/storage architecture. StarRocks fills this gap: it speaks MySQL protocol, offers vectorized query execution, and deploys cleanly on Kubernetes via its official operator.

## What Changes

- New `starrocks` kit providing full lifecycle management (install / start / stop / uninstall)
- StarRocks deployed via `kube-starrocks` Helm chart (installs operator + `StarRocksCluster` CR)
- FE (Frontend) pods placed on `app` nodes; BE (Backend) pods placed on `db` nodes — honoring StarRocks' architectural separation of coordinator and storage roles
- `DB_NODE_COUNT` and `APP_NODE_COUNT` template variables used directly to set BE and FE replica counts respectively — no new kit args for replica counts
- NodePort services exposing MySQL protocol (9030) and HTTP (8030)
- `sql` capability via MySQL JDBC driver (user: `root`)
- Shell guard at start time: fails fast with clear message if no app nodes are provisioned
- Metrics scraping from FE Prometheus endpoint (`/metrics` on port 8030)

## Capabilities

### New Capabilities

- `starrocks`: StarRocks kit — install, start, stop, uninstall lifecycle; SQL capability via MySQL JDBC; NodePort endpoints on 9030 (MySQL) and 8030 (HTTP); metrics scraping from FE

### Modified Capabilities

_(none)_

## Impact

- New kit resource directory: `src/main/resources/com/rustyrazorblade/easydblab/kits/starrocks/`
- Dependency: `mysql-connector-j` JDBC driver must be on the classpath for the `sql` capability
- No Kotlin source changes required — kit is purely declarative (`kit.yaml` + templates)
- Requires clusters provisioned with at least 1 app node (`--app-nodes 1`) and 1 db node
