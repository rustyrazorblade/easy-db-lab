## Why

easy-db-lab supports Cassandra and ClickHouse as database workloads, but PostgreSQL — the world's most widely deployed open-source relational database — is missing. Adding a postgres kit lets users run SQL workloads, benchmark Postgres performance, and query it via Presto alongside other databases.

## What Changes

- New `postgres` kit deployed via the CloudNativePG (CNPG) operator on K8s db nodes
- `postgres sql` command for interactive SQL execution via JDBC
- Presto integration: `postgres` catalog auto-injected when postgres and presto are both running
- PostgreSQL JDBC driver added as a runtime dependency
- ClickHouse `--clickhouse-version` arg renamed to `--version` (consistency fix, no behavior change)

## Capabilities

### New Capabilities

- `postgres`: Deploy and manage PostgreSQL clusters via the CloudNativePG operator; supports configurable instance count (replication), persistent storage, SQL execution, and Presto catalog integration.

### Modified Capabilities

- `presto`: PostgreSQL is now a supported catalog source alongside Cassandra and ClickHouse.

## Impact

- New files: `kits/postgres/` directory (kit.yaml, templates), `kits/presto/catalogs/postgres.properties.template`
- `gradle/libs.versions.toml` and `build.gradle.kts`: add `org.postgresql:postgresql` JDBC driver
- `kits/clickhouse/kit.yaml`: `--clickhouse-version` renamed to `--version`
- `KitInfoTest.kt`: updated assertion for renamed ClickHouse flag (already applied)
- No breaking changes to existing kits or commands
