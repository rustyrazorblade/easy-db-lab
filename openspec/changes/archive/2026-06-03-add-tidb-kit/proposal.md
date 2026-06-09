## Why

easy-db-lab supports Cassandra, ClickHouse, and OpenSearch but has no HTAP database option. TiDB is a MySQL-compatible distributed database with a built-in columnar store (TiFlash) that enables running OLTP and analytics workloads simultaneously — making it a useful addition for mixed-workload testing.

## What Changes

- New `tidb` kit deployed via TiDB Operator (Helm) managing a `TidbCluster` CRD
- TiKV (row store) and TiFlash (columnar store) run on db nodes; TiDB SQL layer and PD (placement driver) run on app nodes
- Requires a mixed cluster (≥1 db node, ≥1 app node); pre-flight shell check enforces this
- MySQL JDBC driver (`com.mysql:mysql-connector-j`) added as a new Gradle dependency
- `sql` capability wired to MySQL port 4000 using `com.mysql.cj.jdbc.Driver`
- Prometheus metrics scraped from `:10080/metrics` (TiDB native endpoint)
- No storage size arg — `10Ti` hardcoded in the manifest (local PVs, size is metadata only)

## Capabilities

### New Capabilities

- `tidb`: TiDB HTAP kit — operator-managed cluster with TiKV row store, TiFlash columnar store, PD placement driver, and TiDB SQL layer; MySQL-compatible endpoint on port 4000

### Modified Capabilities

## Impact

- New kit directory: `src/main/resources/com/rustyrazorblade/easydblab/kits/tidb/`
- `build.gradle.kts` — add `mysql-connector-j` dependency
- `gradle/libs.versions.toml` — add version entry for `mysql-connector-j`
- No Kotlin code changes required — kit is entirely declarative via `kit.yaml` and manifest templates
