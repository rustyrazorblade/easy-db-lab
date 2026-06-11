## 1. ClickHouse Kit: Rename --version Flag

- [x] 1.1 Rename `--clickhouse-version` flag to `--version` and variable `CLICKHOUSE_VERSION` in `kits/clickhouse/kit.yaml`
- [x] 1.2 Update all references to `__CLICKHOUSE_VERSION__` in ClickHouse kit templates to use `__VERSION__`
- [x] 1.3 Update `openspec/specs/clickhouse/spec.md` to reflect the renamed flag

## 2. Trino Kit: Core Files

- [x] 2.1 Add Trino JDBC driver dependency (`io.trino:trino-jdbc`) to `build.gradle.kts`
- [x] 2.2 Create `kits/trino/kit.yaml` with `--version` arg, `sql` capability, endpoints, metrics (port 8080), and catalog hooks
- [x] 2.3 Create `kits/trino/values.yaml.template` with coordinator/worker node selectors and worker count
- [x] 2.4 Create `kits/trino/README.md.template`

## 3. Trino Kit: Shell Scripts

- [x] 3.1 Create `kits/trino/bin/start.sh.template` — helm repo add + `update-catalogs.sh`, then patch coordinator and worker deployments for Pyroscope, then rollout wait
- [x] 3.2 Create `kits/trino/bin/stop.sh.template` — scale coordinator and worker to zero replicas
- [x] 3.3 Create `kits/trino/bin/uninstall.sh.template` — helm uninstall the trino release
- [x] 3.4 Create `kits/trino/bin/update-catalogs.sh.template` — build catalog YAML from `RUNNING_KITS` + sibling `trino-catalog.properties` scan, then `helm upgrade --install`

## 4. Trino Kit: Catalog Connector Files

- [x] 4.1 Create `kits/trino/catalogs/cassandra.properties.template` with `connector.name=cassandra` and `__DB_NODE_IPS__`
- [x] 4.2 Create `kits/trino/catalogs/clickhouse.properties.template` with `connector.name=clickhouse` and unprefixed JDBC properties

## 5. Trino Kit: Observability

- [x] 5.1 Create `kits/trino/metrics-catalog.json` (placeholder matching Presto structure, port 8080)

## 6. Spec and Documentation

- [x] 6.1 Create `openspec/specs/trino/spec.md` from the change spec
- [x] 6.2 Update user docs (`docs/`) to include Trino kit reference
