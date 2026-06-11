## Why

easy-db-lab supports Presto for federated SQL queries against running database kits, but Trino — the more widely-used open-source fork — is missing. Teams running Trino in production have no equivalent lab environment. We also take this opportunity to standardize the version flag across versioned kits (`--clickhouse-version` → `--version`).

## What Changes

- **New Trino kit** at `kits/trino/` as a fully independent kit (no shared code with Presto) with:
  - Helm-based deployment via `trinodb/trino` chart targeting app nodes
  - `--version` flag for selecting the Trino release
  - Catalog connectors for Cassandra and ClickHouse (wired via `post-workload-start`/`post-workload-stop` hooks)
  - Sibling-directory scan for `trino-catalog.properties` (extensibility for custom workloads)
  - Pyroscope profiling agent patched onto coordinator and worker deployments
  - `trino sql` command via the `sql` capability and `io.trino.jdbc.TrinoDriver`
- **Rename `--clickhouse-version` to `--version`** in the ClickHouse kit for consistency

## Capabilities

### New Capabilities

- `trino`: Trino kit lifecycle, catalog connector management, and SQL execution against a running Trino cluster

### Modified Capabilities

- `clickhouse`: `--clickhouse-version` flag renamed to `--version` (consistent with all versioned kits)

## Impact

- New kit resource directory: `src/main/resources/com/rustyrazorblade/easydblab/kits/trino/`
- New spec: `openspec/specs/trino/spec.md`
- Modified: `src/main/resources/com/rustyrazorblade/easydblab/kits/clickhouse/kit.yaml` (flag rename)
- Modified: `openspec/specs/clickhouse/spec.md` (flag rename requirement)
- New Gradle dependency: Trino JDBC driver (`io.trino:trino-jdbc`)
- No Kotlin source changes required — kit is entirely shell scripts + kit.yaml
