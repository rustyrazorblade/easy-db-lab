## Why

Every new SQL-capable kit requires a per-kit Kotlin service interface, implementation class,
command class, and Koin registration — all structurally identical. `KitEndpoint` already
declares the JDBC URL pattern and node type. `ServerType.from()` already maps node-type
strings to cluster state keys. Everything needed to drive a generic SQL command is already
in `kit.yaml`; the Kotlin boilerplate adds no value.

## What Changes

- `KitCapability` sealed class added to `KitConfig.kt`; `KitConfig` gains a `capabilities` field
- New `KitJdbcSqlService` — flat service class parameterised by `KitEndpoint` + user
- New `KitSqlCommand` — single shared command registered once per kit that declares a `sql` capability
- `KitRunnerCommandFactory` becomes a Koin component and registers capability commands alongside lifecycle phases
- `presto/kit.yaml` and `clickhouse/kit.yaml` gain a `capabilities:` block
- `PrestoService`, `DefaultPrestoService`, `ClickHouseService`, `DefaultClickHouseService`,
  `AbstractJdbcSqlService`, `PrestoSql`, `ClickHouseSql` are all deleted
- Associated Koin registrations and tests are removed or replaced

## Capabilities

### New Capabilities

- `kit-capabilities/sql`: Any kit with a `jdbc` endpoint and `capabilities: [{type: sql}]`
  automatically gets a `sql` subcommand — zero Kotlin required

### Modified Capabilities

- `presto sql`: now driven by the `sql` capability; behaviour unchanged for the user
- `clickhouse sql`: now driven by the `sql` capability; behaviour unchanged for the user

### Removed Capabilities

- Per-kit JDBC service abstraction (`AbstractJdbcSqlService` and subclasses)

## Impact

- `services/KitConfig.kt` — add `KitCapability` sealed class and `capabilities` field
- `services/sql/KitJdbcSqlService.kt` — new flat service (replaces `AbstractJdbcSqlService`)
- `commands/kit/KitSqlCommand.kt` — new shared command
- `commands/install/KitRunnerCommandFactory.kt` — Koin component, iterates capabilities
- `kits/presto/kit.yaml` — add capabilities block with `driver-class`
- `kits/clickhouse/kit.yaml` — add capabilities block
- `services/presto/PrestoService.kt` — deleted
- `services/clickhouse/ClickHouseService.kt` — deleted
- `services/sql/SqlQueryService.kt` — `AbstractJdbcSqlService` deleted; `SqlQueryResult` and
  `JdbcConnectionFactory` retained (used by `KitJdbcSqlService`)
- `commands/presto/PrestoSql.kt` — deleted
- `commands/clickhouse/ClickHouseSql.kt` — deleted
- `services/ServicesModule.kt` — remove Koin registrations for deleted classes
- `openspec/specs/presto/spec.md` — updated
- `openspec/specs/clickhouse/spec.md` — REQ-CH-005 added
- `openspec/specs/kit-capabilities/spec.md` — new
